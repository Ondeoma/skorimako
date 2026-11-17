package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import skorimako.auth.{ApiKeys, Crypto}
import skorimako.core.*
import skorimako.db.*
import skorimako.shared.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.*

final case class ApiConfig(
    adminGroups: Set[String],
    cookieSecure: Boolean,
    publicUrl: String,
    sessionTtl: FiniteDuration = 14.days,
    cookieName: String = "skorimako_session",
    devLogin: Boolean = false,
    timezone: String = "Asia/Tokyo",
    retentionDays: Int = 30,
    flushSeconds: Int = 10,
    maxIngestBytes: Long = 32L * 1024 * 1024,     // 送られてくる本文（圧縮したまま）の上限
    maxInflatedBytes: Long = 256L * 1024 * 1024,  // 展開した後の上限
    maxIngestRows: Int = 100000,                  // JSON・RUM の1回の行数の上限
    webhookDenyPrivate: Boolean = false,
    version: String = "0.1.0",
    oauthClients: List[OAuthClient] = Nil,
    stui: Option[LinkService] = None,
    internalSecret: String = "",      // ノード間の問い合わせの署名（APP_SECRET から）
    grpcPort: Int = 0
)

/** 連携する stui（skorimako をクライアントとして登録しておく）。url は画面から開く URL、internalUrl はサーバーから呼ぶ URL。 */
final case class LinkService(provider: String, url: String, clientId: String, clientSecret: String, internalUrl: Option[String] = None):
  def apiUrl: String = internalUrl.getOrElse(url)

/** 認可を求める外部のサービス（OAUTH_CLIENTS で登録する。stui がリンクの要約を読むため）。redirectUri は完全一致で照合する。 */
final case class OAuthClient(id: String, secret: String, redirectUri: String, name: String, scopes: List[String] = List("read"))

extension (s: AuthSession)
  /** 検索の権限（管理者・グループ）。 */
  def viewer: skorimako.engine.Viewer = skorimako.engine.Viewer(s.isAdmin, s.groups.map(_.name).toSet)

/** 処理の失敗（HTTP の応答に変換する）。 */
enum Fail:
  case NotFound(msg: String)
  case Forbidden(msg: String)
  case Invalid(msg: String)
  case Conflict(msg: String)

  def message: String = this match
    case NotFound(m)  => m
    case Forbidden(m) => m
    case Invalid(m)   => m
    case Conflict(m)  => m

type Res[A] = IO[Either[Fail, A]]

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`（取り込みは Basic のパスワードにキーを入れてもよい）。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig):

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    key(req) match
      case Some(token) =>
        val mcp = req.pathInfo.renderString.startsWith("/mcp") || req.headers.get(org.typelevel.ci.CIString("X-Skorimako-Mcp")).isDefined
        fromKey(token, mcp)
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match
          case None        => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  /** APIキー（gRPC のメタデータなど、HTTP の要求でないところから）。 */
  def fromKey(token: String, mcp: Boolean = false): IO[Option[AuthSession]] =
    if !ApiKeys.looksLike(token) then IO.pure(None)
    else users.findKey(Crypto.sha256(token), Instant.now()).map(_.map { (u, gs, kid, scopes) =>
      AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, mcp))
    })

  /** 別のユーザーとしての権限（stui の DM の宛先など）。 */
  def sessionFor(userId: java.util.UUID): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  /** Bearer のキー、または Basic のパスワード（ユーザー名は何でもよい）のキー。 */
  private def key(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t
      case Authorization(Credentials.Token(AuthScheme.Basic, t)) =>
        scala.util.Try(new String(Base64.getDecoder.decode(t), StandardCharsets.UTF_8)).toOption.map(_.split(":", 2)).collect { case Array(_, p) => p }.getOrElse("")
    }.filter(ApiKeys.looksLike)

  def issue(user: User): IO[ResponseCookie] =
    val token = Crypto.randomToken()
    users.createSession(Crypto.sha256(token), user.id, Instant.now().plusSeconds(cfg.sessionTtl.toSeconds)).as(cookie(token, cfg.sessionTtl.toSeconds))

  def revoke(req: Request[IO]): IO[ResponseCookie] =
    req.cookies.find(_.name == cfg.cookieName) match
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).as(cookie("", 0))
      case None    => IO.pure(cookie("", 0))

  private def cookie(value: String, maxAge: Long): ResponseCookie =
    ResponseCookie(cfg.cookieName, value, maxAge = Some(maxAge), path = Some("/"), httpOnly = true, secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax))

/** *Routes に共通の補助（エラー応答・認証・リクエスト本文）。 */
trait RouteSupport extends Http4sDsl[IO]:
  protected def sessions: SessionAuth

  protected def err(status: Status, msg: String): IO[Response[IO]] =
    Response[IO](status).withEntity(ErrorDto(msg)).pure[IO]

  protected def fail(f: Fail): IO[Response[IO]] = f match
    case Fail.NotFound(m)  => err(Status.NotFound, m)
    case Fail.Forbidden(m) => err(Status.Forbidden, m)
    case Fail.Invalid(m)   => err(Status.BadRequest, m)
    case Fail.Conflict(m)  => err(Status.Conflict, m)

  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** 検索の結果（Left は 400）。 */
  protected def query[A: io.circe.Encoder](r: IO[Either[String, A]]): IO[Response[IO]] =
    r.flatMap(_.fold(e => err(Status.BadRequest, e), a => Ok(a)))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/query/sql", "/api/query/logs", "/api/query/metrics", "/api/traces/search", "/api/links/lookup", "/api/links/titles",
    "/api/prom/api/v1/query", "/api/prom/api/v1/query_range", "/api/prom/api/v1/series", "/api/prom/api/v1/labels")
  private def readOnlyPrefix(req: Request[IO]): Boolean =
    req.method == Method.POST && req.pathInfo.renderString.startsWith("/api/prom/api/v1/label/")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST && readOnlyPosts.contains(req.pathInfo.renderString)

  /** ログインしていなければ 401。取り込み専用のキーでは使えない。更新系の要求を read スコープのキーで送ったら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(Status.Unauthorized, "ログインしてください")
      case Some(s) if !s.canRead => err(Status.Forbidden, "このAPIキーは取り込み専用です（read スコープが必要です）")
      case Some(s) if !s.canWrite && !Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(req.method) && !readOnlyPost(req) && !readOnlyPrefix(req) =>
        err(Status.Forbidden, "このAPIキーは参照専用です（write スコープが必要です）")
      case Some(s) => f(s)
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(Status.Forbidden, "管理者だけが使えます"))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(Status.Forbidden, "この操作は画面からのみ行えます"))

  protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(e)  => err(Status.BadRequest, s"リクエストの内容が正しくありません: ${e.getMessage.take(200)}")
      case Right(b) => f(b)
    }

  protected object UuidVar:
    def unapply(s: String): Option[UUID] = uuidOpt(s)

  /** 同じサイトの中のパスか（ログインの後に戻る先。"//…"・"/\…" は別のサイトになるので除く）。 */
  protected def localPath(p: String): Boolean =
    p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\") && !p.exists(_.isControl)

  protected def param(req: Request[IO], k: String): Option[String] = req.params.get(k).map(_.trim).filter(_.nonEmpty)

  protected def intParam(req: Request[IO], k: String, default: Int, max: Int): Int =
    param(req, k).flatMap(_.toIntOption).map(v => math.max(1, math.min(v, max))).getOrElse(default)

  protected def longParam(req: Request[IO], k: String): Option[Long] = param(req, k).flatMap(_.toLongOption)

  /** 時間の範囲（start・end はマイクロ秒。省略時は直近の1時間）。 */
  protected def range(req: Request[IO]): (Long, Long) =
    val now = System.currentTimeMillis() * 1000
    val end = longParam(req, "end").getOrElse(now)
    (longParam(req, "start").getOrElse(end - 3600L * 1000000), end)

  protected def audit(users: UserStore, s: AuthSession, action: String, target: Option[String], detail: io.circe.Json = io.circe.Json.obj()): IO[Unit] =
    users.audit(Some(s.user.id), s.viaString, action, target, detail)
