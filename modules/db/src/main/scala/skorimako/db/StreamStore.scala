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

import java.time.{Instant, LocalDate}
import java.util.UUID

/** allowedGroups は閲覧できるグループ（空なら全員）、maxRowsPerMinute は取り込みの上限。 */
final case class StreamRow(id: UUID, streamType: String, name: String, retentionDays: Option[Int], allowedGroups: List[String] = Nil,
    maxRowsPerMinute: Option[Int] = None)

/** tags は文字列の列ごとの値の一覧（種類が少ない列だけ。列 = 値 の条件で読まないファイルを決める）。 */
final case class FileRow(id: Long, streamId: UUID, key: String, minTs: Long, maxTs: Long, rows: Long, size: Long, level: Int,
    tags: Map[String, List[String]] = Map.empty)

/** 取り込んだファイルの登録の内容。 */
final case class NewFile(streamId: UUID, key: String, minTs: Long, maxTs: Long, rows: Long, size: Long, level: Int, node: String,
    tags: Map[String, List[String]] = Map.empty)

/** トレースの索引に足す内容（1回の書き出しの中のトレースごと）。 */
final case class TracePart(traceId: String, minTs: Long, maxTs: Long, rootService: String, rootName: String, services: List[String],
    spans: Int, errors: Int)

/** ストリーム・列の型・データのファイル・トレースの索引・取り込みの量。 */
final class StreamStore(xa: Transactor[IO]) extends TxStore(xa):

  private val dtoCols = fr"""s.id, s.name, s.stream_type, s.retention_days, s.doc_count, s.stored_bytes, s.file_count, s.min_ts, s.max_ts,
                             s.description, s.unit, s.metric_type, s.updated_at, s.allowed_groups, s.max_rows_per_minute"""

  private def toDto(defaultRetention: Int)(r: DtoRow) =
    val (id, n, t, ret, dc, sb, fc, mi, ma, d, u, mt, up, ag, mr) = r
    StreamDto(id, n, t, ret.getOrElse(defaultRetention), dc, sb, fc, mi, ma, d, u, mt, up, ag, mr)

  private type DtoRow = (UUID, String, String, Option[Int], Long, Long, Long, Option[Long], Option[Long], String, String, String, Instant, List[String],
    Option[Int])

  private val rowCols = fr"id, stream_type, name, retention_days, allowed_groups, max_rows_per_minute"


  // ---------------------------------------------------------------- ストリーム

  /** なければ作る。 */
  def ensure(streamType: String, name: String): IO[StreamRow] = tx(
    sql"""insert into stream (id, stream_type, name) values (${UUID.randomUUID()}, $streamType, $name)
          on conflict (stream_type, name) do nothing""".update.run *>
      (fr"select" ++ rowCols ++ fr"from stream where stream_type = $streamType and name = $name").query[StreamRow].unique
  )

  def find(streamType: String, name: String): IO[Option[StreamRow]] = tx(
    (fr"select" ++ rowCols ++ fr"from stream where stream_type = $streamType and name = $name").query[StreamRow].option
  )

  def byId(id: UUID): IO[Option[StreamRow]] = tx(
    (fr"select" ++ rowCols ++ fr"from stream where id = $id").query[StreamRow].option
  )

  def all: IO[List[StreamRow]] = tx((fr"select" ++ rowCols ++ fr"from stream").query[StreamRow].to[List])

  def names(streamType: String): IO[List[String]] = tx(
    sql"select name from stream where stream_type = $streamType order by name".query[String].to[List]
  )

  def list(streamType: Option[String], q: Option[String], defaultRetention: Int, limit: Int = 5000): IO[List[StreamDto]] = tx {
    val where = List(streamType.map(t => fr"s.stream_type = $t"), q.map(_.trim).filter(_.nonEmpty).map(x => fr"s.name ilike ${"%" + x + "%"}")).flatten
    (fr"select" ++ dtoCols ++ fr"from stream s" ++ Sql.whereAnd(where) ++ fr"order by s.stream_type, s.name limit $limit")
      .query[DtoRow].map(toDto(defaultRetention)).to[List]
  }

  def dto(id: UUID, defaultRetention: Int): IO[Option[StreamDto]] = tx(
    (fr"select" ++ dtoCols ++ fr"from stream s where s.id = $id").query[DtoRow].map(toDto(defaultRetention)).option
  )

  def update(id: UUID, retentionDays: Option[Option[Int]], description: Option[String], allowedGroups: Option[List[String]] = None,
      maxRows: Option[Option[Int]] = None): IO[Unit] = tx {
    val sets = List(retentionDays.map(r => fr"retention_days = $r"), description.map(d => fr"description = ${d.take(1000)}"),
      allowedGroups.map(g => fr"allowed_groups = ${g.toArray}"), maxRows.map(m => fr"max_rows_per_minute = $m")).flatten
    if sets.isEmpty then ().pure[ConnectionIO] else (fr"update stream" ++ Sql.set(fr"updated_at = now()" :: sets) ++ fr"where id = $id").update.run.void
  }

  /** メトリクスの説明・単位・種類（取り込みのたびに最新にする。空なら変えない）。 */
  def setMetricMeta(id: UUID, description: String, unit: String, metricType: String): IO[Unit] = tx(
    sql"""update stream set description = case when $description <> '' then $description else description end,
            unit = case when $unit <> '' then $unit else unit end,
            metric_type = case when $metricType <> '' then $metricType else metric_type end
          where id = $id and (description <> $description or unit <> $unit or metric_type <> $metricType)""".update.run.void
  )

  /** ストリームを消す（ファイルは削除の印を付けて後で S3 から消す）。 */
  def delete(id: UUID): IO[List[String]] = tx(
    for
      keys <- sql"select s3_key from data_file where stream_id = $id".query[String].to[List]
      _    <- sql"delete from stream where id = $id".update.run
    yield keys
  )

  // ---------------------------------------------------------------- 列の型

  def fields(streamId: UUID): IO[List[FieldDto]] = tx(
    sql"select name, field_type from stream_field where stream_id = $streamId order by name".query[FieldDto].to[List]
  )

  /** 列の型を登録し、確定した型を返す（すでにあれば食い違いを広げる: 整数と小数は小数、ほかは文字列）。 */
  def mergeFields(streamId: UUID, inferred: Map[String, String]): IO[Map[String, String]] =
    if inferred.isEmpty then IO.pure(Map.empty)
    else tx {
      val names = inferred.keys.toList
      val ins = Update[(UUID, String, String)](
        """insert into stream_field (stream_id, name, field_type) values (?, ?, ?)
           on conflict (stream_id, name) do update set field_type =
             case when stream_field.field_type = excluded.field_type then stream_field.field_type
                  when stream_field.field_type in ('int64', 'float64') and excluded.field_type in ('int64', 'float64') then 'float64'
                  else 'utf8' end
           where stream_field.field_type <> excluded.field_type""")
      ins.updateMany(inferred.toList.sortBy(_._1).map((n, t) => (streamId, n, t))) *>
        sql"select name, field_type from stream_field where stream_id = $streamId and name = any(${names.toArray})".query[(String, String)].to[List].map(_.toMap)
    }

  // ---------------------------------------------------------------- ファイル

  /** 取り込んだファイルを登録し、ストリームの統計と取り込みの量を増やす。 */
  def addFile(f: NewFile, traces: List[TracePart]): IO[Unit] = tx(
    insertFile(f) *> bump(f.streamId, f.rows, f.size, 1, f.minTs, f.maxTs) *>
      sql"""insert into ingest_stat (day, stream_id, rows, bytes) values (${LocalDate.now()}, ${f.streamId}, ${f.rows}, ${f.size})
            on conflict (day, stream_id) do update set rows = ingest_stat.rows + excluded.rows, bytes = ingest_stat.bytes + excluded.bytes""".update.run *>
      upsertTraces(f.streamId, traces).void
  )

  private def insertFile(f: NewFile): ConnectionIO[Int] =
    sql"""insert into data_file (stream_id, s3_key, min_ts, max_ts, row_count, size_bytes, level, node, tags)
          values (${f.streamId}, ${f.key}, ${f.minTs}, ${f.maxTs}, ${f.rows}, ${f.size}, ${f.level}, ${f.node}, ${f.tags.asJson})""".update.run

  private def bump(id: UUID, rows: Long, size: Long, files: Long, minTs: Long, maxTs: Long): ConnectionIO[Int] =
    sql"""update stream set doc_count = doc_count + $rows, stored_bytes = stored_bytes + $size, file_count = file_count + $files,
            min_ts = least(coalesce(min_ts, $minTs), $minTs), max_ts = greatest(coalesce(max_ts, $maxTs), $maxTs), updated_at = now()
          where id = $id""".update.run

  private def fileRow(r: (Long, UUID, String, Long, Long, Long, Long, Int, Json)): FileRow =
    FileRow(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8, r._9.as[Map[String, List[String]]].getOrElse(Map.empty))

  /** 時間の範囲に重なるファイル。 */
  def filesFor(streamIds: List[UUID], start: Long, end: Long): IO[List[FileRow]] =
    if streamIds.isEmpty then IO.pure(Nil)
    else tx(
      sql"""select id, stream_id, s3_key, min_ts, max_ts, row_count, size_bytes, level, tags from data_file
            where stream_id = any(${streamIds.toArray}) and deleted_at is null and max_ts >= $start and min_ts <= $end
            order by min_ts""".query[(Long, UUID, String, Long, Long, Long, Long, Int, Json)].map(fileRow).to[List]
    )

  /** 圧縮の候補: 書いてから minAge 秒たった小さいファイルを、ストリーム・時間（hourMicros ごと）で束ねる。 */
  def compactionGroups(minAgeSeconds: Int, maxBytes: Long, limit: Int): IO[List[List[FileRow]]] = tx(
    sql"""select id, stream_id, s3_key, min_ts, max_ts, row_count, size_bytes, level, tags from data_file
          where deleted_at is null and size_bytes < $maxBytes and created_at < now() - make_interval(secs => $minAgeSeconds)
          order by stream_id, min_ts limit 20000""".query[(Long, UUID, String, Long, Long, Long, Long, Int, Json)].map(fileRow).to[List]
  ).map { fs =>
    fs.groupBy(f => (f.streamId, f.minTs / 3600000000L)).values.toList
      .filter(_.size >= 2)
      .map { g =>
        // まとめた結果が maxBytes を超えないところまで
        g.sortBy(_.minTs).foldLeft((List.empty[FileRow], 0L)) { case ((acc, total), f) =>
          if total + f.size > maxBytes then (acc, total) else (f :: acc, total + f.size)
        }._1.reverse
      }
      .filter(_.size >= 2)
      .take(limit)
  }

  /** 圧縮したファイルに置き換える（元のファイルには削除の印を付ける。すでに消えていたら何もしない）。 */
  def replaceFiles(old: List[FileRow], f: NewFile): IO[Boolean] = tx(
    sql"""update data_file set deleted_at = now() where id = any(${old.map(_.id).toArray}) and deleted_at is null""".update.run.flatMap { n =>
      if n != old.size then FC.rollback.as(false)
      else insertFile(f) *> bump(f.streamId, f.rows - old.map(_.rows).sum, f.size - old.map(_.size).sum, 1 - old.size, f.minTs, f.maxTs).as(true)
    }
  )

  /** 保持期間を過ぎたファイルに削除の印を付け、統計を引く。古いトレースの索引も消す。 */
  def expire(defaultRetention: Int, nowMicros: Long): IO[Int] = tx(
    for
      ss <- sql"select id, coalesce(retention_days, $defaultRetention) from stream".query[(UUID, Int)].to[List]
      ns <- ss.traverse { (id, days) =>
        val limit = nowMicros - days.toLong * 86400L * 1000000L
        for
          gone <- sql"""update data_file set deleted_at = now() where stream_id = $id and deleted_at is null and max_ts < $limit
                        returning row_count, size_bytes""".query[(Long, Long)].to[List]
          _ <- if gone.isEmpty then 0.pure[ConnectionIO]
               else sql"""update stream set doc_count = greatest(0, doc_count - ${gone.map(_._1).sum}),
                            stored_bytes = greatest(0, stored_bytes - ${gone.map(_._2).sum}), file_count = greatest(0, file_count - ${gone.size.toLong}),
                            min_ts = (select min(min_ts) from data_file where stream_id = $id and deleted_at is null)
                          where id = $id""".update.run
          _ <- sql"delete from trace_index where stream_id = $id and max_ts < $limit".update.run
        yield gone.size
      }
      _ <- sql"delete from ingest_stat where day < current_date - 400".update.run
    yield ns.sum
  )

  /** 削除の印を付けてから graceSeconds たったファイル（S3 から消してから forget で行を消す）。 */
  def deletedFiles(graceSeconds: Int, limit: Int): IO[List[(Long, String)]] = tx(
    sql"""select id, s3_key from data_file where deleted_at is not null and deleted_at < now() - make_interval(secs => $graceSeconds)
          order by deleted_at limit $limit""".query[(Long, String)].to[List]
  )

  def forget(ids: List[Long]): IO[Unit] =
    if ids.isEmpty then IO.unit else tx(sql"delete from data_file where id = any(${ids.toArray})".update.run.void)

  /** 今日の取り込みの行数。 */
  def ingestedToday: IO[Long] = tx(sql"select coalesce(sum(rows), 0)::bigint from ingest_stat where day = ${LocalDate.now()}".query[Long].unique)

  // ---------------------------------------------------------------- トレースの索引

  private def upsertTraces(streamId: UUID, ts: List[TracePart]): ConnectionIO[Int] =
    if ts.isEmpty then 0.pure[ConnectionIO]
    else Update[(UUID, String, Long, Long, String, String, List[String], Int, Int)](
      """insert into trace_index (stream_id, trace_id, min_ts, max_ts, root_service, root_name, services, span_count, error_count)
         values (?, ?, ?, ?, ?, ?, ?, ?, ?)
         on conflict (stream_id, trace_id) do update set
           min_ts = least(trace_index.min_ts, excluded.min_ts), max_ts = greatest(trace_index.max_ts, excluded.max_ts),
           root_service = case when excluded.root_name <> '' then excluded.root_service else trace_index.root_service end,
           root_name = case when excluded.root_name <> '' then excluded.root_name else trace_index.root_name end,
           services = array(select distinct x from unnest(trace_index.services || excluded.services) x order by x),
           span_count = trace_index.span_count + excluded.span_count, error_count = trace_index.error_count + excluded.error_count"""
    ).updateMany(ts.map(t => (streamId, t.traceId, t.minTs, t.maxTs, t.rootService, t.rootName, t.services, t.spans, t.errors)))

  def searchTraces(r: TraceSearchReq, streamIds: List[(UUID, String)], limit: Int): IO[List[TraceSummaryDto]] =
    if streamIds.isEmpty then IO.pure(Nil)
    else tx {
      val names = streamIds.toMap
      val where = List(
        Some(fr"t.stream_id = any(${streamIds.map(_._1).toArray})"),
        Some(fr"t.min_ts >= ${r.start} and t.min_ts <= ${r.end}"),
        r.service.map(_.trim).filter(_.nonEmpty).map(s => fr"${s} = any(t.services)"),
        r.name.map(_.trim).filter(_.nonEmpty).map(n => fr"t.root_name ilike ${"%" + n + "%"}"),
        r.minDurationMs.filter(_ > 0).map(ms => fr"(t.max_ts - t.min_ts) >= ${(ms * 1000).toLong}"),
        Option.when(r.errorsOnly.contains(true))(fr"t.error_count > 0")
      ).flatten
      (fr"""select t.stream_id, t.trace_id, t.root_service, t.root_name, t.min_ts, t.max_ts - t.min_ts, t.span_count, t.error_count, t.services
            from trace_index t""" ++ Sql.whereAnd(where) ++ fr"order by t.min_ts desc limit $limit")
        .query[(UUID, String, String, String, Long, Long, Int, Int, List[String])]
        .map((sid, id, svc, n, st, d, sc, ec, ss) => TraceSummaryDto(id, names.getOrElse(sid, ""), svc, n, st, d, sc, ec, ss)).to[List]
    }

  /** トレース ID から、ストリームと時間の範囲。 */
  def findTrace(traceId: String): IO[List[(UUID, Long, Long)]] = tx(
    sql"select stream_id, min_ts, max_ts from trace_index where trace_id = $traceId".query[(UUID, Long, Long)].to[List]
  )

  def services(streamIds: List[UUID], start: Long, end: Long): IO[List[ServiceDto]] =
    if streamIds.isEmpty then IO.pure(Nil)
    else tx(
      sql"""select s, count(*)::bigint, count(*) filter (where error_count > 0)::bigint
            from trace_index, unnest(services) s where stream_id = any(${streamIds.toArray}) and min_ts >= $start and min_ts <= $end
            group by s order by s limit 1000""".query[ServiceDto].to[List]
    )

  // ---------------------------------------------------------------- ノード

  /** 自分を登録する（30秒ごと）。 */
  def heartbeat(id: String, url: String): IO[Unit] = tx(
    sql"""insert into node (id, url, last_seen) values ($id, $url, now())
          on conflict (id) do update set url = excluded.url, last_seen = now()""".update.run *>
      sql"delete from node where last_seen < now() - interval '1 day'".update.run.void
  )

  /** 直近に動いていたほかのノード（id, url）。 */
  def peers(self: String): IO[List[(String, String)]] = tx(
    sql"select id, url from node where id <> $self and last_seen > now() - interval '90 seconds' order by id".query[(String, String)].to[List]
  )

  def leaveNode(id: String): IO[Unit] = tx(sql"delete from node where id = $id".update.run.void)

  // ---------------------------------------------------------------- 取り込みの変換・上限

  def ingestRules: IO[List[IngestRuleDto]] = tx(
    sql"""select id, stream_type, stream, position, kind, field, pattern, replacement, ratio, enabled from ingest_rule
          order by stream_type, stream, position, created_at""".query[IngestRuleDto].to[List]
  )

  def createIngestRule(r: IngestRuleReq): IO[UUID] =
    val id = UUID.randomUUID()
    tx(sql"""insert into ingest_rule (id, stream_type, stream, position, kind, field, pattern, replacement, ratio, enabled)
             values ($id, ${r.streamType}, ${r.stream}, ${r.position}, ${r.kind}, ${r.field}, ${r.pattern}, ${r.replacement}, ${r.ratio}, ${r.enabled})"""
      .update.run).as(id)

  def updateIngestRule(id: UUID, r: IngestRuleReq): IO[Boolean] = tx(
    sql"""update ingest_rule set stream_type = ${r.streamType}, stream = ${r.stream}, position = ${r.position}, kind = ${r.kind}, field = ${r.field},
            pattern = ${r.pattern}, replacement = ${r.replacement}, ratio = ${r.ratio}, enabled = ${r.enabled} where id = $id""".update.run.map(_ > 0)
  )

  def deleteIngestRule(id: UUID): IO[Boolean] = tx(sql"delete from ingest_rule where id = $id".update.run.map(_ > 0))

  /** 取り込みの上限のあるストリーム（種類, 名前, 1分あたりの行）。 */
  def limits: IO[List[(String, String, Int)]] = tx(
    sql"select stream_type, name, max_rows_per_minute from stream where max_rows_per_minute is not null".query[(String, String, Int)].to[List]
  )

  /** 閲覧を制限しているストリーム（種類, 名前, グループ）。 */
  def restricted: IO[List[(String, String, List[String])]] = tx(
    sql"select stream_type, name, allowed_groups from stream where cardinality(allowed_groups) > 0".query[(String, String, List[String])].to[List]
  )

  /** ファイルに記録した値の一覧から、列の値（Prometheus のラベルの値の候補）。 */
  def tagValues(streamType: String, column: String, limit: Int): IO[List[String]] = tx(
    sql"""select distinct v from data_file f join stream s on s.id = f.stream_id, jsonb_array_elements_text(f.tags -> $column) v
          where s.stream_type = $streamType and f.deleted_at is null order by v limit $limit""".query[String].to[List]
  )

  /** その種類のすべてのストリームの列の名前。 */
  def fieldNames(streamType: String): IO[List[String]] = tx(
    sql"""select distinct f.name from stream_field f join stream s on s.id = f.stream_id where s.stream_type = $streamType order by 1"""
      .query[String].to[List]
  )
