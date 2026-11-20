package skorimako.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.{LoginResult, OidcClient}
import sbirds.http.{Errors, OidcLoginFlow}
import skorimako.core.ErrorCode
import skorimako.db.*
import skorimako.shared.*

/** ログイン（OIDC・開発用）・ログアウト・画面の設定。 */
final class AuthRoutes(oidc: Option[OidcClient], users: UserStore, val sessions: SessionAuth, cfg: ApiConfig)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "auth" / "login" => getAuthLogin(req)
    case req @ GET -> Root / "auth" / "callback" => getAuthCallback(req)
    case req @ POST -> Root / "auth" / "logout" => postAuthLogout(req)
    case req @ POST -> Root / "auth" / "dev-login" if cfg.devLogin => postAuthDevLogin(req)
    case GET -> Root / "api" / "client-config" => getClientConfig()
  }

  private def getAuthLogin(req: Request[IO]): IO[Response[IO]] = OidcLoginFlow.login(flow)(req)

  private def getAuthCallback(req: Request[IO]): IO[Response[IO]] = OidcLoginFlow.callback(flow)(req)

  /** OIDC のログインの流れ（sbirds.http.OidcLoginFlow）に差し込む、skorimako の部分。 */
  private val flow = new OidcLoginFlow.Host[LoginResult] {
    val idp = oidc.map(OidcLoginFlow.Idp(_))
    // state をこのブラウザの Cookie にも入れ、戻ってきたときに照合する（他人のログインを踏ませる攻撃を防ぐ）
    val cookie = OidcLoginFlow.StateCookie(
      "skorimako_login_state",
      cfg.cookieSecure,
      maxAge = 900,
      clear = OidcLoginFlow.Clear.Never,
      checkWithoutCode = true
    )

    def returnTo(req: Request[IO]): String = safeReturn(param(req, "returnTo"))
    def saveState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit] =
      users.saveLoginState(state, verifier, nonce, returnTo)
    def takeState(state: String): IO[Option[(String, String, String)]] = users.takeLoginState(state)
    def redirect(to: String): IO[Response[IO]] = AuthRoutes.this.redirect(to)

    def withoutIdp(req: Request[IO], back: String): IO[Response[IO]] =
      if cfg.devLogin then AuthRoutes.this.redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back, "UTF-8")}")
      else err(ErrorCode.OidcNotConfigured)

    def failed(req: Request[IO], f: OidcLoginFlow.Failure): IO[Response[IO]] = {
      import OidcLoginFlow.Failure.*
      def denied(e: Throwable) = users.audit(None, "web", "auth.denied", None, Json.obj("error" -> e.getMessage.asJson))
      f match {
        case NotConfigured => err(ErrorCode.OidcNotConfigured)
        case ParamsMissing => err(ErrorCode.LoginCodeStateRequired)
        case BrowserMismatch | Expired => err(ErrorCode.LoginExpired)
        // 利用者に見せてよい理由（IdP・設定が断った）だけを返す。ほかの失敗の詳細はログと監査にだけ残す
        case Denied(e) => denied(e) *> err(ErrorCode.LoginFailed, s"ログインできませんでした: ${e.getMessage}")
        case Failed(e) =>
          denied(e) *> Errors.respondLogged(ErrorCode.LoginFailed, String.valueOf(e.getMessage), Some(req), Some(e))
        case IdpError(e, _) => Errors.respondLogged(ErrorCode.LoginFailed, e.take(100), Some(req))
      }
    }

    def loggedIn(req: Request[IO], res: LoginResult, back: String): IO[Response[IO]] =
      for {
        u <- users.upsert(res.identity.sub, res.identity.name, res.identity.email, res.identity.picture)
        r <- if u.disabled then err(ErrorCode.UserDisabled)
        else
          users.syncGroups(u.id, res.identity.groups) *>
            AuthRoutes.this.loggedIn(u, "web", Some(res.idToken)).flatMap(c => AuthRoutes.this.redirect(back, Some(c)))
      } yield r
  }

  private def postAuthLogout(req: Request[IO]): IO[Response[IO]] =
    sessions.revoke(req).flatMap {
      (idToken, c) =>
        OidcLoginFlow.logoutRedirect(oidc, idToken, s"${cfg.publicUrl.stripSuffix("/")}/").flatMap { to =>
          Ok(Json.obj("redirect" -> to.asJson)).map(_.addCookie(c))
        }
    }

  private def postAuthDevLogin(req: Request[IO]): IO[Response[IO]] =
    body[Json](req) { j =>
      val c = j.hcursor
      val name = c.get[String]("name").getOrElse("").trim
      val admin = c.get[Boolean]("admin").getOrElse(false)
      if name.isEmpty then err(ErrorCode.NameRequired)
      else
        for {
          u0 <- users.upsert(s"dev:$name", name, c.get[String]("email").toOption.filter(_.contains("@")), None)
          _ <- users.update(u0.id, Option.when(admin)(true), None)
          u <- users.find(u0.id).map(_.getOrElse(u0))
          ck <- loggedIn(u, "web")
          r <- Ok(Json.obj("redirect" -> safeReturn(c.get[String]("returnTo").toOption).asJson)).map(_.addCookie(ck))
        } yield r
    }

  private def getClientConfig(): IO[Response[IO]] =
    Ok(ClientConfigDto(
      cfg.devLogin && oidc.isEmpty,
      cfg.version,
      cfg.publicUrl,
      cfg.flushSeconds,
      cfg.stui.isDefined,
      cfg.grpcPort
    ))

  private def safeReturn(s: Option[String]): String = sbirds.shared.SafePath.orElse(s)

  private def loggedIn(u: skorimako.core.User, via: String, idToken: Option[String] = None): IO[ResponseCookie] =
    sessions.issue(u, idToken).flatTap(_ => users.audit(Some(u.id), via, "auth.login", None, Json.obj()))

}
