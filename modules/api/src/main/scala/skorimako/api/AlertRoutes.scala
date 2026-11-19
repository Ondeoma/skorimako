package skorimako.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skorimako.auth.SecretBox
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.util.UUID

/** アラートのルール・履歴・通知先。 */
final class AlertRoutes(store: AlertStore,
                        svc: AlertService,
                        users: UserStore,
                        box: SecretBox,
                        val sessions: SessionAuth,
                        acl: Acl) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "alerts" => getAlerts(req)
    case req @ GET -> Root / "api" / "alerts" / "events" => getAlertsEvents(req)
    case req @ POST -> Root / "api" / "alerts" / "test" => postAlertsTest(req)
    case req @ POST -> Root / "api" / "alerts" => postAlerts(req)
    case req @ POST -> Root / "api" / "alerts" / UuidVar(id) / "ack" => postAlertAck(req, id)
    case req @ DELETE -> Root / "api" / "alerts" / UuidVar(id) / "ack" => deleteAlertAck(req, id)
    case req @ GET -> Root / "api" / "silences" => getSilences(req)
    case req @ POST -> Root / "api" / "silences" => postSilences(req)
    case req @ DELETE -> Root / "api" / "silences" / UuidVar(id) => deleteSilence(req, id)
    case req @ GET -> Root / "api" / "alerts" / UuidVar(id) => getAlert(req, id)
    case req @ PUT -> Root / "api" / "alerts" / UuidVar(id) => putAlert(req, id)
    case req @ DELETE -> Root / "api" / "alerts" / UuidVar(id) => deleteAlert(req, id)
    case req @ GET -> Root / "api" / "destinations" => getDestinations(req)
    case req @ POST -> Root / "api" / "destinations" => postDestinations(req)
    case req @ PUT -> Root / "api" / "destinations" / UuidVar(id) => putDestination(req, id)
    case req @ DELETE -> Root / "api" / "destinations" / UuidVar(id) => deleteDestination(req, id)
    case req @ POST -> Root / "api" / "destinations" / UuidVar(id) / "test" => postDestinationTest(req, id)
    case req @ GET -> Root / "api" / "deliveries" => getDeliveries(req)
  }

  private def getAlerts(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ => store.rules.flatMap(rs => Ok(rs.map(_.toDto))))

  private def getAlertsEvents(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ =>
      store.events(param(req, "ruleId").flatMap(uuidOpt), intParam(req, "limit", 100, 1000)).flatMap(Ok(_))
    )

  private def postAlertsTest(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[AlertRuleReq](req)(r =>
        svc.validate(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) => IO.realTimeInstant.flatMap(svc.evaluate(v, _, s.viewer)).flatMap {
              case Left(e) => Ok(AlertTestDto(false, Nil, Nil, "", Some(e)))
              case Right((sql, hits, values)) => Ok(AlertTestDto(hits.nonEmpty, hits, values.take(100), sql, None))
            }
        }
      )
    )

  private def postAlerts(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[AlertRuleReq](req)(r =>
        svc.validate(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) => canSee(s, v).ifM(
              store.create(v, s.user.id).flatMap(id =>
                audit(users, s, "alert.create", Some(id.toString)) *>
                  store.rule(id).flatMap(r => Created(r.map(_.toDto)))
              ),
              err(ErrorCode.StreamAccessDenied)
            )
        }
      )
    )

  private def postAlertAck(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[AckReq](req)(r =>
        store.ack(id, s.user.id, s.user.name, r.note.trim.take(500)).flatMap {
          case true => audit(users, s, "alert.ack", Some(id.toString)) *>
              store.rule(id).flatMap(x => Ok(x.map(_.toDto)))
          case false => err(ErrorCode.AlertAckNotFiring)
        }
      )
    )

  private def deleteAlertAck(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => store.unack(id) *> audit(users, s, "alert.unack", Some(id.toString)) *> NoContent())

  private def getSilences(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ => store.silences(param(req, "all").contains("true")).flatMap(Ok(_)))

  private def postSilences(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[SilenceReq](req) { r =>
        val now = java.time.Instant.now()
        val (st, en) = r.minutes match {
          case Some(m) => (now, now.plusSeconds(m.toLong * 60))
          case None => (r.startsAt.getOrElse(now), r.endsAt.getOrElse(now.plusSeconds(3600)))
        }
        if !en.isAfter(st) then err(ErrorCode.MuteEndBeforeStart)
        else if java.time.Duration.between(st, en).toDays > 30 then err(ErrorCode.MuteTooLong)
        else if r.ruleId.isEmpty && !s.canAdmin then err(ErrorCode.MuteAllAdminOnly)
        else
          store.createSilence(r.ruleId, r.reason.trim.take(500), st, en, s.user.id).flatMap(id =>
            audit(users, s, "silence.create", Some(id.toString)) *> Created(Json.obj("id" -> id.asJson))
          )
      }
    )

  private def deleteSilence(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      store.endSilence(id).flatMap {
        case true => audit(users, s, "silence.end", Some(id.toString)) *> NoContent()
        case false => err(ErrorCode.MuteNotFound)
      }
    )

  private def getAlert(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(_ => store.rule(id).flatMap(_.fold(err(ErrorCode.RuleNotFound))(r => Ok(r.toDto))))

  private def putAlert(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[AlertRuleReq](req)(r =>
        svc.validate(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) => canSee(s, v).ifM(
              store.update(id, v).flatMap {
                case false => err(ErrorCode.RuleNotFound)
                case true => audit(users, s, "alert.update", Some(id.toString)) *>
                    store.rule(id).flatMap(r => Ok(r.map(_.toDto)))
              },
              err(ErrorCode.StreamAccessDenied)
            )
        }
      )
    )

  private def deleteAlert(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      store.delete(id).flatMap {
        case false => err(ErrorCode.RuleNotFound)
        case true => audit(users, s, "alert.delete", Some(id.toString)) *> NoContent()
      }
    )

  private def getDestinations(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ => store.destinations.flatMap(Ok(_)))

  private def postDestinations(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[DestinationReq](req)(r =>
        destOk(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) if !canManage(s, v.kind, v.targetUserId) =>
            err(ErrorCode.ChannelCreateAdminOnly)
          case Right(v) =>
            store.createDestination(v, v.secret.map(_.trim).filter(_.nonEmpty).map(box.encrypt), s.user.id).flatMap(
              id =>
                audit(users, s, "destination.create", Some(id.toString)) *> store.destination(id).flatMap(Created(_))
            )
        }
      )
    )

  private def putDestination(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[DestinationReq](req)(r =>
        destOk(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) if !canManage(s, v.kind, v.targetUserId) => err(ErrorCode.ChannelUpdateAdminOnly)
          case Right(v) =>
            // secret: 省略なら変えない、空の文字列なら外す
            val secret = v.secret.map(x => Option(x.trim).filter(_.nonEmpty).map(box.encrypt))
            store.updateDestination(id, v, secret).flatMap {
              case false => err(ErrorCode.ChannelNotFound)
              case true => audit(users, s, "destination.update", Some(id.toString)) *>
                  store.destination(id).flatMap(Ok(_))
            }
        }
      )
    )

  private def deleteDestination(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      store.destination(id).flatMap {
        case None => err(ErrorCode.ChannelNotFound)
        case Some(d) if !canManage(s, d.kind, d.targetUser.map(_.id)) => err(ErrorCode.ChannelDeleteAdminOnly)
        case Some(_) => store.deleteDestination(id) *> audit(users, s, "destination.delete", Some(id.toString)) *>
            NoContent()
      }
    )

  private def postDestinationTest(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      store.destination(id).map(_.filter(d => canManage(s, d.kind, d.targetUser.map(_.id)))).flatMap {
        case None => err(ErrorCode.ChannelTestAdminOnly)
        case Some(_) => svc.test(id).flatMap {
            case Right(m) => Ok(Json.obj("message" -> m.asJson))
            case Left(e) => err(ErrorCode.ChannelTestFailed, s"送れませんでした: $e")
          }
      }
    )

  private def getDeliveries(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => store.deliveries(intParam(req, "limit", 100, 1000)).flatMap(Ok(_)))

  /** ルールの対象のストリームを見られるか（SQL のルールは評価のときに確かめる）。 */
  private def canSee(s: AuthSession, r: AlertRuleReq): IO[Boolean] =
    if r.mode == AlertMode.Sql then IO.pure(true) else acl.visible(s.viewer, r.streamType, r.stream)

  /** 通知先を扱えるか: 管理者、または自分宛ての stui の DM。 */
  private def canManage(s: AuthSession, kind: String, target: Option[java.util.UUID]): Boolean =
    s.canAdmin || (kind == DestinationKind.StuiDm && target.contains(s.user.id) && s.canWrite)

  private def destOk(r: DestinationReq): Either[String, DestinationReq] = {
    val dm = r.kind == DestinationKind.StuiDm
    for {
      _ <- Either.cond(r.name.trim.nonEmpty, (), "名前を入力してください")
      _ <- Either.cond(DestinationKind.all.contains(r.kind), (), "種類が正しくありません")
      _ <- Either.cond(
        dm || r.url.trim.startsWith("https://") || r.url.trim.startsWith("http://"),
        (),
        "URL は http:// か https:// で始めてください"
      )
      _ <- Either.cond(dm || Uri.fromString(r.url.trim).isRight, (), "URL が正しくありません")
      _ <- Either.cond(!dm || r.targetUserId.isDefined, (), "DM を送る人を指定してください")
    } yield r.copy(
      name = r.name.trim.take(200),
      url = if dm then "" else r.url.trim,
      targetUserId = if dm then r.targetUserId else None
    )
  }

}
