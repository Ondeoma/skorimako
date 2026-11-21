package skorimako.app

import sbirds.http.Env.*
import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import skorimako.api.*
import sbirds.auth.OidcConfig
import sbirds.mail.SmtpConfig
import skorimako.engine.*
import skorimako.storage.S3Config

import java.nio.file.Paths
import scala.concurrent.duration.*

/** 設定は環境変数から読む。 */
object Config {

  def load: IO[AppConfig] = IO.fromEither(
    (for {
      dbUrl <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match {
        case (Some(_), Some(_), Some(_)) if devLogin =>
          Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
        case (Some(i), Some(c), Some(s)) =>
          Right(Some(OidcConfig(
            i,
            c,
            s,
            s"${pub.stripSuffix("/")}/auth/callback",
            opt("OIDC_GROUPS_CLAIM").getOrElse("groups")
          )))
        case (None, None, None) if devLogin => Right(None)
        case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      }
      secret <- opt("APP_SECRET") match {
        case Some(s) if s.length >= 16 => Right(s)
        case Some(_) => Left("APP_SECRET は16文字以上にしてください")
        case None if devLogin => Right("skorimako-development-secret")
        case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      }
      _ <- Either.cond(
        opt("S3_ENDPOINT").isDefined || opt("STORAGE_DIR").isDefined,
        (),
        "S3_ENDPOINT（S3 互換ストレージ）か STORAGE_DIR（1台だけで動かすときのローカルのディレクトリ）を設定してください"
      )
      clients <- sbirds.oauth.OAuthClient.fromEnv()
      // RATE_LIMIT_AUTH・RATE_LIMIT_PUBLIC（IP ごとの1分あたりの回数. 0 で制限しない）・TRUSTED_PROXIES
      rate <- sbirds.http.RateLimitConfig.fromEnv()
    } yield {
      val base = pub.stripSuffix("/")
      val dataDir = Paths.get(opt("DATA_DIR").getOrElse("data")).toAbsolutePath.normalize()
      val nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" +
        java.util.UUID.randomUUID().toString.take(8))
      val flush = int("FLUSH_SECONDS").filter(_ > 0).getOrElse(10)
      val retention = int("RETENTION_DAYS").filter(_ > 0).getOrElse(30)
      val duck =
        DuckConfig(opt("DUCKDB_MEMORY").getOrElse("2GB"), int("DUCKDB_THREADS").getOrElse(4), dataDir.resolve("duck"))
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
          rateLimit = rate,
          // STUI_URL・STUI_CLIENT_ID・STUI_CLIENT_SECRET（STUI_INTERNAL_URL はサーバーから呼ぶ URL）
          stui = (opt("STUI_URL"), opt("STUI_CLIENT_ID"), opt("STUI_CLIENT_SECRET")).mapN((u, id, sec) =>
            LinkService("stui", u.stripSuffix("/"), id, sec, opt("STUI_INTERNAL_URL").map(_.stripSuffix("/")))
          ),
          internalSecret = secret,
          grpcPort = int("GRPC_PORT").getOrElse(4317)
        ),
        staticDir = opt("STATIC_DIR"),
        s3 = opt("S3_ENDPOINT").map(ep =>
          S3Config(
            ep,
            opt("S3_REGION").getOrElse("us-east-1"),
            opt("S3_BUCKET").getOrElse("skorimako"),
            opt("S3_ACCESS_KEY").getOrElse(""),
            opt("S3_SECRET_KEY").getOrElse(""),
            !opt("S3_PATH_STYLE").contains("false"),
            opt("S3_PREFIX").getOrElse("")
          )
        ),
        storageDir = opt("STORAGE_DIR"),
        dataDir = dataDir,
        // アラートのメールの通知（SMTP）. 送り先を skereru にすれば skereru から送る
        smtp = opt("SMTP_HOST").map { h =>
          val tls = opt("SMTP_TLS").getOrElse("starttls").toLowerCase
          SmtpConfig(
            h,
            int("SMTP_PORT").getOrElse(if tls == "ssl" then 465 else if tls == "none" then 25 else 587),
            opt("SMTP_USER"),
            opt("SMTP_PASSWORD"),
            tls,
            opt("MAIL_FROM").getOrElse(s"skorimako@$h")
          )
        },
        appSecret = secret,
        nodeId = nodeId,
        ingest = IngestConfig(
          dataDir,
          nodeId,
          flush.seconds,
          int("FLUSH_ROWS").getOrElse(200000),
          int("MAX_BUFFERED_ROWS").getOrElse(2000000).toLong,
          enabled("WAL_SYNC")
        ),
        query = QueryConfig(
          dataDir.resolve("cache"),
          int("CACHE_MAX_GB").getOrElse(10).toLong << 30,
          int("QUERY_TIMEOUT_SECONDS").getOrElse(60).seconds,
          int("QUERY_CONCURRENCY").getOrElse(4),
          int("QUERY_PER_USER").getOrElse(2)
        ),
        duck = duck,
        maintenance =
          MaintenanceConfig(retention, compactParallel = int("COMPACT_PARALLEL").filter(_ > 0).getOrElse(3)),
        nodeUrl = opt("NODE_URL").map(_.stripSuffix("/")),
        selfMonitor = !opt("SELF_MONITOR").contains("false")
      )
    }).left.map(new IllegalArgumentException(_))
  )
}
