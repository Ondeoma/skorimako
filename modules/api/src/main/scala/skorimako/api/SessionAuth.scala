package skorimako.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import sbirds.http.SessionCookie
import skorimako.auth.ApiKeys
import skorimako.core.*
import skorimako.db.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`（取り込みは Basic のパスワードにキーを入れてもよい）。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig) {

  private val cookie = new SessionCookie(cfg.cookieName, cfg.cookieSecure, cfg.sessionTtl)

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    key(req) match {
      case Some(token) =>
        // MCP 経由の印は、サーバーの中で付けた属性だけを信用する（外から付けられるヘッダーは見ない）
        val mcp = req.pathInfo.renderString.startsWith("/mcp") || req.attributes.contains(SessionAuth.McpCall)
        fromKey(token, mcp)
      case None =>
        cookie.hash(req) match {
          case None => IO.pure(None)
          case Some(hash) =>
            users.findSession(hash, Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))
        }
    }

  /** APIキー（gRPC のメタデータなど、HTTP の要求でないところから）。 */
  def fromKey(token: String, mcp: Boolean = false): IO[Option[AuthSession]] =
    if !ApiKeys.looksLike(token) then IO.pure(None)
    else
      users.findKey(Crypto.sha256(token), Instant.now()).map(_.map {
        (u, gs, kid, scopes) =>
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
        scala.util.Try(new String(
          Base64.getDecoder.decode(t),
          StandardCharsets.UTF_8
        )).toOption.map(_.split(":", 2)).collect { case Array(_, p) => p }.getOrElse("")
    }.filter(ApiKeys.looksLike)

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] =
    cookie.issue((hash, expires) => users.createSession(hash, user.id, expires, idToken))

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(req: Request[IO]): IO[Map[String, String]] = cookie.appNames(req)(users.sessionIdToken)

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] = cookie.revoke(req)(users.deleteSession)
}

object SessionAuth {

  /** MCP のツールが REST API を内部から呼んだ印（監査の経路を mcp にする）。要求の属性なので、外からは付けられない。 */
  val McpCall: org.typelevel.vault.Key[Unit] = org.typelevel.vault.Key.newKey[SyncIO, Unit].unsafeRunSync()
}
