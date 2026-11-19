package skorimako.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import skorimako.shared.*

import java.time.Instant
import java.util.UUID

/** アラートのルール（DB の行）。 */
final case class RuleRow(
  id: UUID,
  name: String,
  description: String,
  enabled: Boolean,
  streamType: String,
  stream: String,
  mode: String,
  where: Option[String],
  agg: String,
  field: Option[String],
  groupBy: Option[String],
  sql: Option[String],
  op: String,
  threshold: Double,
  periodMinutes: Int,
  everyMinutes: Int,
  silenceMinutes: Int,
  destinationIds: List[UUID],
  state: String,
  lastValue: Option[Double],
  lastEvalAt: Option[Instant],
  lastNotifiedAt: Option[Instant],
  lastFiredAt: Option[Instant],
  lastError: Option[String],
  createdById: Option[UUID],
  createdByName: Option[String],
  createdByAvatar: Option[String],
  createdAt: Instant,
  lookbackMinutes: Int,
  ackById: Option[UUID],
  ackByName: Option[String],
  ackAt: Option[Instant],
  ackNote: Option[String],
  silencedUntil: Option[Instant]) {
  def toDto: AlertRuleDto = AlertRuleDto(
    id,
    name,
    description,
    enabled,
    streamType,
    stream,
    mode,
    where,
    agg,
    field,
    groupBy,
    sql,
    op,
    threshold,
    periodMinutes,
    everyMinutes,
    silenceMinutes,
    destinationIds,
    state,
    lastValue,
    lastEvalAt,
    lastFiredAt,
    lastError,
    (createdById, createdByName).mapN(
      (
        i,
        n
      ) => UserBrief(i, n, createdByAvatar)
    ),
    createdAt,
    lookbackMinutes,
    ackAt.map(at =>
      AckDto(
        (ackById, ackByName).mapN(
          (
            i,
            n
          ) => UserBrief(i, n, None)
        ),
        at,
        ackNote.getOrElse("")
      )
    ),
    silencedUntil
  )
  def acked: Boolean = ackAt.isDefined
  def silenced: Boolean = silencedUntil.isDefined
  def toReq: AlertRuleReq = AlertRuleReq(
    name,
    description,
    enabled,
    streamType,
    stream,
    mode,
    where,
    agg,
    field,
    groupBy,
    sql,
    op,
    threshold,
    periodMinutes,
    everyMinutes,
    silenceMinutes,
    destinationIds,
    Some(lookbackMinutes)
  )
}

/** 評価の結果の記録。event があれば履歴に残し、通知先へ送る。 */
final case class EvalRecord(
  state: String,
  value: Option[Double],
  error: Option[String],
  notified: Boolean,
  fired: Boolean,
  event: Option[(String, Option[Double], List[AlertHitDto], String)])

/** 送信待ちの通知。 */
final case class DeliveryRow(
  id: UUID,
  attempts: Int,
  kind: String,
  url: String,
  secret: Option[String],
  eventState: String,
  value: Option[Double],
  hits: Json,
  message: String,
  at: Instant,
  ruleId: UUID,
  ruleName: String,
  op: String,
  threshold: Double,
  streamType: String,
  stream: String,
  targetUserId: Option[UUID])

final class AlertStore(
  xa: Transactor[IO]) extends TxStore(xa) {

  private val ruleCols =
    fr"""r.id, r.name, r.description, r.enabled, r.stream_type, r.stream, r.mode, r.where_expr, r.agg, r.field, r.group_by,
    r.sql_text, r.op, r.threshold, r.period_minutes, r.every_minutes, r.silence_minutes, r.destination_ids, r.state, r.last_value,
    r.last_eval_at, r.last_notified_at, r.last_fired_at, r.last_error, u.id, u.name, u.avatar_url, r.created_at, r.lookback_minutes,
    r.ack_by, a.name, r.ack_at, r.ack_note,
    (select max(x.ends_at) from alert_silence x where (x.rule_id = r.id or x.rule_id is null) and x.starts_at <= now() and x.ends_at > now())"""
  private val ruleFrom =
    fr"from alert_rule r left join app_user u on u.id = r.created_by left join app_user a on a.id = r.ack_by"

  // ---------------------------------------------------------------- ルール

  def rules: IO[List[RuleRow]] = tx((fr"select" ++ ruleCols ++ ruleFrom ++ fr"order by r.name").query[RuleRow].to[List])

  def firing: IO[List[RuleRow]] = tx(
    (fr"select" ++ ruleCols ++ ruleFrom ++
      fr"where r.enabled and r.state <> 'ok' order by r.last_fired_at desc nulls last").query[RuleRow].to[List]
  )

  def rule(
    id: UUID
  ): IO[Option[RuleRow]] = tx((fr"select" ++ ruleCols ++ ruleFrom ++ fr"where r.id = $id").query[RuleRow].option)

  def create(
    r: AlertRuleReq,
    by: UUID
  ): IO[UUID] = {
    val id = UUID.randomUUID()
    tx(
      sql"""insert into alert_rule (id, name, description, enabled, stream_type, stream, mode, where_expr, agg, field, group_by, sql_text, op,
              threshold, period_minutes, every_minutes, silence_minutes, destination_ids, created_by, lookback_minutes)
            values ($id, ${r.name}, ${r.description}, ${r.enabled}, ${r.streamType}, ${r.stream}, ${r.mode}, ${r.where}, ${r.agg}, ${r.field},
              ${r.groupBy}, ${r.sql}, ${r.op}, ${r.threshold}, ${r.periodMinutes}, ${r.everyMinutes}, ${r.silenceMinutes},
              ${r.destinationIds.toArray}, $by, ${r.lookbackMinutes.getOrElse(1440)})""".update.run
    ).as(id)
  }

  /** 変える（条件が変わるので、次の評価をすぐにする）。 */
  def update(
    id: UUID,
    r: AlertRuleReq
  ): IO[Boolean] = tx(
    sql"""update alert_rule set name = ${r.name}, description = ${r.description}, enabled = ${r.enabled}, stream_type = ${r.streamType},
            stream = ${r.stream}, mode = ${r.mode}, where_expr = ${r.where}, agg = ${r.agg}, field = ${r.field}, group_by = ${r.groupBy},
            sql_text = ${r.sql}, op = ${r.op}, threshold = ${r.threshold}, period_minutes = ${r.periodMinutes}, every_minutes = ${r.everyMinutes},
            silence_minutes = ${r.silenceMinutes}, destination_ids = ${r.destinationIds.toArray}, next_eval_at = now(), updated_at = now(),
            lookback_minutes = ${r.lookbackMinutes.getOrElse(1440)},
            state = case when ${r.enabled} then state else 'ok' end
          where id = $id""".update.run.map(_ > 0)
  )

  def delete(
    id: UUID
  ): IO[Boolean] = tx(sql"delete from alert_rule where id = $id".update.run.map(_ > 0))

  /** 評価の時刻が来たルールを取る（次の評価の時刻を先に進めておくので、ほかのノードと二重に評価しない）。 */
  def claimDue(
    limit: Int
  ): IO[List[RuleRow]] = tx(
    sql"""update alert_rule set next_eval_at = now() + make_interval(mins => every_minutes)
          where id in (select id from alert_rule where enabled and next_eval_at <= now() order by next_eval_at limit $limit for update skip locked)
          returning id""".query[UUID].to[List].flatMap { ids =>
      if ids.isEmpty then Nil.pure[ConnectionIO]
      else (fr"select" ++ ruleCols ++ ruleFrom ++ fr"where r.id = any(${ids.toArray})").query[RuleRow].to[List]
    }
  )

  /** 評価の結果を記録する。 */
  def record(
    rule: RuleRow,
    rec: EvalRecord
  ): IO[Option[UUID]] = tx(
    for {
      _ <-
        sql"""update alert_rule set state = ${rec.state}, last_value = ${rec.value}, last_error = ${rec.error}, last_eval_at = now(),
                   last_notified_at = case when ${rec.notified} then now() else last_notified_at end,
                   last_fired_at = case when ${rec.fired} then now() else last_fired_at end,
                   ack_by = case when ${rec.state} = 'ok' then null else ack_by end,
                   ack_at = case when ${rec.state} = 'ok' then null else ack_at end,
                   ack_note = case when ${rec.state} = 'ok' then null else ack_note end
                 where id = ${rule.id}""".update.run
      ev <- rec.event.traverse(
        (
          state,
          value,
          hits,
          msg
        ) => addEvent(rule, state, value, hits, msg)
      )
    } yield ev
  )

  /** 履歴に残し、ミュート中でなければ通知先ごとに送信待ちを作る。 */
  private def addEvent(
    rule: RuleRow,
    state: String,
    value: Option[Double],
    hits: List[AlertHitDto],
    msg: String
  ): ConnectionIO[UUID] = {
    val eid = UUID.randomUUID()
    for {
      silenced <- sql"""select exists(select 1 from alert_silence where (rule_id = ${rule.id} or rule_id is null)
                        and starts_at <= now() and ends_at > now())""".query[Boolean].unique
      _ <- sql"""insert into alert_event (id, rule_id, state, value, hits, message, silenced)
                 values ($eid, ${rule.id}, $state, $value, ${hits.asJson}, $msg, $silenced)""".update.run
      _ <- if state == "error" || silenced then 0.pure[ConnectionIO]
      else
        sql"""insert into alert_delivery (id, event_id, destination_id)
                      select gen_random_uuid(), $eid, d.id from alert_destination d where d.id = any(${rule.destinationIds.toArray})""".update.run
    } yield eid
  }

  /** 確認済み（対応中）にする。通知先にも知らせる。発生中でなければ false。 */
  def ack(
    ruleId: UUID,
    by: UUID,
    byName: String,
    note: String
  ): IO[Boolean] = tx(
    (fr"select" ++ ruleCols ++ ruleFrom ++ fr"where r.id = $ruleId for update of r").query[RuleRow].option.flatMap {
      case Some(r) if r.state == "firing" =>
        sql"update alert_rule set ack_by = $by, ack_at = now(), ack_note = $note where id = $ruleId".update.run *>
          addEvent(
            r,
            "acknowledged",
            r.lastValue,
            Nil,
            s"${r.name}: $byName が対応中です" + (if note.trim.isEmpty then "" else s"（${note.trim}）")
          ).as(true)
      case _ => false.pure[ConnectionIO]
    }
  )

  def unack(
    ruleId: UUID
  ): IO[Boolean] = tx(
    sql"update alert_rule set ack_by = null, ack_at = null, ack_note = null where id = $ruleId and ack_at is not null".update.run.map(
      _ > 0
    )
  )

  // ---------------------------------------------------------------- ミュート

  def silences(
    includeEnded: Boolean
  ): IO[List[SilenceDto]] = tx(
    (fr"""select x.id, x.rule_id, r.name, x.reason, x.starts_at, x.ends_at, u.id, u.name, u.avatar_url
          from alert_silence x left join alert_rule r on r.id = x.rule_id left join app_user u on u.id = x.created_by""" ++
      (if includeEnded then fr"where x.ends_at > now() - interval '7 days'" else fr"where x.ends_at > now()") ++
      fr"order by x.starts_at desc limit 500")
      .query[(
        UUID,
        Option[UUID],
        Option[String],
        String,
        Instant,
        Instant,
        Option[UUID],
        Option[String],
        Option[String]
      )]
      .map(
        (
          id,
          rid,
          rn,
          re,
          st,
          en,
          uid,
          un,
          ua
        ) =>
          SilenceDto(
            id,
            rid,
            rn,
            re,
            st,
            en,
            (uid, un).mapN(
              (
                i,
                n
              ) => UserBrief(i, n, ua)
            )
          )
      ).to[List]
  )

  def createSilence(
    ruleId: Option[UUID],
    reason: String,
    starts: Instant,
    ends: Instant,
    by: UUID
  ): IO[UUID] = {
    val id = UUID.randomUUID()
    tx(sql"""insert into alert_silence (id, rule_id, reason, starts_at, ends_at, created_by) values ($id, $ruleId, $reason, $starts, $ends, $by)"""
      .update.run).as(id)
  }

  /** 終わらせる（今で切る。始まる前なら消す）。 */
  def endSilence(
    id: UUID
  ): IO[Boolean] = tx(
    sql"delete from alert_silence where id = $id and starts_at > now()".update.run.flatMap(n =>
      if n > 0 then true.pure[ConnectionIO]
      else sql"update alert_silence set ends_at = now() where id = $id and ends_at > now()".update.run.map(_ > 0)
    )
  )

  def events(
    ruleId: Option[UUID],
    limit: Int
  ): IO[List[AlertEventDto]] = tx(
    (fr"""select e.id, e.rule_id, r.name, e.state, e.value, e.hits, e.message, e.at, e.silenced from alert_event e join alert_rule r on r.id = e.rule_id""" ++
      Sql.whereAnd(ruleId.map(id => fr"e.rule_id = $id").toList) ++ fr"order by e.at desc limit $limit")
      .query[(UUID, UUID, String, String, Option[Double], Json, String, Instant, Boolean)]
      .map(
        (
          id,
          rid,
          n,
          s,
          v,
          h,
          m,
          at,
          si
        ) => AlertEventDto(id, rid, n, s, v, h.as[List[AlertHitDto]].getOrElse(Nil), m, at, si)
      ).to[List]
  )

  def purgeEvents(
    days: Int
  ): IO[Int] = tx(
    sql"delete from alert_event where at < now() - make_interval(days => $days)".update.run
  )

  // ---------------------------------------------------------------- 通知先

  private val destSql =
    fr"""select d.id, d.name, d.kind, d.url, d.secret is not null, d.created_at, u.id, u.name, u.avatar_url
    from alert_destination d left join app_user u on u.id = d.target_user_id"""
  private type DestRow = (UUID, String, String, String, Boolean, Instant, Option[UUID], Option[String], Option[String])
  private def destDto(
    r: DestRow
  ) = DestinationDto(
    r._1,
    r._2,
    r._3,
    r._4,
    r._5,
    r._6,
    (r._7, r._8).mapN(
      (
        i,
        n
      ) => UserBrief(i, n, r._9)
    )
  )

  def destinations: IO[List[DestinationDto]] = tx((destSql ++ fr"order by d.name").query[DestRow].map(destDto).to[List])

  def destination(
    id: UUID
  ): IO[Option[DestinationDto]] = tx((destSql ++ fr"where d.id = $id").query[DestRow].map(destDto).option)

  /** 送るための内容（種類・URL・暗号化した署名の鍵）。 */
  def destinationRow(
    id: UUID
  ): IO[Option[(String, String, Option[String], Option[UUID])]] = tx(
    sql"select kind, url, secret, target_user_id from alert_destination where id = $id".query[(
      String,
      String,
      Option[String],
      Option[UUID]
    )].option
  )

  /** アラートのプッシュ通知を受け取る人: ルールを作った人と、通知先（stui の DM）の宛先の人。 */
  def pushRecipients(
    rule: RuleRow
  ): IO[List[UUID]] =
    tx(sql"""select target_user_id from alert_destination
             where id = any(${rule.destinationIds.toArray}) and kind = ${DestinationKind.StuiDm} and target_user_id is not null"""
      .query[UUID].to[List]).map(us => (rule.createdById.toList ++ us).distinct)

  def createDestination(
    r: DestinationReq,
    secret: Option[String],
    by: UUID
  ): IO[UUID] = {
    val id = UUID.randomUUID()
    tx(sql"""insert into alert_destination (id, name, kind, url, secret, created_by, target_user_id)
             values ($id, ${r.name}, ${r.kind}, ${r.url}, $secret, $by, ${r.targetUserId})""".update.run)
      .as(id)
  }

  /** secret: None は変えない、Some(None) は外す。 */
  def updateDestination(
    id: UUID,
    r: DestinationReq,
    secret: Option[Option[String]]
  ): IO[Boolean] = tx {
    val sets =
      List(fr"name = ${r.name}", fr"kind = ${r.kind}", fr"url = ${r.url}", fr"target_user_id = ${r.targetUserId}") ++
        secret.map(s => fr"secret = $s").toList
    (fr"update alert_destination" ++ Sql.set(sets) ++ fr"where id = $id").update.run.map(_ > 0)
  }

  def deleteDestination(
    id: UUID
  ): IO[Boolean] = tx(
    sql"update alert_rule set destination_ids = array_remove(destination_ids, $id)".update.run *>
      sql"delete from alert_destination where id = $id".update.run.map(_ > 0)
  )

  // ---------------------------------------------------------------- 送信

  /** 送信待ちを取り出して送り、結果を記録する（取り出しから記録までを1つのトランザクションで。ほかのノードは SKIP LOCKED で飛ばす）。 */
  def processDeliveries(
    limit: Int
  )(
    send: DeliveryRow => IO[OutboxResult]
  ): IO[Int] =
    processEach(
      sql"""select d.id, d.attempts, x.kind, x.url, x.secret, e.state, e.value, e.hits, e.message, e.at, r.id, r.name, r.op, r.threshold,
                   r.stream_type, r.stream, x.target_user_id
            from alert_delivery d join alert_event e on e.id = d.event_id join alert_rule r on r.id = e.rule_id
            join alert_destination x on x.id = d.destination_id
            where d.status = 'pending' and d.next_at <= now() order by d.next_at limit $limit for update of d skip locked""".query[
        DeliveryRow
      ].to[List]
    )(send) {
      (
        d,
        res
      ) =>
        res match {
          case OutboxResult.Sent =>
            sql"update alert_delivery set status = 'sent', attempts = attempts + 1, sent_at = now(), last_error = null where id = ${d.id}".update.run
          case OutboxResult.Skip(r) =>
            sql"update alert_delivery set status = 'skipped', attempts = attempts + 1, last_error = $r where id = ${d.id}".update.run
          case OutboxResult.Retry(e) =>
            if d.attempts + 1 >= 8 then
              sql"update alert_delivery set status = 'failed', attempts = attempts + 1, last_error = $e where id = ${d.id}".update.run
            else {
              val secs = Outbox.backoffSeconds(d.attempts)
              sql"""update alert_delivery set attempts = attempts + 1, last_error = $e, next_at = now() + make_interval(secs => $secs)
                  where id = ${d.id}""".update.run
            }
        }
    }

  def deliveries(
    limit: Int
  ): IO[List[DeliveryDto]] = tx(
    sql"""select d.id, x.name, r.name, e.state, d.status, d.attempts, d.last_error, d.created_at, d.sent_at
          from alert_delivery d join alert_event e on e.id = d.event_id join alert_rule r on r.id = e.rule_id
          join alert_destination x on x.id = d.destination_id order by d.created_at desc limit $limit""".query[
      DeliveryDto
    ].to[List]
  )

  def purgeDeliveries(
    days: Int
  ): IO[Int] = tx(
    sql"delete from alert_delivery where status <> 'pending' and created_at < now() - make_interval(days => $days)".update.run
  )
}
