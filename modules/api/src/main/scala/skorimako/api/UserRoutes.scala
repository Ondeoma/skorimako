package skorimako.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skorimako.auth.ApiKeys
import skorimako.core.ErrorCode
import skorimako.db.*
import skorimako.shared.*

import java.time.Instant

import java.util.UUID

/** 自分の情報・APIキー、管理画面（ユーザー・監査ログ）。 */
final class UserRoutes(users: UserStore, val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => getMe(req)
    case req @ PATCH -> Root / "api" / "me" => patchMe(req)
    case req @ GET -> Root / "api" / "me" / "keys" => getMeKeys(req)
    case req @ POST -> Root / "api" / "me" / "keys" => postMeKeys(req)
    case req @ DELETE -> Root / "api" / "me" / "keys" / UuidVar(id) => deleteMeKey(req, id)
    case req @ GET -> Root / "api" / "admin" / "users" => getAdminUsers(req)
    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) => patchAdminUser(req, id)
    case req @ GET -> Root / "api" / "admin" / "audit" => getAdminAudit(req)
  }

  private def getMe(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => sessions.appNames(req).flatMap(n => Ok(me(s).copy(appNames = n))))

  private def patchMe(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[UpdateMeReq](req) { r =>
        val tz = r.timezone.map(_.trim).filter(_.nonEmpty)
        if tz.exists(z => scala.util.Try(java.time.ZoneId.of(z)).isFailure) then
          err(ErrorCode.TimezoneInvalid)
        else
          users.updateMe(s.user.id, r.name, tz) *>
            users.find(s.user.id).flatMap(u => Ok(me(s.copy(user = u.getOrElse(s.user)))))
      }
    )

  private def getMeKeys(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => users.keys(s.user.id).flatMap(Ok(_)))

  private def postMeKeys(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      body[CreateKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct.filter(KeyScope.all.contains)
        if r.name.trim.isEmpty then err(ErrorCode.NameRequired)
        else if scopes.isEmpty then err(ErrorCode.ApiKeyScopeRequired)
        else if scopes.contains(KeyScope.Admin) && !s.isAdmin then err(ErrorCode.ApiKeyAdminScopeAdminOnly)
        else {
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.user.id, r.name.trim.take(100), prefix, Crypto.sha256(key), scopes, exp).flatMap { k =>
            audit(users, s, "key.create", Some(k.id.toString), Json.obj("scopes" -> scopes.asJson)) *>
              Created(CreatedKeyDto(key, k))
          }
        }
      }
    )

  private def deleteMeKey(req: Request[IO], id: UUID): IO[Response[IO]] =
    sessionOnly(req)(s =>
      users.revokeKey(s.user.id, id).flatMap {
        case true => audit(users, s, "key.revoke", Some(id.toString)) *> NoContent()
        case false => err(ErrorCode.ApiKeyNotFound)
      }
    )

  private def getAdminUsers(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => users.list(param(req, "q")).flatMap(Ok(_)))

  private def patchAdminUser(req: Request[IO], id: UUID): IO[Response[IO]] =
    admin(req)(s =>
      body[UpdateUserReq](req) { r =>
        if id == s.user.id then err(ErrorCode.CannotChangeOwnRole)
        else
          users.update(id, r.isAdmin, r.disabled).flatMap {
            case true => audit(users, s, "user.update", Some(id.toString), r.asJson) *> NoContent()
            case false => err(ErrorCode.UserNotFound)
          }
      }
    )

  private def getAdminAudit(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => users.auditLog(intParam(req, "limit", 200, 1000)).flatMap(Ok(_)))

  private def me(s: skorimako.core.AuthSession): MeDto =
    MeDto(s.user.id, s.user.name, s.user.email, s.user.avatarUrl, s.isAdmin, s.user.timezone)

}
