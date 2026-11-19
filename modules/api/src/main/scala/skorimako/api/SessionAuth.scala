package skorimako.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import skorimako.auth.ApiKeys
import skorimako.core.*
import skorimako.db.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`（取り込みは Basic のパスワードにキーを入れてもよい）。 */
final class SessionAuth(
  users: UserStore,
  cfg: ApiConfig) {

  private def isAdmin(
    u: User,
    gs: List[Group]
  ) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(
    req: Request[IO]
  ): IO[Option[AuthSession]] =
    key(req) match {
      case Some(token) =>
        val mcp = req.pathInfo.renderString.startsWith("/mcp") ||
          req.headers.get(org.typelevel.ci.CIString("X-Skorimako-Mcp")).isDefined
        fromKey(token, mcp)
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match {
          case None => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map(
              (
                u,
                gs
              ) => AuthSession(u, gs, isAdmin(u, gs))
            ))
        }
    }

  /** APIキー（gRPC のメタデータなど、HTTP の要求でないところから）。 */
  def fromKey(
    token: String,
    mcp: Boolean = false
  ): IO[Option[AuthSession]] =
    if !ApiKeys.looksLike(token) then IO.pure(None)
    else
      users.findKey(Crypto.sha256(token), Instant.now()).map(_.map {
        (
          u,
          gs,
          kid,
          scopes
        ) =>
          AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, mcp))
      })

  /** 別のユーザーとしての権限（stui の DM の宛先など）。 */
  def sessionFor(
    userId: java.util.UUID
  ): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map(
      (
        u,
        gs
      ) => AuthSession(u, gs, isAdmin(u, gs))
    ))

  /** Bearer のキー、または Basic のパスワード（ユーザー名は何でもよい）のキー。 */
  private def key(
    req: Request[IO]
  ): Option[String] =
    req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t
      case Authorization(Credentials.Token(AuthScheme.Basic, t)) =>
        scala.util.Try(new String(
          Base64.getDecoder.decode(t),
          StandardCharsets.UTF_8
        )).toOption.map(_.split(":", 2)).collect { case Array(_, p) => p }.getOrElse("")
    }.filter(ApiKeys.looksLike)

  def issue(
    user: User,
    idToken: Option[String] = None
  ): IO[ResponseCookie] = {
    val token = Crypto.randomToken()
    users.createSession(
      Crypto.sha256(token),
      user.id,
      Instant.now().plusSeconds(cfg.sessionTtl.toSeconds),
      idToken
    ).as(cookie(token, cfg.sessionTtl.toSeconds))
  }

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(
    req: Request[IO]
  ): IO[Map[String, String]] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) =>
        users.sessionIdToken(Crypto.sha256(c.content)).map(_.fold(Map.empty)(sbirds.auth.AppNames.fromIdToken))
      case None => IO.pure(Map.empty)
    }

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(
    req: Request[IO]
  ): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None => IO.pure(None -> cookie("", 0))
    }

  private def cookie(
    value: String,
    maxAge: Long
  ): ResponseCookie =
    ResponseCookie(
      cfg.cookieName,
      value,
      maxAge = Some(maxAge),
      path = Some("/"),
      httpOnly = true,
      secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax)
    )
}
