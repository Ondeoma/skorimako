package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Location
import skorimako.auth.{ApiKeys, Crypto, OidcClient}
import skorimako.db.*
import skorimako.shared.*

import java.time.Instant

/** ログイン（OIDC・開発用）・ログアウト・画面の設定。 */
final class AuthRoutes(oidc: Option[OidcClient], users: UserStore, val sessions: SessionAuth, cfg: ApiConfig) extends RouteSupport:

  private val LoginCookie = "skorimako_login_state"

  private def safeReturn(s: Option[String]): String = s.filter(localPath).getOrElse("/")

  private def redirect(to: String, cookie: Option[ResponseCookie] = None): IO[Response[IO]] =
    val r = Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to)))
    IO.pure(cookie.fold(r)(r.addCookie))

  private def loggedIn(u: skorimako.core.User, via: String, idToken: Option[String] = None): IO[ResponseCookie] =
    sessions.issue(u, idToken).flatTap(_ => users.audit(Some(u.id), via, "auth.login", None, Json.obj()))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "auth" / "login" =>
      val back = safeReturn(param(req, "returnTo"))
      oidc match
        case Some(o) =>
          val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48); val nonce = Crypto.randomToken()
          // state をこのブラウザの Cookie にも入れ、戻ってきたときに照合する（他人のログインを踏ませる攻撃を防ぐ）
          users.saveLoginState(state, verifier, nonce, back) *> o.authorizationUri(state, verifier, nonce).flatMap(u =>
            redirect(u.renderString, Some(ResponseCookie(LoginCookie, state, path = Some("/auth"), httpOnly = true, secure = cfg.cookieSecure,
              sameSite = Some(SameSite.Lax), maxAge = Some(900)))))
        case None if cfg.devLogin => redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back, "UTF-8")}")
        case None                 => err(Status.ServiceUnavailable, "OIDC が設定されていません")

    case req @ GET -> Root / "auth" / "callback" =>
      (oidc, param(req, "code"), param(req, "state")) match
        case (Some(_), _, Some(state)) if !req.cookies.find(_.name == LoginCookie).exists(c => Crypto.constantTimeEquals(c.content, state)) =>
          err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
        case (Some(o), Some(code), Some(state)) =>
          users.takeLoginState(state).flatMap {
            case None => err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
            case Some((verifier, nonce, back)) =>
              o.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
                case Left(e) =>
                  users.audit(None, "web", "auth.denied", None, Json.obj("error" -> e.getMessage.asJson)) *>
                    err(Status.Unauthorized, s"ログインできませんでした: ${e.getMessage}")
                case Right(id) =>
                  for
                    u <- users.upsert(id.sub, id.name, id.email, id.picture)
                    r <- if u.disabled then err(Status.Forbidden, "このユーザーは無効にされています")
                         else users.syncGroups(u.id, id.groups) *> loggedIn(u, "web", id.idToken).flatMap(c => redirect(back, Some(c)))
                  yield r
              }
          }
        case (None, _, _) => err(Status.ServiceUnavailable, "OIDC が設定されていません")
        case _            => err(Status.BadRequest, "code と state が必要です")

    // ログアウト: セッションを消し、IdP にもログアウトを頼む（IdP のログインが残っていると、すぐにログインし直してしまうため）
    case req @ POST -> Root / "auth" / "logout" =>
      sessions.revoke(req).flatMap { (idToken, c) =>
        oidc.flatTraverse(_.logoutUri(idToken, s"${cfg.publicUrl.stripSuffix("/")}/")).flatMap { idp =>
          Ok(Json.obj("redirect" -> idp.fold("/")(_.renderString).asJson)).map(_.addCookie(c))
        }
      }

    // 開発用: 名前だけでログインする（DEV_LOGIN=true のときだけ）
    case req @ POST -> Root / "auth" / "dev-login" if cfg.devLogin =>
      body[Json](req) { j =>
        val c     = j.hcursor
        val name  = c.get[String]("name").getOrElse("").trim
        val admin = c.get[Boolean]("admin").getOrElse(false)
        if name.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else
          for
            u0 <- users.upsert(s"dev:$name", name, c.get[String]("email").toOption.filter(_.contains("@")), None)
            _  <- users.update(u0.id, Option.when(admin)(true), None)
            u  <- users.find(u0.id).map(_.getOrElse(u0))
            ck <- loggedIn(u, "web")
            r  <- Ok(Json.obj("redirect" -> safeReturn(c.get[String]("returnTo").toOption).asJson)).map(_.addCookie(ck))
          yield r
      }

    case GET -> Root / "api" / "client-config" =>
      Ok(ClientConfigDto(cfg.devLogin && oidc.isEmpty, cfg.version, cfg.publicUrl, cfg.flushSeconds, cfg.stui.isDefined, cfg.grpcPort))
  }

/** 自分の情報・APIキー、管理画面（ユーザー・監査ログ）。 */
final class UserRoutes(users: UserStore, val sessions: SessionAuth) extends RouteSupport:

  private def me(s: skorimako.core.AuthSession): MeDto =
    MeDto(s.user.id, s.user.name, s.user.email, s.user.avatarUrl, s.isAdmin, s.user.timezone)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => authed(req)(s => Ok(me(s)))

    case req @ PATCH -> Root / "api" / "me" =>
      authed(req)(s => body[UpdateMeReq](req) { r =>
        val tz = r.timezone.map(_.trim).filter(_.nonEmpty)
        if tz.exists(z => scala.util.Try(java.time.ZoneId.of(z)).isFailure) then err(Status.BadRequest, "タイムゾーンが正しくありません")
        else users.updateMe(s.user.id, r.name, tz) *> users.find(s.user.id).flatMap(u => Ok(me(s.copy(user = u.getOrElse(s.user)))))
      })

    // ---------------------------------------------------------------- APIキー

    case req @ GET -> Root / "api" / "me" / "keys" => sessionOnly(req)(s => users.keys(s.user.id).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "me" / "keys" =>
      sessionOnly(req)(s => body[CreateKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct.filter(KeyScope.all.contains)
        if r.name.trim.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else if scopes.isEmpty then err(Status.BadRequest, "スコープを選んでください")
        else if scopes.contains(KeyScope.Admin) && !s.isAdmin then err(Status.Forbidden, "admin スコープは管理者だけが付けられます")
        else
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.user.id, r.name.trim.take(100), prefix, Crypto.sha256(key), scopes, exp).flatMap { k =>
            audit(users, s, "key.create", Some(k.id.toString), Json.obj("scopes" -> scopes.asJson)) *> Created(CreatedKeyDto(key, k))
          }
      })

    case req @ DELETE -> Root / "api" / "me" / "keys" / UuidVar(id) =>
      sessionOnly(req)(s => users.revokeKey(s.user.id, id).flatMap {
        case true  => audit(users, s, "key.revoke", Some(id.toString)) *> NoContent()
        case false => err(Status.NotFound, "APIキーが見つかりません")
      })

    // ---------------------------------------------------------------- 管理

    case req @ GET -> Root / "api" / "admin" / "users" => admin(req)(_ => users.list(param(req, "q")).flatMap(Ok(_)))

    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) =>
      admin(req)(s => body[UpdateUserReq](req) { r =>
        if id == s.user.id then err(Status.BadRequest, "自分の権限は変えられません")
        else users.update(id, r.isAdmin, r.disabled).flatMap {
          case true  => audit(users, s, "user.update", Some(id.toString), r.asJson) *> NoContent()
          case false => err(Status.NotFound, "ユーザーが見つかりません")
        }
      })

    case req @ GET -> Root / "api" / "admin" / "audit" => admin(req)(_ => users.auditLog(intParam(req, "limit", 200, 1000)).flatMap(Ok(_)))
  }
