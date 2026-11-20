package skorimako.api

import cats.effect.*
import cats.syntax.all.*
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
    authed(req)(s => store.rules.flatMap(svc.access.filter(s.viewer, _)).flatMap(rs => Ok(rs.map(_.toDto))))

  private def getAlertsEvents(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      svc.access.events(s.viewer, param(req, "ruleId").flatMap(uuidOpt), intParam(req, "limit", 100, 1000))
        .flatMap(Ok(_))
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
        withRule(s, id)(_ =>
          store.ack(id, s.user.id, s.user.name, r.note.trim.take(500)).flatMap {
            case true => audit(users, s, "alert.ack", Some(id.toString)) *>
                store.rule(id).flatMap(x => Ok(x.map(_.toDto)))
            case false => err(ErrorCode.AlertAckNotFiring)
          }
        )
      )
    )

  private def deleteAlertAck(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      withRule(s, id)(_ => store.unack(id) *> audit(users, s, "alert.unack", Some(id.toString)) *> NoContent())
    )

  /** ミュートの一覧。すべてのルールのミュートと、見られるルールのミュートだけ。 */
  private def getSilences(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      (store.silences(param(req, "all").contains("true")), svc.access.visibleIds(s.viewer)).tupled.flatMap {
        (xs, ids) => Ok(xs.filter(x => x.ruleId.forall(id => ids.forall(_.contains(id)))))
      }
    )

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
          // ルールを指定したミュートは、そのルールを見られる人だけ（見られないルールは、ないものと同じ）
          r.ruleId.traverse(svc.access.rule(s.viewer, _)).flatMap {
            case Some(None) => err(ErrorCode.RuleNotFound)
            case _ =>
              store.createSilence(r.ruleId, r.reason.trim.take(500), st, en, s.user.id).flatMap(id =>
                audit(users, s, "silence.create", Some(id.toString)) *> Created(Json.obj("id" -> id.asJson))
              )
          }
      }
    )

  /** ミュートを終わらせる。作成した人か管理者だけ。 */
  private def deleteSilence(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      store.silenceOwner(id).flatMap {
        case None => err(ErrorCode.MuteNotFound)
        case Some(owner) if !s.canAdmin && !owner.contains(s.user.id) => err(ErrorCode.MuteEndNotAllowed)
        case Some(_) =>
          store.endSilence(id).flatMap {
            case true => audit(users, s, "silence.end", Some(id.toString)) *> NoContent()
            case false => err(ErrorCode.MuteNotFound)
          }
      }
    )

  private def getAlert(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => withRule(s, id)(r => Ok(r.toDto)))

  private def putAlert(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[AlertRuleReq](req)(r =>
        svc.validate(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          // いまのルールを見られる人だけが変えられる（見られないルールは、ないものと同じ）
          case Right(v) => withRule(s, id)(_ =>
              canSee(s, v).ifM(
                store.update(id, v).flatMap {
                  case false => err(ErrorCode.RuleNotFound)
                  case true => audit(users, s, "alert.update", Some(id.toString)) *>
                      store.rule(id).flatMap(r => Ok(r.map(_.toDto)))
                },
                err(ErrorCode.StreamAccessDenied)
              )
            )
        }
      )
    )

  private def deleteAlert(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      withRule(s, id)(_ =>
        store.delete(id).flatMap {
          case false => err(ErrorCode.RuleNotFound)
          case true => audit(users, s, "alert.delete", Some(id.toString)) *> NoContent()
        }
      )
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

  /** ルールの対象のストリームを見られるか（SQL のルールは、SQL が参照するストリームすべて）。 */
  private def canSee(s: AuthSession, r: AlertRuleReq): IO[Boolean] = svc.access.canSee(s.viewer, r)

  /** 保存してあるルールを見られるか（canSee と同じ判定）。 */
  private def visibleRule(s: AuthSession, rule: RuleRow): IO[Boolean] = svc.access.visibleRule(s.viewer, rule)

  /** 見られるルールで f を行う。見られないルールは、ないものと同じ（404）。 */
  private def withRule(s: AuthSession, id: UUID)(f: RuleRow => IO[Response[IO]]): IO[Response[IO]] =
    store.rule(id).flatMap(_.filterA(visibleRule(s, _))).flatMap(_.fold(err(ErrorCode.RuleNotFound))(f))

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
