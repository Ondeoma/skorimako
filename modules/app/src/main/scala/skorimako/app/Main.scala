package skorimako.app

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import skorimako.api.*
import sbirds.auth.{OidcClient, OidcConfig}
import skorimako.auth.SecretBox
import skorimako.db.*
import skorimako.engine.*
import skorimako.core.ErrorCode
import sbirds.http.{Errors, Health, Spa}
import sbirds.net.NetGuard
import skorimako.storage.{LocalObjectStore, ObjectStore, S3Config, S3ObjectStore}

import java.nio.file.Paths
import scala.concurrent.duration.*

final case class AppConfig(port: Int,
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
        // skorimako は観測の受け口なので、SQL ごとのトレースは残さない
        xa <- Database.transactor(cfg.db, telemetry = false)
        http <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build
        // 利用者が決めた宛先（アラートの通知先）へ送るクライアント. WEBHOOK_DENY_PRIVATE=true なら、確かめた IP にそのままつなぐ
        hookHttp <- NetGuard.client(NetGuard.Policy.of(!cfg.api.webhookDenyPrivate))
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
        alertSvc = new AlertService(alerts, engine, hookHttp, box, cfg.api, stuiCli, stats, push)
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
          maint.purgeLoop,
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
          Health.routes(Database.isPrimary(xa), s"ok ${cfg.nodeId}", errorDetail = true) <+>
            new AuthRoutes(oidc, users, sess, cfg.api).routes <+>
            new UserRoutes(users, sess).routes <+>
            ingestRoutes.routes <+>
            new PromRoutes(ingest, engine, streams, sess, cfg.api).routes <+>
            new DataRoutes(streams, alerts, engine, ingester, transform, objects, users, sess, cfg.api).routes <+>
            new AlertRoutes(alerts, alertSvc, users, box, sess, acl).routes <+>
            new AppRoutes(apps, streams, users, sess).routes <+>
            new OAuthRoutes(new sbirds.oauth.OAuthKeyStore(xa, revokeOnReuse = true), users, sess, cfg.api).routes <+>
            stuiCli.routes(sess, users) <+>
            new LinkRoutes(streams, alertSvc.access, apps, acl, sess, cfg.api).routes <+>
            new PushRoutes(pushStore, push.publicKey, sess).routes <+>
            peers.routes
        app = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        all = cfg.staticDir.fold(app)(d => app <+> spa(d))
        _ <- EmberServerBuilder.default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
          .withShutdownTimeout(5.seconds)
          .withMaxHeaderSize(64 * 1024)
          .withHttpApp(Errors.handle(
            Guards(RateLimit(all, cfg.api.rateLimit), cfg.api).orNotFound,
            ErrorCode.Unexpected
          ))
          .build
        _ <- Resource.eval(sbirds.observe.Log.println(s"[skorimako] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      } yield ()).useForever
    } yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(cfg: AppConfig, attempt: Int = 1): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else
        sbirds.observe.Log.println(s"[skorimako] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *>
          IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  private def objectStore(cfg: AppConfig): Resource[IO, ObjectStore] = cfg.s3 match {
    case Some(c) => S3ObjectStore.resource(c).evalTap(_.ensureBucket).widen[ObjectStore]
    case None =>
      val dir = Paths.get(cfg.storageDir.getOrElse("storage")).toAbsolutePath.normalize()
      Resource.eval(sbirds.observe.Log.println(
        s"[skorimako] WARNING: S3_ENDPOINT が未設定のため、データを $dir に置きます（1台だけの構成。冗長化には S3 互換ストレージが必要です）"
      )
        .as(new LocalObjectStore(dir)))
  }

  private def startupNotes(cfg: AppConfig): IO[Unit] =
    IO.whenA(
      cfg.oidc.isEmpty
    )(sbirds.observe.Log.println("[skorimako] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  /** 期限切れのセッション・古い監査ログ・アラートの履歴と送信の記録を消す（1ノードだけ）。 */
  private def purgeLoop(users: UserStore, alerts: AlertStore, lock: JobLock): IO[Unit] =
    Periodic.run(10.minutes, 1.hour, ErrorCode.PurgeFailed)(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> users.purgeAudit(730).void *> alerts.purgeEvents(180).void *>
        alerts.purgeDeliveries(30).void
    ))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。/rum.js はほかのサイトから読み込まれる。 */
  private def spa(dir: String): HttpRoutes[IO] =
    Spa.routes(
      dir,
      Spa.startsWith("api", "auth", "mcp", "v1", "rum/", "oauth", "internal"),
      Errors.respond(ErrorCode.PageNotFound),
      (req, r) =>
        if req.pathInfo.renderString == "/rum.js" then
          r.putHeaders(
            "Cache-Control" -> "public, max-age=3600",
            "Access-Control-Allow-Origin" -> "*",
            "Cross-Origin-Resource-Policy" -> "cross-origin"
          )
        else r
    )
}
