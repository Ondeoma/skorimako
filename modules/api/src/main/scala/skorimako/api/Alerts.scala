package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import skorimako.auth.{Crypto, SecretBox}
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * アラートの評価と通知。
 * 評価: 時刻が来たルールを全ノードが取り合い（次の評価の時刻を先に進めるので二重にしない）、直近 period の範囲で SQL を実行する。
 * 通知: 状態が変わったとき（発生・解消）と、発生中に沈黙の時間が過ぎたときに、通知先へ送る（送信待ちの表から再試行つきで）。
 */
final class AlertService(store: AlertStore, engine: QueryEngine, http: Client[IO], box: SecretBox, cfg: ApiConfig, stui: StuiClient, stats: Stats):

  // ---------------------------------------------------------------- 検査・評価

  /** ルールの内容を確かめ、正しい形にする。 */
  def validate(r: AlertRuleReq): Either[String, AlertRuleReq] =
    val name = r.name.trim
    for
      _ <- Either.cond(name.nonEmpty && name.length <= 200, (), "名前を入力してください（200文字まで）")
      _ <- Either.cond(StreamType.all.contains(r.streamType), (), "データの種類が正しくありません")
      _ <- Either.cond(Set(AlertMode.Threshold, AlertMode.Sql, AlertMode.Absence).contains(r.mode), (), "評価の方法が正しくありません")
      lookback = r.lookbackMinutes.getOrElse(1440)
      _ <- Either.cond(r.mode != AlertMode.Absence || (lookback > r.periodMinutes && lookback <= 30 * 24 * 60), (),
             "途絶の判定で見る範囲は、期間より長く30日以内にしてください")
      _ <- Either.cond(AlertOp.all.contains(r.op), (), "比べ方が正しくありません")
      _ <- Either.cond(!r.threshold.isNaN && !r.threshold.isInfinite, (), "閾値が正しくありません")
      _ <- Either.cond(r.periodMinutes >= 1 && r.periodMinutes <= 7 * 24 * 60, (), "対象の期間は 1分〜7日 にしてください")
      _ <- Either.cond(r.everyMinutes >= 1 && r.everyMinutes <= 24 * 60, (), "評価の間隔は 1分〜1日 にしてください")
      _ <- Either.cond(r.silenceMinutes >= 0 && r.silenceMinutes <= 7 * 24 * 60, (), "再通知の間隔は 0分〜7日 にしてください")
      stream <- if r.mode != AlertMode.Sql then Names.stream(r.stream) else Right(r.stream.trim)
      _ <- if r.mode == AlertMode.Sql then SqlText.checkQuery(r.sql.getOrElse("")).void
           else
             for
               _ <- Either.cond(AlertAgg.all.contains(r.agg), (), "集計の方法が正しくありません")
               _ <- Either.cond(r.agg == "count" || r.field.exists(_.trim.nonEmpty), (), "集計する列を指定してください")
               _ <- r.where.filter(_.trim.nonEmpty).traverse(SqlText.checkCondition)
             yield ()
    yield r.copy(name = name, stream = stream, description = r.description.trim.take(2000), where = r.where.map(_.trim).filter(_.nonEmpty),
      field = r.field.map(_.trim).filter(_.nonEmpty), groupBy = r.groupBy.map(_.trim).filter(_.nonEmpty), sql = r.sql.map(_.trim).filter(_.nonEmpty),
      destinationIds = r.destinationIds.distinct, lookbackMinutes = Some(lookback))

  /** 評価する（通知はしない）。values はすべての値、hits は条件に当てはまったもの。 */
  def evaluate(r: AlertRuleReq, now: Instant, viewer: Viewer = Viewer.system): IO[Either[String, (String, List[AlertHitDto], List[AlertHitDto])]] =
    if r.mode == AlertMode.Absence then absence(r, now, viewer) else evaluateQuery(r, now, viewer)

  /**
   * 途絶: lookback の範囲で来ていたデータ（groupBy の値ごと）の、最後の行からの分。直近 period の間に来ていなければ当てはまる。
   * groupBy がなければ、ストリーム全体で直近 period の間に1行もなければ当てはまる。
   */
  private def absence(r: AlertRuleReq, now: Instant, viewer: Viewer): IO[Either[String, (String, List[AlertHitDto], List[AlertHitDto])]] =
    val end   = now.getEpochSecond * 1000000L + now.getNano / 1000
    val look  = r.lookbackMinutes.getOrElse(1440).toLong
    val start = end - look * 60L * 1000000L
    QueryBuilder.conditions(r.where, None, Nil).map(cs => QueryBuilder.absence(r.stream, r.groupBy, cs)) match
      case Left(e) => IO.pure(Left(e))
      case Right(sql) =>
        engine.acl.visible(viewer, r.streamType, r.stream).flatMap {
          case false => IO.pure(Left(s"ストリーム ${r.stream} を見る権限がありません"))
          case true =>
            engine.session(r.streamType, List(r.stream), start, end, Prune.constraints(r.where))(_.query(sql, 10000)).map(_.map { (res, _, _) =>
              val names = res.columns.map(_.name)
              val values = res.rows.map { row =>
                val m = names.zip(row).toMap
                val last = m.get("last").flatMap(_.asNumber).flatMap(_.toLong)
                // 最後の行からの分（なければ範囲の長さ）
                val mins = last.map(l => (end - l) / 60000000.0).getOrElse(look.toDouble)
                AlertHitDto(m.get("grp").map(QueryEngine.text), math.round(mins * 10) / 10.0)
              }
              (sql, values.filter(_.value >= r.periodMinutes), values)
            })
        }

  private def evaluateQuery(r: AlertRuleReq, now: Instant, viewer: Viewer): IO[Either[String, (String, List[AlertHitDto], List[AlertHitDto])]] =
    val end   = now.getEpochSecond * 1000000L + now.getNano / 1000
    val start = end - r.periodMinutes.toLong * 60L * 1000000L
    val built: Either[String, (String, List[String])] =
      if r.mode == AlertMode.Sql then SqlText.checkQuery(r.sql.getOrElse("")).map((sql, names) => (sql, names.toList))
      else QueryBuilder.conditions(r.where, None, Nil).flatMap(cs => QueryBuilder.alert(r.stream, r.agg, r.field, r.groupBy, cs)).map(_ -> List(r.stream))
    built match
      case Left(e) => IO.pure(Left(e))
      case Right((sql, names)) =>
        engine.referenced(viewer, r.streamType, names.toSet).flatMap {
          case Left(e) => IO.pure(Left(e))
          case Right(Nil) => IO.pure(Left("SQL の FROM にストリームの名前を書いてください"))
          case Right(ss) =>
            engine.session(r.streamType, ss, start, end, if r.mode == AlertMode.Sql then Map.empty else Prune.constraints(r.where))(_.query(QueryBuilder.expand(sql, start, end), 1000)).map(_.map { (res, _, _) =>
              val values = AlertService.values(res)
              (sql, values.filter(v => AlertLogic.compare(r.op, v.value, r.threshold)), values)
            })
        }

  /** 時刻が来たルールを評価して、結果を記録する。 */
  def tick: IO[Int] =
    store.claimDue(20).flatMap(_.traverse { rule =>
      IO.realTimeInstant.flatMap { now =>
        stats.inc("skorimako_alert_evaluations_total")
        evaluate(rule.toReq, now).attempt.map(_.leftMap(e => s"評価できませんでした: ${e.getClass.getSimpleName}").flatten).flatMap {
          case Left(e) =>
            // 評価できない状態は、初めてそうなったときだけ履歴に残す（通知はしない）
            val ev = Option.when(rule.state != AlertState.Error)(("error", None, Nil, e.take(500)))
            store.record(rule, EvalRecord(AlertState.Error, None, Some(e.take(1000)), notified = false, fired = false, ev))
          case Right((_, hits, values)) =>
            val firing = hits.nonEmpty
            val prev   = if rule.state == AlertState.Error then AlertState.Ok else rule.state
            val (state, action0) = AlertLogic.next(prev, firing, rule.lastNotifiedAt, rule.silenceMinutes, now)
            // 確認済み（対応中）の間は再通知しない
            val action = if action0 == AlertLogic.Action.Fire && prev == AlertState.Firing && rule.acked then AlertLogic.Action.None else action0
            val value  = (if firing then hits else values).map(_.value).maxOption
            val event = action match
              case AlertLogic.Action.Fire    => Some(("firing", value, hits, s"${rule.name}: ${AlertService.describe(rule.mode, hits, rule.op, rule.threshold)}"))
              case AlertLogic.Action.Resolve => Some(("resolved", value, Nil, s"${rule.name}: 解消しました"))
              case AlertLogic.Action.None    => None
            store.record(rule, EvalRecord(state, value, None, notified = event.isDefined, fired = action == AlertLogic.Action.Fire, event))
        }
      }
    }.map(_.size))

  def loop: IO[Nothing] =
    Periodic.run(15.seconds, 10.seconds)(e => s"[skorimako] alert evaluation failed: $e")(tick)

  // ---------------------------------------------------------------- 通知

  /** 通知先が内部のネットワークでないか（WEBHOOK_DENY_PRIVATE=true のときだけ確かめる。stui は社内に置くことが多いため既定では許す）。 */
  def allowedHost(url: String): IO[Boolean] =
    if !cfg.webhookDenyPrivate then IO.pure(true)
    else
      Uri.fromString(url).toOption.flatMap(_.host).map(_.value.stripPrefix("[").stripSuffix("]")) match
        case None => IO.pure(false)
        case Some(h) =>
          IO.blocking(java.net.InetAddress.getAllByName(h).toList).map(_.forall { a =>
            val b = a.getAddress
            val ula = b.length == 16 && (b(0) & 0xfe) == 0xfc
            val cgnat = b.length == 4 && (b(0) & 0xff) == 100 && (b(1) & 0xc0) == 64
            !(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || ula || cgnat)
          }).handleError(_ => false)

  private def ruleUrl(id: UUID): String = s"${cfg.publicUrl}/alerts/$id"

  /** 通知の本文。stui は Markdown、Slack は mrkdwn、webhook は JSON。 */
  def payload(d: DeliveryRow): Json =
    val hits = d.hits.as[List[AlertHitDto]].getOrElse(Nil)
    val (icon, label) = d.eventState match
      case "firing"   => ("🔴", "発生")
      case "resolved" => ("✅", "解消")
      case "test"     => ("🧪", "通知の試験")
      case "acknowledged" => ("👀", "対応中")
      case s          => ("⚠️", s)
    val detail =
      if d.eventState == "test" then "skorimako からの通知の試験です"
      else d.message.stripPrefix(d.ruleName + ": ")
    val test  = d.eventState == "test"
    val where = if test then "" else s"${StreamType.label(d.streamType)} / ${d.stream}"
    val url = if test then cfg.publicUrl else ruleUrl(d.ruleId)
    d.kind match
      case DestinationKind.Webhook =>
        Json.obj(
          "event" -> s"alert.${d.eventState}".asJson,
          "rule" -> Json.obj("id" -> d.ruleId.asJson, "name" -> d.ruleName.asJson, "op" -> d.op.asJson, "threshold" -> d.threshold.asJson,
            "streamType" -> d.streamType.asJson, "stream" -> d.stream.asJson),
          "value" -> d.value.asJson, "hits" -> hits.asJson, "message" -> d.message.asJson, "url" -> url.asJson, "at" -> d.at.asJson)
      case DestinationKind.Slack =>
        Json.obj("username" -> "skorimako".asJson,
          "text" -> s"$icon *[$label] ${d.ruleName}*\n$detail\n$where  <$url|${if test then "skorimako を開く" else "詳しく見る"}>".asJson)
      case _ =>
        Json.obj("username" -> "skorimako".asJson,
          "text" -> s"$icon **[$label] ${d.ruleName}**\n$detail\n$where  [${if test then "skorimako を開く" else "詳しく見る"}]($url)".asJson)

  def send(d: DeliveryRow): IO[OutboxResult] =
    sendRaw(d).flatTap { r =>
      val result = r match
        case OutboxResult.Sent     => "sent"
        case _: OutboxResult.Skip  => "skipped"
        case _: OutboxResult.Retry => "retry"
      IO(stats.inc("skorimako_alert_notifications_total", Map("result" -> result)))
    }

  private def sendRaw(d: DeliveryRow): IO[OutboxResult] =
    if d.kind == DestinationKind.StuiDm then
      d.targetUserId match
        case None => IO.pure(OutboxResult.Skip("宛先の人がいません"))
        case Some(u) =>
          val text = payload(d.copy(kind = DestinationKind.Stui)).hcursor.get[String]("text").getOrElse(d.message)
          stui.notify(u, text)
    else allowedHost(d.url).flatMap {
      case false => IO.pure(OutboxResult.Skip("送信先が内部のネットワークのため送りませんでした"))
      case true =>
        val body = payload(d).noSpaces
        val sig  = d.secret.flatMap(box.decrypt).map(k => "sha256=" + Crypto.hmacHex(k, body.getBytes(StandardCharsets.UTF_8)))
        Uri.fromString(d.url).toOption match
          case None => IO.pure(OutboxResult.Skip("URL が正しくありません"))
          case Some(uri) =>
            // 署名した文字列をそのまま送る（circe のエンコーダーを通すと JSON の文字列として包まれてしまう）
            val req = Request[IO](Method.POST, uri).withEntity(body)(using EntityEncoder.stringEncoder)
              .withContentType(`Content-Type`(MediaType.application.json))
              .putHeaders("User-Agent" -> "skorimako-alert", "X-Skorimako-Event" -> s"alert.${d.eventState}", "X-Skorimako-Delivery" -> d.id.toString)
            val signed = sig.fold(req)(s => req.putHeaders("X-Skorimako-Signature" -> s))
            http.run(signed).use(r => IO.pure(r.status.code)).timeout(15.seconds).attempt.map {
              case Right(c) if c >= 200 && c < 300 => OutboxResult.Sent
              case Right(c) if c == 404 || c == 410 => OutboxResult.Skip(s"HTTP $c")
              case Right(c) => OutboxResult.Retry(s"HTTP $c")
              case Left(_: java.util.concurrent.TimeoutException) => OutboxResult.Retry("時間切れ")
              case Left(_) => OutboxResult.Retry("接続できませんでした")
            }
    }

  /** 通知先を試す（その場で送り、結果を返す）。 */
  def test(dest: UUID): IO[Either[String, String]] =
    store.destination(dest).flatMap {
      case None => IO.pure(Left("通知先が見つかりません"))
      case Some(_) =>
        store.destinationRow(dest).flatMap {
          case None => IO.pure(Left("通知先が見つかりません"))
          case Some((kind, url, secret, target)) =>
            val d = DeliveryRow(UUID.randomUUID(), 0, kind, url, secret, "test", Some(1), Json.arr(), "通知の試験", Instant.now(), UUID.randomUUID(),
              "通知の試験", ">", 0, StreamType.Logs, "default", target)
            send(d).map {
              case OutboxResult.Sent      => Right("送りました")
              case OutboxResult.Skip(r)   => Left(r)
              case OutboxResult.Retry(r)  => Left(r)
            }
        }
    }

  def deliveryLoop: IO[Nothing] =
    Periodic.run(10.seconds, 5.seconds)(e => s"[skorimako] alert delivery failed: $e")(store.processDeliveries(50)(send))

object AlertService:
  /** 発生のときの文言。途絶は「最後のデータから◯分」。 */
  def describe(mode: String, hits: List[AlertHitDto], op: String, threshold: Double): String =
    if mode == AlertMode.Absence then
      val parts = hits.take(10).map(h => h.group.fold("")(g => s"$g: ") + s"${AlertLogic.fmt(h.value)}分")
      s"データが来ていません（最後のデータから ${parts.mkString("、")}${if hits.size > 10 then s" ほか${hits.size - 10}件" else ""}）"
    else AlertLogic.describe(hits, op, threshold)

  /** 結果の各行の値: value の列（なければ最初の数値の列）。grp の列（なければ数値でない最初の列）を分け方にする。 */
  def values(res: QueryResultDto): List[AlertHitDto] =
    val names = res.columns.map(_.name.toLowerCase)
    res.rows.flatMap { row =>
      val cells = names.zip(row)
      val v = cells.find(_._1 == "value").flatMap(_._2.asNumber).orElse(cells.flatMap(_._2.asNumber).headOption).map(_.toDouble)
      val g = cells.find(_._1 == "grp").map(_._2).orElse(cells.find(c => c._2.asNumber.isEmpty && !c._2.isNull).map(_._2))
        .map(QueryEngine.text)
      v.map(AlertHitDto(g, _))
    }

/** アラートのルール・履歴・通知先。 */
final class AlertRoutes(store: AlertStore, svc: AlertService, users: UserStore, box: SecretBox, val sessions: SessionAuth, acl: Acl) extends RouteSupport:

  /** ルールの対象のストリームを見られるか（SQL のルールは評価のときに確かめる）。 */
  private def canSee(s: AuthSession, r: AlertRuleReq): IO[Boolean] =
    if r.mode == AlertMode.Sql then IO.pure(true) else acl.visible(s.viewer, r.streamType, r.stream)

  /** 通知先を扱えるか: 管理者、または自分宛ての stui の DM。 */
  private def canManage(s: AuthSession, kind: String, target: Option[java.util.UUID]): Boolean =
    s.canAdmin || (kind == DestinationKind.StuiDm && target.contains(s.user.id) && s.canWrite)

  private def destOk(r: DestinationReq): Either[String, DestinationReq] =
    val dm = r.kind == DestinationKind.StuiDm
    for
      _ <- Either.cond(r.name.trim.nonEmpty, (), "名前を入力してください")
      _ <- Either.cond(DestinationKind.all.contains(r.kind), (), "種類が正しくありません")
      _ <- Either.cond(dm || r.url.trim.startsWith("https://") || r.url.trim.startsWith("http://"), (), "URL は http:// か https:// で始めてください")
      _ <- Either.cond(dm || Uri.fromString(r.url.trim).isRight, (), "URL が正しくありません")
      _ <- Either.cond(!dm || r.targetUserId.isDefined, (), "DM を送る人を指定してください")
    yield r.copy(name = r.name.trim.take(200), url = if dm then "" else r.url.trim, targetUserId = if dm then r.targetUserId else None)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "alerts" => authed(req)(_ => store.rules.flatMap(rs => Ok(rs.map(_.toDto))))

    case req @ GET -> Root / "api" / "alerts" / "events" =>
      authed(req)(_ => store.events(param(req, "ruleId").flatMap(uuidOpt), intParam(req, "limit", 100, 1000)).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "alerts" / "test" =>
      authed(req)(s => body[AlertRuleReq](req)(r => svc.validate(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) => IO.realTimeInstant.flatMap(svc.evaluate(v, _, s.viewer)).flatMap {
          case Left(e)                      => Ok(AlertTestDto(false, Nil, Nil, "", Some(e)))
          case Right((sql, hits, values))   => Ok(AlertTestDto(hits.nonEmpty, hits, values.take(100), sql, None))
        }))

    case req @ POST -> Root / "api" / "alerts" =>
      authed(req)(s => body[AlertRuleReq](req)(r => svc.validate(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) => canSee(s, v).ifM(store.create(v, s.user.id).flatMap(id =>
          audit(users, s, "alert.create", Some(id.toString)) *> store.rule(id).flatMap(r => Created(r.map(_.toDto)))),
          err(Status.Forbidden, "このストリームを見る権限がありません"))))

    // ---------------------------------------------------------------- 確認済み・ミュート

    case req @ POST -> Root / "api" / "alerts" / UuidVar(id) / "ack" =>
      authed(req)(s => body[AckReq](req)(r => store.ack(id, s.user.id, s.user.name, r.note.trim.take(500)).flatMap {
        case true  => audit(users, s, "alert.ack", Some(id.toString)) *> store.rule(id).flatMap(x => Ok(x.map(_.toDto)))
        case false => err(Status.Conflict, "発生中のルールだけを確認済みにできます")
      }))

    case req @ DELETE -> Root / "api" / "alerts" / UuidVar(id) / "ack" =>
      authed(req)(s => store.unack(id) *> audit(users, s, "alert.unack", Some(id.toString)) *> NoContent())

    case req @ GET -> Root / "api" / "silences" =>
      authed(req)(_ => store.silences(param(req, "all").contains("true")).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "silences" =>
      authed(req)(s => body[SilenceReq](req) { r =>
        val now = java.time.Instant.now()
        val (st, en) = r.minutes match
          case Some(m) => (now, now.plusSeconds(m.toLong * 60))
          case None    => (r.startsAt.getOrElse(now), r.endsAt.getOrElse(now.plusSeconds(3600)))
        if !en.isAfter(st) then err(Status.BadRequest, "終わりは始まりより後にしてください")
        else if java.time.Duration.between(st, en).toDays > 30 then err(Status.BadRequest, "ミュートは30日までにしてください")
        else if r.ruleId.isEmpty && !s.canAdmin then err(Status.Forbidden, "すべてのルールのミュートは管理者だけができます")
        else store.createSilence(r.ruleId, r.reason.trim.take(500), st, en, s.user.id).flatMap(id =>
          audit(users, s, "silence.create", Some(id.toString)) *> Created(Json.obj("id" -> id.asJson)))
      })

    case req @ DELETE -> Root / "api" / "silences" / UuidVar(id) =>
      authed(req)(s => store.endSilence(id).flatMap {
        case true  => audit(users, s, "silence.end", Some(id.toString)) *> NoContent()
        case false => err(Status.NotFound, "ミュートが見つかりません（終わっています）")
      })

    case req @ GET -> Root / "api" / "alerts" / UuidVar(id) =>
      authed(req)(_ => store.rule(id).flatMap(_.fold(err(Status.NotFound, "ルールが見つかりません"))(r => Ok(r.toDto))))

    case req @ PUT -> Root / "api" / "alerts" / UuidVar(id) =>
      authed(req)(s => body[AlertRuleReq](req)(r => svc.validate(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) => canSee(s, v).ifM(store.update(id, v).flatMap {
          case false => err(Status.NotFound, "ルールが見つかりません")
          case true  => audit(users, s, "alert.update", Some(id.toString)) *> store.rule(id).flatMap(r => Ok(r.map(_.toDto)))
        }, err(Status.Forbidden, "このストリームを見る権限がありません"))))

    case req @ DELETE -> Root / "api" / "alerts" / UuidVar(id) =>
      authed(req)(s => store.delete(id).flatMap {
        case false => err(Status.NotFound, "ルールが見つかりません")
        case true  => audit(users, s, "alert.delete", Some(id.toString)) *> NoContent()
      })

    // ---------------------------------------------------------------- 通知先（管理者）

    case req @ GET -> Root / "api" / "destinations" => authed(req)(_ => store.destinations.flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "destinations" =>
      authed(req)(s => body[DestinationReq](req)(r => destOk(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) if !canManage(s, v.kind, v.targetUserId) => err(Status.Forbidden, "通知先の追加は管理者だけができます（自分宛ての stui の DM は誰でも追加できます）")
        case Right(v) =>
          store.createDestination(v, v.secret.map(_.trim).filter(_.nonEmpty).map(box.encrypt), s.user.id).flatMap(id =>
            audit(users, s, "destination.create", Some(id.toString)) *> store.destination(id).flatMap(Created(_)))))

    case req @ PUT -> Root / "api" / "destinations" / UuidVar(id) =>
      authed(req)(s => body[DestinationReq](req)(r => destOk(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) if !canManage(s, v.kind, v.targetUserId) => err(Status.Forbidden, "通知先の変更は管理者だけができます")
        case Right(v) =>
          // secret: 省略なら変えない、空の文字列なら外す
          val secret = v.secret.map(x => Option(x.trim).filter(_.nonEmpty).map(box.encrypt))
          store.updateDestination(id, v, secret).flatMap {
            case false => err(Status.NotFound, "通知先が見つかりません")
            case true  => audit(users, s, "destination.update", Some(id.toString)) *> store.destination(id).flatMap(Ok(_))
          }))

    case req @ DELETE -> Root / "api" / "destinations" / UuidVar(id) =>
      authed(req)(s => store.destination(id).flatMap {
        case None => err(Status.NotFound, "通知先が見つかりません")
        case Some(d) if !canManage(s, d.kind, d.targetUser.map(_.id)) => err(Status.Forbidden, "通知先の削除は管理者だけができます")
        case Some(_) => store.deleteDestination(id) *> audit(users, s, "destination.delete", Some(id.toString)) *> NoContent()
      })

    case req @ POST -> Root / "api" / "destinations" / UuidVar(id) / "test" =>
      authed(req)(s => store.destination(id).map(_.filter(d => canManage(s, d.kind, d.targetUser.map(_.id)))).flatMap {
        case None => err(Status.Forbidden, "通知先を試せるのは管理者だけです（自分宛ての stui の DM は試せます）")
        case Some(_) => svc.test(id).flatMap {
          case Right(m) => Ok(Json.obj("message" -> m.asJson))
          case Left(e)  => err(Status.BadGateway, s"送れませんでした: $e")
        }
      })

    case req @ GET -> Root / "api" / "deliveries" => admin(req)(_ => store.deliveries(intParam(req, "limit", 100, 1000)).flatMap(Ok(_)))
  }
