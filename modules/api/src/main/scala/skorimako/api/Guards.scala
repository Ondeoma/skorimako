package skorimako.api

import cats.data.OptionT
import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import org.http4s.server.middleware.EntityLimiter
import org.typelevel.ci.CIString
import sbirds.http.Errors
import sbirds.shared.AppError
import skorimako.core.ErrorCode

/**
 * すべての要求に共通の守り:
 *  - 本文の大きさの上限（取り込みは MAX_INGEST_MB、それ以外は 2MB）。大きすぎる本文でメモリを使い切らせない。
 *  - セキュリティのヘッダー（iframe の禁止・nosniff・Referrer・https なら HSTS）。
 *  - Cookie のセッションで状態を変える要求は、別のサイト（Origin が違う）からなら断る。
 */
object Guards {

  private val JsonLimit: Long = 2L * 1024 * 1024
  private val safe = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  /** 観測データの取り込み（OTLP・JSON・RUM）。 */
  def isIngest(path: String): Boolean =
    path.startsWith("/v1/") || path.startsWith("/rum/") || path.matches("^/api/ingest/[^/]+/_(json|multi)$") ||
      path == "/api/prom/push" || path == "/api/prom/api/v1/write"

  private def originOf(u: String): String = Uri.fromString(u).toOption.map(x =>
    s"${x.scheme.fold("")(_.value)}://${x.authority.fold("")(_.renderString)}"
  ).getOrElse(u)

  def apply(app: HttpRoutes[IO], cfg: ApiConfig): HttpRoutes[IO] = {
    val allowed = originOf(cfg.publicUrl).toLowerCase
    def foreign(req: Request[IO]): Boolean =
      !safe.contains(req.method) &&
        !isIngest(req.pathInfo.renderString) &&
        req.headers.get[Authorization].isEmpty &&
        req.cookies.exists(_.name == cfg.cookieName) &&
        req.headers.get(CIString("Origin")).map(_.head.value.trim.toLowerCase).exists { o =>
          // "null"（サンドボックスの iframe など）も別のサイトとして扱う
          o != allowed && !(cfg.devLogin && (o.startsWith("http://localhost:") || o.startsWith("http://127.0.0.1:")))
        }
    HttpRoutes[IO] { req =>
      val limit = if isIngest(req.pathInfo.renderString) then cfg.maxIngestBytes else JsonLimit
      if foreign(req) then
        OptionT.liftF(Errors.respond(AppError(ErrorCode.CrossSiteRequest), Some(req)))
      else if req.contentLength.exists(_ > limit) then
        OptionT.liftF(Errors.respond(AppError(ErrorCode.RequestTooLarge), Some(req)))
      else EntityLimiter.httpRoutes(app, limit).run(req)
    }.map(secure(_, cfg.publicUrl.startsWith("https://")))
  }

  /** すべての応答に付けるセキュリティのヘッダー（応答が自分で付けたものは残す）。iframe に入れさせない（クリックジャッキング）。 */
  private def secure(r: Response[IO], https: Boolean): Response[IO] = {
    val base = List(
      "X-Frame-Options" -> "DENY",
      "Content-Security-Policy" -> "frame-ancestors 'none'; base-uri 'self'; object-src 'none'",
      "X-Content-Type-Options" -> "nosniff",
      "Referrer-Policy" -> "same-origin"
    ) ++ (if https then List("Strict-Transport-Security" -> "max-age=31536000") else Nil)
    base.foldLeft(r) { case (acc, (k, v)) =>
      if acc.headers.get(CIString(k)).isDefined then acc else acc.putHeaders(k -> v)
    }
  }
}
