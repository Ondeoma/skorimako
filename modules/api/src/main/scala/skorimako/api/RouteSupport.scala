package skorimako.api

import cats.effect.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import skorimako.core.*
import skorimako.db.*
import sbirds.http.Errors
import sbirds.shared.AppError

import java.util.UUID

/**
 * *Routes に共通の補助（エラー応答・認証・リクエスト本文）。
 */
trait RouteSupport extends Http4sDsl[IO] {
  protected def sessions: SessionAuth

  /** エラーの応答（コード付きでログに出る）. detail を省くとコードの既定の文言. */
  protected def err(
    code: ErrorCode,
    detail: String | Null = null
  ): IO[Response[IO]] = Errors.respond(AppError(code, Option(detail)))

  protected def fail(
    e: AppError
  ): IO[Response[IO]] = Errors.respond(e)

  protected def respond[A](
    r: Res[A]
  )(
    f: A => IO[Response[IO]]
  ): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](
    r: Res[A]
  ): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** 検索の結果（Left は 400）。 */
  protected def query[A: io.circe.Encoder](
    r: IO[Either[String, A]]
  ): IO[Response[IO]] =
    r.flatMap(_.fold(e => err(ErrorCode.QueryInvalid, e), a => Ok(a)))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set(
    "/api/query/sql",
    "/api/query/logs",
    "/api/query/metrics",
    "/api/traces/search",
    "/api/links/lookup",
    "/api/links/titles",
    "/api/prom/api/v1/query",
    "/api/prom/api/v1/query_range",
    "/api/prom/api/v1/series",
    "/api/prom/api/v1/labels"
  )
  private def readOnlyPrefix(
    req: Request[IO]
  ): Boolean =
    req.method == Method.POST && req.pathInfo.renderString.startsWith("/api/prom/api/v1/label/")
  private def readOnlyPost(
    req: Request[IO]
  ): Boolean = req.method == Method.POST && readOnlyPosts.contains(req.pathInfo.renderString)

  /** ログインしていなければ 401。取り込み専用のキーでは使えない。更新系の要求を read スコープのキーで送ったら 403。 */
  protected def authed(
    req: Request[IO]
  )(
    f: AuthSession => IO[Response[IO]]
  ): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(ErrorCode.NotLoggedIn)
      case Some(s) if !s.canRead => err(ErrorCode.ApiKeyIngestOnly)
      case Some(s)
          if !s.canWrite && !Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(req.method) && !readOnlyPost(req) &&
            !readOnlyPrefix(req) =>
        err(ErrorCode.ApiKeyNeedsWrite)
      case Some(s) => f(s)
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(
    req: Request[IO]
  )(
    f: AuthSession => IO[Response[IO]]
  ): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(ErrorCode.AdminOnly))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(
    req: Request[IO]
  )(
    f: AuthSession => IO[Response[IO]]
  ): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(ErrorCode.SessionOnly))

  protected def body[A: Decoder](
    req: Request[IO]
  )(
    f: A => IO[Response[IO]]
  ): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(e) => err(ErrorCode.RequestBodyInvalid, s"リクエストの内容が正しくありません: ${e.getMessage.take(200)}")
      case Right(b) => f(b)
    }

  protected object UuidVar {
    def unapply(
      s: String
    ): Option[UUID] = uuidOpt(s)
  }

  /** 同じサイトの中のパスか（ログインの後に戻る先。"//…"・"/\…" は別のサイトになるので除く）。 */
  protected def localPath(
    p: String
  ): Boolean =
    p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\") && !p.exists(_.isControl)

  protected def param(
    req: Request[IO],
    k: String
  ): Option[String] = req.params.get(k).map(_.trim).filter(_.nonEmpty)

  protected def intParam(
    req: Request[IO],
    k: String,
    default: Int,
    max: Int
  ): Int =
    param(req, k).flatMap(_.toIntOption).map(v => math.max(1, math.min(v, max))).getOrElse(default)

  protected def longParam(
    req: Request[IO],
    k: String
  ): Option[Long] = param(req, k).flatMap(_.toLongOption)

  /** 時間の範囲（start・end はマイクロ秒。省略時は直近の1時間）。 */
  protected def range(
    req: Request[IO]
  ): (Long, Long) = {
    val now = System.currentTimeMillis() * 1000
    val end = longParam(req, "end").getOrElse(now)
    (longParam(req, "start").getOrElse(end - 3600L * 1000000), end)
  }

  protected def audit(
    users: UserStore,
    s: AuthSession,
    action: String,
    target: Option[String],
    detail: io.circe.Json = io.circe.Json.obj()
  ): IO[Unit] =
    users.audit(Some(s.user.id), s.viaString, action, target, detail)
}
