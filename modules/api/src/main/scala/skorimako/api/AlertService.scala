package skorimako.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import sbirds.auth.Crypto
import skorimako.auth.SecretBox
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import sbirds.net.NetGuard
import sbirds.push.PushMessage
import skorimako.shared.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * アラートの評価と通知。
 * 評価: 時刻が来たルールを全ノードが取り合い（次の評価の時刻を先に進めるので二重にしない）、直近 period の範囲で SQL を実行する。
 * 通知: 状態が変わったとき（発生・解消）と、発生中に沈黙の時間が過ぎたときに、通知先へ送る（送信待ちの表から再試行つきで）。
 * http は利用者が決めた通知先へ送るクライアント（Main で NetGuard.client から作る。リダイレクトは追わない）。
 */
final class AlertService(store: AlertStore,
                         engine: QueryEngine,
                         http: Client[IO],
                         box: SecretBox,
                         cfg: ApiConfig,
                         stui: StuiClient,
                         stats: Stats,
                         push: PushService) {

  // ---------------------------------------------------------------- 検査・評価

  /** ルールの内容を確かめ、正しい形にする。 */
  def validate(r: AlertRuleReq): Either[String, AlertRuleReq] = {
    val name = r.name.trim
    for {
      _ <- Either.cond(name.nonEmpty && name.length <= 200, (), "名前を入力してください（200文字まで）")
      _ <- Either.cond(StreamType.all.contains(r.streamType), (), "データの種類が正しくありません")
      _ <-
        Either.cond(Set(AlertMode.Threshold, AlertMode.Sql, AlertMode.Absence).contains(r.mode), (), "評価の方法が正しくありません")
      lookback = r.lookbackMinutes.getOrElse(1440)
      _ <- Either.cond(
        r.mode != AlertMode.Absence || (lookback > r.periodMinutes && lookback <= 30 * 24 * 60),
        (),
        "途絶の判定で見る範囲は、期間より長く30日以内にしてください"
      )
      _ <- Either.cond(AlertOp.all.contains(r.op), (), "比べ方が正しくありません")
      _ <- Either.cond(!r.threshold.isNaN && !r.threshold.isInfinite, (), "閾値が正しくありません")
      _ <- Either.cond(r.periodMinutes >= 1 && r.periodMinutes <= 7 * 24 * 60, (), "対象の期間は 1分〜7日 にしてください")
      _ <- Either.cond(r.everyMinutes >= 1 && r.everyMinutes <= 24 * 60, (), "評価の間隔は 1分〜1日 にしてください")
      _ <- Either.cond(r.silenceMinutes >= 0 && r.silenceMinutes <= 7 * 24 * 60, (), "再通知の間隔は 0分〜7日 にしてください")
      stream <- if r.mode != AlertMode.Sql then Names.stream(r.stream) else Right(r.stream.trim)
      _ <- if r.mode == AlertMode.Sql then SqlText.checkQuery(r.sql.getOrElse("")).void
      else
        for {
          _ <- Either.cond(AlertAgg.all.contains(r.agg), (), "集計の方法が正しくありません")
          _ <- Either.cond(r.agg == "count" || r.field.exists(_.trim.nonEmpty), (), "集計する列を指定してください")
          _ <- r.where.filter(_.trim.nonEmpty).traverse(SqlText.checkCondition)
        } yield ()
    } yield r.copy(
      name = name,
      stream = stream,
      description = r.description.trim.take(2000),
      where = r.where.map(_.trim).filter(_.nonEmpty),
      field = r.field.map(_.trim).filter(_.nonEmpty),
      groupBy = r.groupBy.map(_.trim).filter(_.nonEmpty),
      sql = r.sql.map(_.trim).filter(_.nonEmpty),
      destinationIds = r.destinationIds.distinct,
      lookbackMinutes = Some(lookback)
    )
  }

  /** ルールの閲覧権限（対象のストリームを見られる人だけが見られる）。 */
  val access: AlertAccess = new AlertAccess(store, engine)

  /** 評価する（通知はしない）。values はすべての値、hits は条件に当てはまったもの。 */
  def evaluate(r: AlertRuleReq,
               now: Instant,
               viewer: Viewer = Viewer.system): IO[Either[String, (String, List[AlertHitDto], List[AlertHitDto])]] =
    if r.mode == AlertMode.Absence then absence(r, now, viewer) else evaluateQuery(r, now, viewer)

  /**
   * 途絶: lookback の範囲で来ていたデータ（groupBy の値ごと）の、最後の行からの分。直近 period の間に来ていなければ当てはまる。
   * groupBy がなければ、ストリーム全体で直近 period の間に1行もなければ当てはまる。
   */
  private def absence(r: AlertRuleReq,
                      now: Instant,
                      viewer: Viewer): IO[Either[String, (String, List[AlertHitDto], List[AlertHitDto])]] = {
    val end = now.getEpochSecond * 1000000L + now.getNano / 1000
    val look = r.lookbackMinutes.getOrElse(1440).toLong
    val start = end - look * 60L * 1000000L
    QueryBuilder.conditions(r.where, None, Nil).map(cs => QueryBuilder.absence(r.stream, r.groupBy, cs)) match {
      case Left(e) => IO.pure(Left(e))
      case Right(sql) =>
        // 見られないストリームは、ないストリームと同じ結果になる（engine.session が空のビューにする）
        engine.session(viewer, r.streamType, List(r.stream), start, end, Prune.constraints(r.where))(_.query(
          sql,
          10000
        )).map(_.map {
          (res, _, _) =>
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
  }

  private def evaluateQuery(r: AlertRuleReq,
                            now: Instant,
                            viewer: Viewer): IO[Either[String, (String, List[AlertHitDto], List[AlertHitDto])]] = {
    val end = now.getEpochSecond * 1000000L + now.getNano / 1000
    val start = end - r.periodMinutes.toLong * 60L * 1000000L
    val built: Either[String, (String, List[String])] =
      if r.mode == AlertMode.Sql then
        SqlText.checkQuery(r.sql.getOrElse("")).map((sql, names) => (sql, names.toList))
      else
        QueryBuilder.conditions(r.where, None, Nil).flatMap(cs =>
          QueryBuilder.alert(r.stream, r.agg, r.field, r.groupBy, cs)
        ).map(_ -> List(r.stream))
    built match {
      case Left(e) => IO.pure(Left(e))
      case Right((sql, names)) =>
        engine.referenced(viewer, r.streamType, names.toSet).flatMap {
          case Nil => IO.pure(Left("SQL の FROM にストリームの名前を書いてください"))
          case ss =>
            engine.session(
              viewer,
              r.streamType,
              ss,
              start,
              end,
              if r.mode == AlertMode.Sql then Map.empty else Prune.constraints(r.where)
            )(_.query(QueryBuilder.expand(sql, start, end), 1000)).map(_.map {
              (res, _, _) =>
                val values = AlertService.values(res)
                (sql, values.filter(v => AlertLogic.compare(r.op, v.value, r.threshold)), values)
            })
        }
    }
  }

  /** 時刻が来たルールを評価して、結果を記録する。 */
  def tick: IO[Int] =
    store.claimDue(20).flatMap(_.traverse { rule =>
      IO.realTimeInstant.flatMap { now =>
        stats.inc("skorimako_alert_evaluations_total")
        evaluate(rule.toReq, now).attempt.map(_.leftMap(e =>
          s"評価できませんでした: ${e.getClass.getSimpleName}"
        ).flatten).flatMap {
          case Left(e) =>
            // 評価できない状態は、初めてそうなったときだけ履歴に残す（通知はしない）
            val ev = Option.when(rule.state != AlertState.Error)(("error", None, Nil, e.take(500)))
            store.record(
              rule,
              EvalRecord(AlertState.Error, None, Some(e.take(1000)), notified = false, fired = false, ev)
            )
          case Right((_, hits, values)) =>
            val firing = hits.nonEmpty
            val prev = if rule.state == AlertState.Error then AlertState.Ok else rule.state
            val (state, action0) = AlertLogic.next(prev, firing, rule.lastNotifiedAt, rule.silenceMinutes, now)
            // 確認済み（対応中）の間は再通知しない
            val action = if action0 == AlertLogic.Action.Fire && prev == AlertState.Firing && rule.acked then
              AlertLogic.Action.None
            else action0
            val value = (if firing then hits else values).map(_.value).maxOption
            val event = action match {
              case AlertLogic.Action.Fire => Some((
                  "firing",
                  value,
                  hits,
                  s"${rule.name}: ${AlertService.describe(rule.mode, hits, rule.op, rule.threshold)}"
                ))
              case AlertLogic.Action.Resolve => Some(("resolved", value, Nil, s"${rule.name}: 解消しました"))
              case AlertLogic.Action.None => None
            }
            store.record(
              rule,
              EvalRecord(
                state,
                value,
                None,
                notified = event.isDefined,
                fired = action == AlertLogic.Action.Fire,
                event
              )
            ) <*
              event.filter(_ => !rule.silenced).traverse_((st, _, _, msg) => pushEvent(rule, st, msg).start)
        }
      }
    }.map(_.size))

  /** 発生・解消を、ルールを作った人と通知先（stui の DM）の人の端末にプッシュ通知で送る（ミュート中は送らない）。 */
  private def pushEvent(rule: RuleRow, state: String, message: String): IO[Unit] = {
    val (icon, label) = AlertService.label(state)
    val m = PushMessage(
      s"$icon [$label] ${rule.name}",
      message.stripPrefix(rule.name + ": "),
      s"/alerts/${rule.id}",
      Some(s"alert-${rule.id}")
    )
    store.pushRecipients(rule).flatMap(_.traverse_(push.send(_, m)))
      .handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.AlertPushFailed, e.toString, e))
  }

  def loop: IO[Nothing] =
    Periodic.run(15.seconds, 10.seconds, ErrorCode.AlertEvaluationFailed)(tick)

  // ---------------------------------------------------------------- 通知

  /** 通知先が内部のネットワークでないか（WEBHOOK_DENY_PRIVATE=true のときだけ確かめる。stui は社内に置くことが多いため既定では許す）。 */
  def allowedHost(url: String): IO[Boolean] =
    if !cfg.webhookDenyPrivate then IO.pure(true) else NetGuard.isAllowed(url, NetGuard.Policy.publicOnly)

  private def ruleUrl(id: UUID): String = s"${cfg.publicUrl}/alerts/$id"

  /** 通知の本文。stui は Markdown、Slack は mrkdwn、webhook は JSON。 */
  def payload(d: DeliveryRow): Json = {
    val hits = d.hits.as[List[AlertHitDto]].getOrElse(Nil)
    val (icon, label) = AlertService.label(d.eventState)
    val detail =
      if d.eventState == "test" then "skorimako からの通知の試験です"
      else d.message.stripPrefix(d.ruleName + ": ")
    val test = d.eventState == "test"
    val where = if test then "" else s"${StreamType.label(d.streamType)} / ${d.stream}"
    val url = if test then cfg.publicUrl else ruleUrl(d.ruleId)
    d.kind match {
      case DestinationKind.Webhook =>
        Json.obj(
          "event" -> s"alert.${d.eventState}".asJson,
          "rule" -> Json.obj(
            "id" -> d.ruleId.asJson,
            "name" -> d.ruleName.asJson,
            "op" -> d.op.asJson,
            "threshold" -> d.threshold.asJson,
            "streamType" -> d.streamType.asJson,
            "stream" -> d.stream.asJson
          ),
          "value" -> d.value.asJson,
          "hits" -> hits.asJson,
          "message" -> d.message.asJson,
          "url" -> url.asJson,
          "at" -> d.at.asJson
        )
      case DestinationKind.Slack =>
        Json.obj(
          "username" -> "skorimako".asJson,
          "text" -> s"$icon *[$label] ${d.ruleName}*\n$detail\n$where  <$url|${
              if test then "skorimako を開く" else "詳しく見る"
            }>".asJson
        )
      case _ =>
        Json.obj(
          "username" -> "skorimako".asJson,
          "text" -> s"$icon **[$label] ${d.ruleName}**\n$detail\n$where  [${
              if test then "skorimako を開く" else "詳しく見る"
            }]($url)".asJson
        )
    }
  }

  def send(d: DeliveryRow): IO[OutboxResult] =
    sendRaw(d).flatTap { r =>
      val result = r match {
        case OutboxResult.Sent => "sent"
        case _: OutboxResult.Skip => "skipped"
        case _: OutboxResult.Retry => "retry"
      }
      IO(stats.inc("skorimako_alert_notifications_total", Map("result" -> result)))
    }

  private def sendRaw(d: DeliveryRow): IO[OutboxResult] =
    if d.kind == DestinationKind.StuiDm then
      d.targetUserId match {
        case None => IO.pure(OutboxResult.Skip("宛先の人がいません"))
        case Some(u) =>
          val text = payload(d.copy(kind = DestinationKind.Stui)).hcursor.get[String]("text").getOrElse(d.message)
          stui.notify(u, text)
      }
    else
      allowedHost(d.url).flatMap {
        case false => IO.pure(OutboxResult.Skip("送信先が内部のネットワークのため送りませんでした"))
        case true =>
          val body = payload(d).noSpaces
          val sig =
            d.secret.flatMap(box.decrypt).map(k => "sha256=" + Crypto.hmacHex(k, body.getBytes(StandardCharsets.UTF_8)))
          Uri.fromString(d.url).toOption match {
            case None => IO.pure(OutboxResult.Skip("URL が正しくありません"))
            case Some(uri) =>
              // 署名した文字列をそのまま送る（circe のエンコーダーを通すと JSON の文字列として包まれてしまう）
              val req = Request[IO](Method.POST, uri).withEntity(body)(using EntityEncoder.stringEncoder)
                .withContentType(`Content-Type`(MediaType.application.json))
                .putHeaders(
                  "User-Agent" -> "skorimako-alert",
                  "X-Skorimako-Event" -> s"alert.${d.eventState}",
                  "X-Skorimako-Delivery" -> d.id.toString
                )
              val signed = sig.fold(req)(s => req.putHeaders("X-Skorimako-Signature" -> s))
              http.run(signed).use(r => IO.pure(r.status.code)).timeout(15.seconds).attempt.map {
                case Right(c) if c >= 200 && c < 300 => OutboxResult.Sent
                case Right(c) if c == 404 || c == 410 => OutboxResult.Skip(s"HTTP $c")
                case Right(c) => OutboxResult.Retry(s"HTTP $c")
                case Left(_: java.util.concurrent.TimeoutException) => OutboxResult.Retry("時間切れ")
                // 確かめた後に名前の向き先が変わったとき（http は確かめた IP にそのままつなぐ）
                case Left(_: NetGuard.Rejected) => OutboxResult.Skip("送信先が内部のネットワークのため送りませんでした")
                case Left(_) => OutboxResult.Retry("接続できませんでした")
              }
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
            val d = DeliveryRow(
              UUID.randomUUID(),
              0,
              kind,
              url,
              secret,
              "test",
              Some(1),
              Json.arr(),
              "通知の試験",
              Instant.now(),
              UUID.randomUUID(),
              "通知の試験",
              ">",
              0,
              StreamType.Logs,
              "default",
              target
            )
            send(d).map {
              case OutboxResult.Sent => Right("送りました")
              case OutboxResult.Skip(r) => Left(r)
              case OutboxResult.Retry(r) => Left(r)
            }
        }
    }

  def deliveryLoop: IO[Nothing] =
    Periodic.run(10.seconds, 5.seconds, ErrorCode.AlertDeliveryFailed)(store.processDeliveries(50)(send))
}

object AlertService {

  /** 出来事（発生・解消など）の印と名前。 */
  def label(state: String): (String, String) = state match {
    case "firing" => ("🔴", "発生")
    case "resolved" => ("✅", "解消")
    case "test" => ("🧪", "通知の試験")
    case "acknowledged" => ("👀", "対応中")
    case s => ("⚠️", s)
  }

  /** 発生のときの文言。途絶は「最後のデータから◯分」。 */
  def describe(mode: String, hits: List[AlertHitDto], op: String, threshold: Double): String =
    if mode == AlertMode.Absence then {
      val parts = hits.take(10).map(h => h.group.fold("")(g => s"$g: ") + s"${AlertLogic.fmt(h.value)}分")
      s"データが来ていません（最後のデータから ${parts.mkString("、")}${if hits.size > 10 then s" ほか${hits.size - 10}件" else ""}）"
    } else AlertLogic.describe(hits, op, threshold)

  /** 結果の各行の値: value の列（なければ最初の数値の列）。grp の列（なければ数値でない最初の列）を分け方にする。 */
  def values(res: QueryResultDto): List[AlertHitDto] = {
    val names = res.columns.map(_.name.toLowerCase)
    res.rows.flatMap { row =>
      val cells = names.zip(row)
      val v = cells.find(
        _._1 == "value"
      ).flatMap(_._2.asNumber).orElse(cells.flatMap(_._2.asNumber).headOption).map(_.toDouble)
      val g =
        cells.find(_._1 == "grp").map(_._2).orElse(cells.find(c => c._2.asNumber.isEmpty && !c._2.isNull).map(_._2))
          .map(QueryEngine.text)
      v.map(AlertHitDto(g, _))
    }
  }
}
