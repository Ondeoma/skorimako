package skorimako.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.staticcontent.*
import skorimako.api.*
import skorimako.auth.{OidcClient, OidcConfig, SecretBox}
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.ErrorDto
import skorimako.storage.{LocalObjectStore, ObjectStore, S3Config, S3ObjectStore}

import java.nio.file.Paths
import scala.concurrent.duration.*

final case class AppConfig(
    port: Int,
    db: DbConfig,
    oidc: Option[OidcConfig],
    api: ApiConfig,
    staticDir: Option[String],
    s3: Option[S3Config],
    storageDir: Option[String],
    dataDir: java.nio.file.Path,
    appSecret: String,
    nodeId: String,
    ingest: IngestConfig,
    query: QueryConfig,
    duck: DuckConfig,
    maintenance: MaintenanceConfig,
    nodeUrl: Option[String],
    selfMonitor: Boolean
)

/** 設定は環境変数から読む。 */
object Config:
  private def opt(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(name: String): Option[Int]    = opt(name).flatMap(_.toIntOption)
  private def enabled(name: String): Boolean    = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")

  /** OAUTH_CLIENTS: [{"id":"stui","secret":"…","redirectUri":"https://…/callback","name":"stui","scopes":["read"]}] */
  private def oauthClients: Either[String, List[OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ => "OAUTH_CLIENTS は JSON の配列で指定してください").flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter((id, sec, uri) => id.nonEmpty && sec.length >= 16 && (uri.startsWith("https://") || uri.startsWith("http://localhost")))
          .map((id, sec, uri) => OAuthClient(id, sec, uri, c.get[String]("name").getOrElse(id),
            c.get[List[String]]("scopes").toOption.map(_.filter(Set("read", "write"))).filter(_.nonEmpty).getOrElse(List("read"))))
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for
      dbUrl  <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub    <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match
                case (Some(_), Some(_), Some(_)) if devLogin => Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
                case (Some(i), Some(c), Some(s)) =>
                  Right(Some(OidcConfig(i, c, s, s"${pub.stripSuffix("/")}/auth/callback", opt("OIDC_GROUPS_CLAIM").getOrElse("groups"))))
                case (None, None, None) if devLogin => Right(None)
                case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      secret <- opt("APP_SECRET") match
                  case Some(s) if s.length >= 16 => Right(s)
                  case Some(_) => Left("APP_SECRET は16文字以上にしてください")
                  case None if devLogin => Right("skorimako-development-secret")
                  case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      _ <- Either.cond(opt("S3_ENDPOINT").isDefined || opt("STORAGE_DIR").isDefined, (),
             "S3_ENDPOINT（S3 互換ストレージ）か STORAGE_DIR（1台だけで動かすときのローカルのディレクトリ）を設定してください")
      clients <- oauthClients
    yield
      val base     = pub.stripSuffix("/")
      val dataDir  = Paths.get(opt("DATA_DIR").getOrElse("data")).toAbsolutePath.normalize()
      val nodeId   = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" + java.util.UUID.randomUUID().toString.take(8))
      val flush    = int("FLUSH_SECONDS").filter(_ > 0).getOrElse(10)
      val retention = int("RETENTION_DAYS").filter(_ > 0).getOrElse(30)
      val duck     = DuckConfig(opt("DUCKDB_MEMORY").getOrElse("2GB"), int("DUCKDB_THREADS").getOrElse(4), dataDir.resolve("duck"))
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
        oidc = oidc,
        api = ApiConfig(
          adminGroups = opt("ADMIN_GROUPS").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
          cookieSecure = base.startsWith("https://"),
          publicUrl = base,
          devLogin = devLogin,
          timezone = opt("TIMEZONE").getOrElse("Asia/Tokyo"),
          retentionDays = retention,
          flushSeconds = flush,
          maxIngestBytes = int("MAX_INGEST_MB").getOrElse(32).toLong * 1024 * 1024,
          webhookDenyPrivate = enabled("WEBHOOK_DENY_PRIVATE"),
          oauthClients = clients,
          // STUI_URL・STUI_CLIENT_ID・STUI_CLIENT_SECRET（STUI_INTERNAL_URL はサーバーから呼ぶ URL）
          stui = (opt("STUI_URL"), opt("STUI_CLIENT_ID"), opt("STUI_CLIENT_SECRET")).mapN((u, id, sec) =>
            LinkService("stui", u.stripSuffix("/"), id, sec, opt("STUI_INTERNAL_URL").map(_.stripSuffix("/")))),
          internalSecret = secret,
          grpcPort = int("GRPC_PORT").getOrElse(4317)
        ),
        staticDir = opt("STATIC_DIR"),
        s3 = opt("S3_ENDPOINT").map(ep => S3Config(ep, opt("S3_REGION").getOrElse("us-east-1"), opt("S3_BUCKET").getOrElse("skorimako"),
          opt("S3_ACCESS_KEY").getOrElse(""), opt("S3_SECRET_KEY").getOrElse(""), !opt("S3_PATH_STYLE").contains("false"), opt("S3_PREFIX").getOrElse(""))),
        storageDir = opt("STORAGE_DIR"),
        dataDir = dataDir,
        appSecret = secret,
        nodeId = nodeId,
        ingest = IngestConfig(dataDir, nodeId, flush.seconds, int("FLUSH_ROWS").getOrElse(200000), int("MAX_BUFFERED_ROWS").getOrElse(2000000).toLong,
          enabled("WAL_SYNC")),
        query = QueryConfig(dataDir.resolve("cache"), int("CACHE_MAX_GB").getOrElse(10).toLong << 30, int("QUERY_TIMEOUT_SECONDS").getOrElse(60).seconds,
          int("QUERY_CONCURRENCY").getOrElse(4)),
        duck = duck,
        maintenance = MaintenanceConfig(retention),
        nodeUrl = opt("NODE_URL").map(_.stripSuffix("/")),
        selfMonitor = !opt("SELF_MONITOR").contains("false")
      )
    ).left.map(new IllegalArgumentException(_))
  )

object Main extends IOApp.Simple:

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for
      _   <- IO(java.security.Security.setProperty("networkaddress.cache.ttl", "30"))
      cfg <- Config.load
      n   <- migrateWithRetry(cfg)
      _   <- IO.println(s"[skorimako] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for
        xa     <- Database.transactor(cfg.db)
        http   <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build
        users   = new UserStore(xa)
        streams = new StreamStore(xa)
        alerts  = new AlertStore(xa)
        apps    = new AppStore(xa)
        lock    = new JobLock(xa, cfg.nodeId)
        box     = new SecretBox(cfg.appSecret)
        oidc   <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        objects <- objectStore(cfg)
        stats    = new Stats
        acl     <- Resource.eval(Acl.create(streams))
        transform <- Resource.eval(Transform.create(streams))
        engine <- Resource.eval(QueryEngine.create(streams, objects, cfg.duck, cfg.query, acl, stats))
        // 止めるときは、たまっている行を書き出してから
        ingester <- Resource.make(Ingester.create(cfg.ingest, cfg.duck, streams, objects, stats))(_.shutdown)
        ingest   = new IngestService(ingester, transform)
        peers    = new PeerRoutes(ingester, streams, http, cfg.nodeId, cfg.nodeUrl, cfg.api.internalSecret)
        _ <- Resource.eval(engine.setLive(peers.live))
        _ <- peers.heartbeat
        maint    = new Maintenance(streams, objects, engine, cfg.duck, cfg.maintenance, lock, cfg.nodeId, cfg.dataDir.resolve("tmp"))
        sess     = new SessionAuth(users, cfg.api)
        stuiCli  = new StuiClient(cfg.api.stui, new IntegrationTokenStore(xa), http, box, cfg.api.publicUrl)
        alertSvc = new AlertService(alerts, engine, http, box, cfg.api, stuiCli, stats)
        selfMon  = new SelfMonitor(ingest, stats, apps, streams, lock, cfg.nodeId)
        _ <- Resource.eval(startupNotes(cfg))
        _ <- Resource.eval(IO.whenA(cfg.selfMonitor)(selfMon.ensureDashboard.handleErrorWith(e => IO.println(s"[skorimako] self dashboard: $e"))))
        loops = List[IO[Unit]](
                  ingester.loop, alertSvc.loop, alertSvc.deliveryLoop, maint.compactLoop, maint.retentionLoop, maint.cacheLoop,
                  purgeLoop(users, alerts, lock), transform.loop
                ) ++ Option.when(cfg.selfMonitor)(selfMon.loop).toList
        _ <- loops.traverse_(_.background)
        ingestRoutes = new IngestRoutes(ingest, apps, sess, cfg.api)
        _ <- if cfg.api.grpcPort > 0 then GrpcIngest.resource(cfg.api.grpcPort, ingest, sess, cfg.api.maxIngestBytes.toInt) else Resource.unit[IO]
        rest =
          healthRoutes(xa, cfg.nodeId) <+>
            new AuthRoutes(oidc, users, sess, cfg.api).routes <+>
            new UserRoutes(users, sess).routes <+>
            ingestRoutes.routes <+>
            new PromRoutes(ingest, engine, streams, sess, cfg.api).routes <+>
            new DataRoutes(streams, alerts, engine, ingester, transform, objects, users, sess, cfg.api).routes <+>
            new AlertRoutes(alerts, alertSvc, users, box, sess, acl).routes <+>
            new AppRoutes(apps, streams, users, sess).routes <+>
            new OAuthRoutes(new OAuthStore(xa), users, sess, cfg.api).routes <+>
            stuiCli.routes(sess, users) <+>
            new LinkRoutes(streams, alerts, apps, acl, sess, cfg.api).routes <+>
            peers.routes
        app = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        all = cfg.staticDir.fold(app)(d => app <+> spa(d))
        _ <- EmberServerBuilder.default[IO]
               .withHost(ipv4"0.0.0.0")
               .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
               .withShutdownTimeout(5.seconds)
               .withMaxHeaderSize(64 * 1024)
               .withHttpApp(ErrorHandling.Custom.recoverWith(Guards(all, cfg.api).orNotFound)(handler))
               .build
        _ <- Resource.eval(IO.println(s"[skorimako] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      yield ()).useForever
    yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(cfg: AppConfig, attempt: Int = 1): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else IO.println(s"[skorimako] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *> IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  private def objectStore(cfg: AppConfig): Resource[IO, ObjectStore] = cfg.s3 match
    case Some(c) => S3ObjectStore.resource(c).evalTap(_.ensureBucket).widen[ObjectStore]
    case None =>
      val dir = Paths.get(cfg.storageDir.getOrElse("storage")).toAbsolutePath.normalize()
      Resource.eval(IO.println(s"[skorimako] WARNING: S3_ENDPOINT が未設定のため、データを $dir に置きます（1台だけの構成。冗長化には S3 互換ストレージが必要です）")
        .as(new LocalObjectStore(dir)))

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(xa: doobie.Transactor[IO], node: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true)  => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e)      => text(Status.ServiceUnavailable, s"database unavailable: ${e.getMessage}")
      }
  }

  private def text(status: Status, body: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def startupNotes(cfg: AppConfig): IO[Unit] =
    IO.whenA(cfg.oidc.isEmpty)(IO.println("[skorimako] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  private val handler: PartialFunction[Throwable, IO[Response[IO]]] = {
    case _: org.http4s.server.middleware.EntityLimiter.EntityTooLarge => PayloadTooLarge(ErrorDto("本文が大きすぎます"))
    case e =>
      IO.println(s"[skorimako] unhandled error: $e") *> InternalServerError(ErrorDto("サーバーでエラーが発生しました"))
  }

  /** 期限切れのセッション・古い監査ログ・アラートの履歴と送信の記録を消す（1ノードだけ）。 */
  private def purgeLoop(users: UserStore, alerts: AlertStore, lock: JobLock): IO[Unit] =
    Periodic.run(10.minutes, 1.hour)(e => s"[skorimako] purge failed: $e")(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> users.purgeAudit(730).void *> alerts.purgeEvents(180).void *> alerts.purgeDeliveries(30).void))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。 */
  private def spa(dir: String): HttpRoutes[IO] =
    val files = fileService[IO](FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _ if !Set("api", "auth", "mcp", "v1", "rum/", "oauth", "internal").exists(p => req.pathInfo.renderString.startsWith("/" + p)) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
    }
    HttpRoutes[IO] { req =>
      val p  = req.pathInfo.renderString
      val cc = if p.startsWith("/assets/") then "public, max-age=31536000, immutable" else if p == "/rum.js" then "public, max-age=3600" else "no-cache"
      // /rum.js はほかのサイトから読み込まれる
      (files <+> index).run(req).map(r => r.putHeaders("Cache-Control" -> cc) |> (x => if p == "/rum.js" then x.putHeaders("Access-Control-Allow-Origin" -> "*", "Cross-Origin-Resource-Policy" -> "cross-origin") else x))
    }

  extension [A](a: A) private def |>[B](f: A => B): B = f(a)
