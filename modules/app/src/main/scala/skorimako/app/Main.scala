package skorimako.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.*
import skorimako.api.*
import sbirds.auth.{OidcClient, OidcConfig}
import skorimako.auth.SecretBox
import skorimako.db.*
import skorimako.engine.*
import skorimako.core.ErrorCode
import sbirds.http.Errors
import sbirds.shared.AppError
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
  selfMonitor: Boolean)

object Main extends IOApp.Simple {

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for {
      _ <- IO(java.security.Security.setProperty("networkaddress.cache.ttl", "30"))
      cfg <- Config.load
      n <- migrateWithRetry(cfg)
      _ <- sbirds.observe.Log.println(s"[skorimako] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for {
        xa <- Database.transactor(cfg.db)
        http <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build
        users = new UserStore(xa)
        streams = new StreamStore(xa)
        alerts = new AlertStore(xa)
        apps = new AppStore(xa)
        lock = new JobLock(xa, cfg.nodeId)
        box = SecretBox(cfg.appSecret)
        oidc <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        objects <- objectStore(cfg)
        stats = new Stats
        acl <- Resource.eval(Acl.create(streams))
        transform <- Resource.eval(Transform.create(streams))
        engine <- Resource.eval(QueryEngine.create(streams, objects, cfg.duck, cfg.query, acl, stats))
        // 止めるときは、たまっている行を書き出してから
        ingester <- Resource.make(Ingester.create(cfg.ingest, cfg.duck, streams, objects, stats))(_.shutdown)
        ingest = new IngestService(ingester, transform)
        peers = new PeerRoutes(ingester, streams, http, cfg.nodeId, cfg.nodeUrl, cfg.api.internalSecret)
        _ <- Resource.eval(engine.setLive(peers.live))
        _ <- peers.heartbeat
        maint = new Maintenance(
          streams,
          objects,
          engine,
          cfg.duck,
          cfg.maintenance,
          lock,
          cfg.nodeId,
          cfg.dataDir.resolve("tmp")
        )
        sess = new SessionAuth(users, cfg.api)
        stuiCli = new StuiClient(cfg.api.stui, new sbirds.links.IntegrationTokenStore(xa), http, box, cfg.api.publicUrl)
        pushStore = new sbirds.push.PushStore(xa)
        push <- Resource.eval(PushService.create(
          pushStore,
          http,
          cfg.api.publicUrl,
          r => IO(stats.inc("skorimako_push_sent_total", Map("result" -> r)))
        ))
        alertSvc = new AlertService(alerts, engine, http, box, cfg.api, stuiCli, stats, push)
        selfMon = new SelfMonitor(ingest, stats, apps, streams, lock, cfg.nodeId)
        _ <- Resource.eval(startupNotes(cfg))
        _ <- Resource.eval(IO.whenA(cfg.selfMonitor)(selfMon.ensureDashboard.handleErrorWith(e =>
          sbirds.observe.Log.report(ErrorCode.SelfDashboardFailed, e.toString, e)
        )))
        loops = List[IO[Unit]](
          ingester.loop,
          alertSvc.loop,
          alertSvc.deliveryLoop,
          maint.compactLoop,
          maint.retentionLoop,
          maint.cacheLoop,
          purgeLoop(users, alerts, lock),
          transform.loop
        ) ++ Option.when(cfg.selfMonitor)(selfMon.loop).toList
        _ <- loops.traverse_(_.background)
        ingestRoutes = new IngestRoutes(ingest, apps, sess, cfg.api)
        _ <- if cfg.api.grpcPort > 0 then
          GrpcIngest.resource(cfg.api.grpcPort, ingest, sess, cfg.api.maxIngestBytes.toInt)
        else Resource.unit[IO]
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
            new PushRoutes(pushStore, push, sess).routes <+>
            peers.routes
        app = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        all = cfg.staticDir.fold(app)(d => app <+> spa(d))
        _ <- EmberServerBuilder.default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
          .withShutdownTimeout(5.seconds)
          .withMaxHeaderSize(64 * 1024)
          .withHttpApp(Errors.handle(tooLarge(Guards(all, cfg.api).orNotFound), ErrorCode.Unexpected))
          .build
        _ <- Resource.eval(sbirds.observe.Log.println(s"[skorimako] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      } yield ()).useForever
    } yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(
    cfg: AppConfig,
    attempt: Int = 1
  ): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else
        sbirds.observe.Log.println(s"[skorimako] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *>
          IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  private def objectStore(
    cfg: AppConfig
  ): Resource[IO, ObjectStore] = cfg.s3 match {
    case Some(c) => S3ObjectStore.resource(c).evalTap(_.ensureBucket).widen[ObjectStore]
    case None =>
      val dir = Paths.get(cfg.storageDir.getOrElse("storage")).toAbsolutePath.normalize()
      Resource.eval(sbirds.observe.Log.println(
        s"[skorimako] WARNING: S3_ENDPOINT が未設定のため、データを $dir に置きます（1台だけの構成。冗長化には S3 互換ストレージが必要です）"
      )
        .as(new LocalObjectStore(dir)))
  }

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(
    xa: doobie.Transactor[IO],
    node: String
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true) => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e) => text(Status.ServiceUnavailable, s"database unavailable: ${e.getMessage}")
      }
  }

  private def text(
    status: Status,
    body: String
  ): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def startupNotes(
    cfg: AppConfig
  ): IO[Unit] =
    IO.whenA(
      cfg.oidc.isEmpty
    )(sbirds.observe.Log.println("[skorimako] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  /** 本文の上限（Guards の EntityLimiter）を超えたら 413. */
  private def tooLarge(
    app: HttpApp[IO]
  ): HttpApp[IO] = HttpApp[IO](req =>
    app.run(req).adaptError { case _: org.http4s.server.middleware.EntityLimiter.EntityTooLarge =>
      AppError(ErrorCode.RequestTooLarge)
    }
  )

  /** 期限切れのセッション・古い監査ログ・アラートの履歴と送信の記録を消す（1ノードだけ）。 */
  private def purgeLoop(
    users: UserStore,
    alerts: AlertStore,
    lock: JobLock
  ): IO[Unit] =
    Periodic.run(10.minutes, 1.hour, ErrorCode.PurgeFailed)(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> users.purgeAudit(730).void *> alerts.purgeEvents(180).void *>
        alerts.purgeDeliveries(30).void
    ))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。 */
  private def spa(
    dir: String
  ): HttpRoutes[IO] = {
    val files = fileService[IO](FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _
          if !Set("api", "auth", "mcp", "v1", "rum/", "oauth", "internal").exists(p =>
            req.pathInfo.renderString.startsWith("/" + p)
          ) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(Errors.respond(ErrorCode.PageNotFound))
    }
    HttpRoutes[IO] { req =>
      val p = req.pathInfo.renderString
      val cc = if p.startsWith("/assets/") then "public, max-age=31536000, immutable"
      else if p == "/rum.js" then "public, max-age=3600" else "no-cache"
      // /rum.js はほかのサイトから読み込まれる
      (files <+> index).run(req).map(r =>
        r.putHeaders("Cache-Control" -> cc) |>
          (x =>
            if p == "/rum.js" then
              x.putHeaders("Access-Control-Allow-Origin" -> "*", "Cross-Origin-Resource-Policy" -> "cross-origin")
            else x
          )
      )
    }
  }

  extension [A](
    a: A
  ) {
    private def |>[B](
      f: A => B
    ): B = f(a)
  }
}
