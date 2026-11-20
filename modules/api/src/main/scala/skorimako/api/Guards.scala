package skorimako.api

import cats.effect.*
import org.http4s.*
import sbirds.http.{BodyLimit, Csrf, SecurityHeaders}
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

  /** 観測データの取り込み（OTLP・JSON・RUM）。 */
  def isIngest(path: String): Boolean =
    path.startsWith("/v1/") || path.startsWith("/rum/") || path.matches("^/api/ingest/[^/]+/_(json|multi)$") ||
      path == "/api/prom/push" || path == "/api/prom/api/v1/write"

  def apply(app: HttpRoutes[IO], cfg: ApiConfig): HttpRoutes[IO] = {
    // Origin（なければ Referer）と Sec-Fetch-Site を確かめる. "null"（サンドボックスの iframe など）も別のサイトとして扱う
    val csrf = Csrf.Policy(cfg.publicUrl, devLocalhost = cfg.devLogin).copy(safeMethods = Csrf.SafeWithOptions)
    // 本文を読む途中で上限を超えたとき（Content-Length がない要求など）も 413 にする
    val limited = BodyLimit(
      req => Some(if isIngest(req.pathInfo.renderString) then cfg.maxIngestBytes else JsonLimit),
      (_, _) => AppError(ErrorCode.RequestTooLarge)
    )(app)
    // すべての応答に付けるセキュリティのヘッダー（応答が自分で付けたものは残す）。iframe に入れさせない（クリックジャッキング）。
    val headers = SecurityHeaders.Policy(
      hsts = SecurityHeaders.hsts(cfg.publicUrl.startsWith("https://")),
      referrer = Some("same-origin"),
      csp = Some(SecurityHeaders.FrameCsp),
      keepExisting = true
    )
    SecurityHeaders(headers)(
      Csrf.routes(
        csrf,
        Csrf.cookieAuth(cfg.cookieName),
        ErrorCode.CrossSiteRequest,
        skip = req => isIngest(req.pathInfo.renderString)
      )(limited)
    )
  }
}
