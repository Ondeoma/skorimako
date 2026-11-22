package skorimako.engine

import cats.effect.*
import cats.effect.std.Semaphore
import cats.syntax.all.*
import cats.effect.syntax.all.*
import io.circe.Json
import skorimako.core.*
import skorimako.db.*
import skorimako.shared.*
import skorimako.storage.ObjectStore

import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final case class QueryConfig(
  cacheDir: Path, // S3 から取ってきた Parquet の置き場所（ノードごと）
  cacheMaxBytes: Long = 10L << 30, // これを超えたら古く使ったものから消す
  timeout: FiniteDuration = 60.seconds, // 1回の検索の上限
  concurrency: Int = 4, // 同時に動かす検索の数（ノードごと）
  perUser: Int = 2, // 1人の利用者が同時に動かす検索の数（ノードごと。超えた分は待つ。サーバーの中の処理は対象外）
  maxRows: Int = 10000 // 1回の検索で返す行の上限
)

/** 検索の失敗（利用者に見せる文言）。 */
final case class QueryError(message: String) extends Exception(message)

/** 書き出す前の行（このノードとほかのノードのバッファ）を集める。(種類, ストリーム名, 始まり, 終わり) → ストリーム名ごとの行。 */
type LiveRows = (String, Set[String], Long, Long) => IO[Map[String, Vector[Row]]]

/**
 * 検索: SQL が参照するストリームの、時間の範囲に重なる Parquet を S3 からキャッシュに取ってきて、DuckDB で SQL を実行する。
 * ストリームはビューとして見える（例: SELECT * FROM "default" WHERE severity_text = 'ERROR'）。
 * まだ書き出していない行（各ノードのバッファ）も、一時の Parquet にしてビューに加える。
 * 「列 = 値」の条件があれば、その値を含まないファイル（ファイルごとの値の一覧で分かるもの）は読まない。
 */
final class QueryEngine(store: StreamStore,
                        objects: ObjectStore,
                        duck: DuckConfig,
                        cfg: QueryConfig,
                        sem: Semaphore[IO],
                        val acl: Acl,
                        stats: Stats,
                        live: Ref[IO, LiveRows],
                        perUser: Ref[IO, Map[String, (Semaphore[IO], Int)]]) {

  private def local(key: String): Path = cfg.cacheDir.resolve(key).normalize()
  private val liveDir = cfg.cacheDir.resolve("_live")

  /** 書き出す前の行の集め方を決める（起動時に、ほかのノードへの問い合わせを含むものに替える）。 */
  def setLive(f: LiveRows): IO[Unit] = live.set(f)

  /** キャッシュになければ取ってくる。使った時刻を記録する（古いものから消すため）。 */
  private def fetch(f: FileRow): IO[Option[Path]] = {
    val p = local(f.key)
    IO.blocking(Files.exists(p)).flatMap {
      case true => IO.blocking(scala.util.Try(Files.setLastModifiedTime(
          p,
          FileTime.fromMillis(System.currentTimeMillis())
        ))).as(Some(p))
      case false => objects.download(f.key, p).map(Option.when(_)(p))
    }
  }

  /** ビュー（ストリームごとの Parquet の一覧と列）。temp は検索の後で消すファイル（書き出す前の行）。 */
  private def views(v: Viewer,
                    streamType: String,
                    names: List[String],
                    start: Long,
                    end: Long,
                    prune: Map[String, Set[String]]): IO[(List[DuckSession.View], Int, Long, List[Path])] =
    for {
      // 見られないストリームは、ないものと同じ（空のビュー）にする
      seen <- acl.filter(v, streamType, names)(identity)
      rows <- names.traverse(n => (if seen.contains(n) then store.find(streamType, n) else IO.pure(None)).map(n -> _))
      found = rows.collect { case (n, Some(r)) => n -> r }
      all <- store.filesFor(found.map(_._2.id), start, end)
      files = all.filter(f => Prune.keep(f.tags, prune))
      _ = stats.inc("skorimako_query_files_pruned_total", Map.empty, (all.size - files.size).toDouble)
      local <- files.parTraverseN(8)(f => fetch(f).map(f -> _))
      buf <- live.get.flatMap(_(streamType, seen.toSet, start, end)).handleError(_ => Map.empty)
      vs <- rows.traverse {
        (n, r) =>
          r.traverse(x => store.fields(x.id)).map(_.getOrElse(Nil)).flatMap { fs =>
            val parquet = local.collect { case (f, Some(p)) if r.exists(_.id == f.streamId) => p }
            buf.get(n).filter(_.nonEmpty) match {
              case None => IO.pure((DuckSession.View(n, parquet, fs), None))
              case Some(liveRows) =>
                // 書き出す前の行は、まだ登録していない列を含むことがある
                val inferred = liveRows.foldLeft(fs.map(f => f.name -> f.fieldType).toMap) {
                  (acc, row) =>
                    row.fields.foldLeft(acc) { case (a, (k, v)) =>
                      a.updated(k, a.get(k).fold(v.fieldType)(FieldTypes.widen(_, v.fieldType)))
                    }
                } - Columns.Timestamp
                val p = liveDir.resolve(UUID.randomUUID().toString + ".parquet")
                IO.blocking { Files.createDirectories(liveDir); Duck.writeParquet(duck, liveRows, inferred, p) }
                  .as((DuckSession.View(n, parquet :+ p, inferred.toList.map(FieldDto(_, _)).sortBy(_.name)), Some(p)))
            }
          }
      }
    } yield (vs.map(_._1), files.size, files.map(_.size).sum, vs.flatMap(_._2))

  /** 利用者ごとの同時実行の枠（v.key ごとに cfg.perUser 本まで。超えた分は待つ）。key のない処理（サーバーの中）は対象外。 */
  private def userSlot(v: Viewer): Resource[IO, Unit] = v.key match {
    case None => Resource.unit
    case Some(k) =>
      Resource.make(
        Semaphore[IO](math.max(1, cfg.perUser).toLong).flatMap(fresh =>
          perUser.modify { m =>
            val (s, n) = m.getOrElse(k, (fresh, 0))
            (m.updated(k, (s, n + 1)), s)
          }
        )
      )(_ =>
        // 使っている人がいなくなったら捨てる
        perUser.update(m =>
          m.get(k) match {
            case Some((s, n)) if n > 1 => m.updated(k, (s, n - 1))
            case _ => m - k
          }
        )
      ).flatMap(_.permit)
  }

  /**
   * ストリームのビューを作った DuckDB で f を実行する（同時に動かす数と時間に上限がある。同時に動かす数は、全体と利用者ごと）。
   * v が見られないストリームは、ないものと同じ（空のビュー）になる。
   * f の中で失敗したら Left（SQL の誤りなど、利用者に見せる文言）。
   */
  def session[A](v: Viewer,
                 streamType: String,
                 names: List[String],
                 start: Long,
                 end: Long,
                 prune: Map[String, Set[String]] = Map.empty)(f: DuckSession => A): IO[Either[String, (A, Int, Long)]] =
    (userSlot(v) *> sem.permit).use { _ =>
      IO.monotonic.flatMap { t0 =>
        Resource.make(views(v, streamType, names.distinct, start, end, prune))((_, _, _, tmp) =>
          IO.blocking(tmp.foreach(p => scala.util.Try(Files.deleteIfExists(p))))
        ).use {
          (vs, nFiles, nBytes, _) =>
            Resource.make(IO.blocking(DuckSession.open(duck, vs, start, end)))(s =>
              IO.blocking(s.close())
            ).use { s =>
              // 時間切れになったら実行中の文を止める（止められた文は例外で終わる）
              (IO.sleep(cfg.timeout) *> IO(s.cancel())).background.surround(IO.blocking(f(s))).attempt.flatMap {
                case Right(a) => IO.pure(Right((a, nFiles, nBytes)))
                case Left(_) if s.isCancelled =>
                  IO.pure(Left(s"検索が時間内（${cfg.timeout.toSeconds}秒）に終わりませんでした。時間の範囲を狭めるか、条件を足してください"))
                case Left(e: QueryError) => IO.pure(Left(e.message))
                case Left(e: java.sql.SQLException) => IO.pure(Left(QueryEngine.clean(e.getMessage)))
                // 予期しない失敗の中身はログにだけ出す
                case Left(e) => QueryEngine.internal(ErrorCode.QueryRunFailed, e).map(Left(_))
              }
            }
        }.handleErrorWith(e => QueryEngine.internal(ErrorCode.QueryPrepareFailed, e).map(Left(_)))
          .flatTap(r =>
            IO.monotonic.map { t1 =>
              stats.inc("skorimako_query_seconds_sum", Map("type" -> streamType), (t1 - t0).toMillis / 1000.0)
              stats.inc("skorimako_queries_total", Map("type" -> streamType))
              if r.isLeft then stats.inc("skorimako_query_errors_total", Map("type" -> streamType))
            }
          )
      }
    }

  /** SQL が参照しているストリーム（その種類の既存のストリームの名前と一致するもの）を、見られるものと見られないものに分ける。 */
  private def split(v: Viewer, streamType: String, names: Set[String]): IO[(List[String], List[String])] =
    store.names(streamType).map(_.filter(n => names.contains(n) || names.contains(n.toLowerCase))).flatMap(ns =>
      acl.filter(v, streamType, ns)(identity).map(ok => (ok, ns.filterNot(ok.contains)))
    )

  /**
   * SQL が参照しているストリームのうち、見られるもの。
   * 見られないものは、ないものと同じに扱う（何も言わずに外す。見られないストリームがあることを文言で知らせない）。
   */
  def referenced(v: Viewer, streamType: String, names: Set[String]): IO[List[String]] =
    split(v, streamType, names).map(_._1)

  /** SQL が参照しているストリームを、すべて見られるか（アラートのルールを保存するときに確かめる）。 */
  def allVisible(v: Viewer, streamType: String, names: Set[String]): IO[Boolean] =
    split(v, streamType, names).map(_._2.isEmpty)

  /** その人が見られるストリーム。見られないものは、ないものと区別しない（None）。 */
  private def visibleStream(v: Viewer, streamType: String, name: String): IO[Option[StreamRow]] =
    acl.visible(v, streamType, name).flatMap(ok => if ok then store.find(streamType, name) else IO.pure(None))

  /** 利用者の SQL。 */
  def sql(v: Viewer, r: SqlQueryReq): IO[Either[String, QueryResultDto]] =
    SqlText.checkQuery(r.sql) match {
      case Left(e) => IO.pure(Left(e))
      case Right((sql, names)) =>
        referenced(v, r.streamType, names).flatMap {
          case Nil =>
            IO.pure(Left(s"FROM に${StreamType.label(r.streamType)}のストリームの名前を書いてください（例: SELECT * FROM \"default\"）"))
          case ss =>
            val limit = math.max(1, math.min(r.limit.getOrElse(1000), cfg.maxRows))
            session(v, r.streamType, ss, r.start, r.end)(_.query(
              QueryBuilder.expand(sql, r.start, r.end),
              limit
            )).map(_.map {
              (res, n, b) =>
                res.copy(scannedFiles = n, scannedBytes = b)
            })
        }
    }

  // ---------------------------------------------------------------- ログ（RUM も同じ）

  private def textColumns(v: Viewer, streamType: String, stream: String): IO[List[String]] =
    visibleStream(v, streamType, stream).flatMap(_.traverse(s => store.fields(s.id))).map { fs =>
      val all = fs.getOrElse(Nil).filter(_.fieldType == FieldType.Utf8).map(_.name)
      val main = List("body", "message", "msg", "log", "status_message", "stack").filter(all.contains)
      // 本文の列があればそれだけを探す（なければ文字列の列すべて）
      if main.nonEmpty then main else all
    }

  def logs(v: Viewer, r: LogSearchReq): IO[Either[String, LogSearchDto]] = {
    val streamType = r.streamType.filter(StreamType.all.contains).getOrElse(StreamType.Logs)
    Names.stream(r.stream) match {
      case Left(e) => IO.pure(Left(e))
      case Right(stream) =>
        // 見られないストリームは、ないストリームと同じ結果になる（列の名前も読まない）
        textColumns(v, streamType, stream).flatMap { tc =>
          QueryBuilder.conditions(r.where, r.keyword, tc) match {
            case Left(e) => IO.pure(Left(e))
            case Right(conds) =>
              val bucket = QueryBuilder.interval(r.start, r.end)
              val limit = math.max(1, math.min(r.limit.getOrElse(100), 1000))
              val q = QueryBuilder.logSearch(
                stream,
                conds,
                r.order.getOrElse("desc"),
                limit,
                math.max(0, r.offset.getOrElse(0)),
                bucket
              )
              session(v, streamType, List(stream), r.start, r.end, Prune.constraints(r.where)) { s =>
                val list = s.query(q.list, limit)
                val total = s.query(
                  q.count,
                  1
                ).rows.headOption.flatMap(_.headOption).flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L)
                val hist = s.query(q.histogram, 100000).rows.flatMap {
                  case t :: c :: Nil =>
                    (t.asNumber.flatMap(_.toLong), c.asNumber.flatMap(_.toLong)).mapN(BucketDto(_, _))
                  case _ => None
                }
                (list, total, hist)
              }.map(_.map { case ((list, total, hist), n, b) =>
                LogSearchDto(list.copy(scannedFiles = n, scannedBytes = b), total, hist, bucket, q.list)
              })
          }
        }
    }
  }

  // ---------------------------------------------------------------- メトリクス

  def metrics(v: Viewer, r: MetricQueryReq): IO[Either[String, MetricResultDto]] =
    Names.metric(r.metric).toRight("メトリクスの名前を指定してください").flatMap(Names.stream) match {
      case Left(e) => IO.pure(Left(e))
      case Right(stream) =>
        visibleStream(v, StreamType.Metrics, stream).flatMap {
          // 見られないメトリクスも、ないものと同じ文言にする
          case None => IO.pure(Left(s"メトリクス $stream がありません"))
          case Some(st) =>
            store.fields(st.id).flatMap { fs =>
              val names = fs.map(_.name).toSet
              val labels = fs.map(_.name).filterNot(QueryBuilder.notLabels)
              val groups = r.groupBy.map(_.trim).filter(_.nonEmpty).distinct
              val step = r.stepSeconds.filter(_ > 0).map(_.toLong * 1000000L).getOrElse(QueryBuilder.interval(
                r.start,
                r.end,
                120
              ))
              (for {
                _ <- groups.find(g => !names.contains(g)).map(g => s"ラベル $g がありません").toLeft(())
                conds <- QueryBuilder.conditions(r.where, None, Nil)
                sql <- QueryBuilder.metric(stream, r.agg, groups, conds, step, labels)
              } yield sql) match {
                case Left(e) => IO.pure(Left(e))
                case Right(sql) =>
                  session(v, StreamType.Metrics, List(stream), r.start, r.end, Prune.constraints(r.where))(_.query(
                    sql,
                    200000
                  )).map(_.map {
                    (res, _, _) =>
                      val series = res.rows.groupBy(row =>
                        groups.zip(row.drop(1).take(groups.size)).map((g, v) => g -> QueryEngine.text(v)).toMap
                      )
                        .toList.sortBy(_._1.toList.sorted.mkString(","))
                        .take(200)
                        .map {
                          (labels, rows) =>
                            SeriesDto(
                              labels,
                              rows.flatMap(row =>
                                row.headOption.flatMap(_.asNumber).flatMap(_.toLong)
                                  .map(t => PointDto(t, row.lastOption.flatMap(_.asNumber).map(_.toDouble)))
                              ).sortBy(_.t)
                            )
                        }
                      MetricResultDto(series, (step / 1000000).toInt, sql, res.truncated)
                  })
              }
            }
        }
    }

  // ---------------------------------------------------------------- トレース

  /** トレースのスパンをすべて（索引から時間の範囲を引いて読む）。 */
  def trace(v: Viewer, traceId: String): IO[Either[String, TraceDto]] = {
    val id = traceId.trim.toLowerCase
    if !id.matches("[0-9a-f]{1,64}") then IO.pure(Left("トレース ID が正しくありません"))
    else
      store.findTrace(id).flatMap {
        case Nil => IO.pure(Left("トレースが見つかりません"))
        case hits =>
          hits.traverse {
            (sid, mi, ma) => store.byId(sid).map(_.map(s => (s.name, mi, ma)))
          }.flatMap { ss =>
            acl.filter(v, StreamType.Traces, ss.flatten)(_._1).flatMap {
              case Nil => IO.pure(Left("トレースが見つかりません"))
              case visible =>
                visible.traverse {
                  (name, mi, ma) =>
                    val sql =
                      s"SELECT * FROM ${SqlText.ident(name)} WHERE trace_id = ${SqlText.lit(id)} ORDER BY ${SqlText.ident(Columns.Timestamp)}"
                    // スパンの開始は索引の範囲の中にある（少し余裕をみる）
                    session(
                      v,
                      StreamType.Traces,
                      List(name),
                      mi - 60000000L,
                      ma + 60000000L
                    )(_.query(sql, 20000)).map(_.map(_._1))
                }.map { rs =>
                  rs.collectFirst { case Left(e) => e }.toLeft {
                    val spans = rs.collect { case Right(res) => res }.flatMap(res =>
                      res.rows.map(row => Json.fromFields(res.columns.map(_.name).zip(row)))
                    )
                    TraceDto(id, spans)
                  }
                }
            }
          }
      }
  }

  // ---------------------------------------------------------------- サービス（トレースから）

  /** サービスごとの要求・エラー・所要時間（入口のスパン: SERVER・CONSUMER・ルート）と、サービス間の呼び出し。 */
  def serviceMap(v: Viewer, stream: String, start: Long, end: Long): IO[Either[String, ServiceMapDto]] = {
    // 見られないストリームは、ないストリームと同じ結果になる（session が空のビューにする）
    val s = SqlText.ident(stream)
    val secs = math.max(1.0, (end - start) / 1e6)
    val statsSql =
      s"""SELECT service_name, count(*) AS n, count(*) FILTER (WHERE status_code = 'ERROR') AS e,
         |quantile_cont(duration, 0.5) / 1000.0, quantile_cont(duration, 0.95) / 1000.0, quantile_cont(duration, 0.99) / 1000.0
         |FROM $s WHERE span_kind IN ('SERVER', 'CONSUMER') OR coalesce(parent_span_id, '') = '' GROUP BY 1 ORDER BY 2 DESC LIMIT 500""".stripMargin
    val edgeSql =
      s"""SELECT p.service_name, c.service_name, count(*), count(*) FILTER (WHERE c.status_code = 'ERROR'), quantile_cont(c.duration, 0.95) / 1000.0
         |FROM $s c JOIN $s p ON c.parent_span_id = p.span_id AND c.trace_id = p.trace_id
         |WHERE p.service_name <> c.service_name GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 2000""".stripMargin
    session(v, StreamType.Traces, List(stream), start, end) { ses =>
      val st = ses.query(statsSql, 500).rows.map { row =>
        def num(i: Int) = row(i).asNumber.map(_.toDouble).getOrElse(0.0)
        val n = num(1).toLong
        ServiceStatDto(QueryEngine.text(row.head), n, num(2).toLong, n / secs, num(3), num(4), num(5))
      }
      val ed = ses.query(edgeSql, 2000).rows.map { row =>
        def num(i: Int) = row(i).asNumber.map(_.toDouble).getOrElse(0.0)
        ServiceEdgeDto(QueryEngine.text(row.head), QueryEngine.text(row(1)), num(2).toLong, num(3).toLong, num(4))
      }
      ServiceMapDto(st, ed)
    }.map(_.map(_._1))
  }

  /** サービスの時系列（入口のスパンの、区切りごとの要求・エラー・p95）。 */
  def serviceSeries(v: Viewer,
                    stream: String,
                    service: String,
                    start: Long,
                    end: Long): IO[Either[String, ServiceSeriesDto]] = {
    val b = QueryBuilder.interval(start, end, 60)
    val sql =
      s"""SELECT (_timestamp // $b) * $b AS t, count(*), count(*) FILTER (WHERE status_code = 'ERROR'), quantile_cont(duration, 0.95) / 1000.0
         |FROM ${SqlText.ident(stream)} WHERE service_name = ${SqlText.lit(service)}
         |AND (span_kind IN ('SERVER', 'CONSUMER') OR coalesce(parent_span_id, '') = '') GROUP BY 1 ORDER BY 1""".stripMargin
    session(v, StreamType.Traces, List(stream), start, end, Map("service_name" -> Set(service)))(_.query(
      sql,
      100000
    )).map(_.map {
      (res, _, _) =>
        ServiceSeriesDto(
          service,
          res.rows.flatMap { row =>
            row.head.asNumber.flatMap(_.toLong).map(t =>
              ServicePointDto(
                t,
                row(1).asNumber.flatMap(_.toLong).getOrElse(0L),
                row(2).asNumber.flatMap(_.toLong).getOrElse(0L),
                row(3).asNumber.map(_.toDouble)
              )
            )
          }
        )
    })
  }

  // ---------------------------------------------------------------- PromQL

  /**
   * PromQL の式のセレクターの対象を読む（start・end はミリ秒。範囲・offset・直近5分の分を足して読む）。
   * 系列はラベル（value・_timestamp・__type__ 以外の列）の組ごと。読む行は、式全体の合計で最大 maxRows。
   * セレクターは QueryEngine.MaxPromSelectors 個まで（セレクターごとに検索を 1 回動かすので）。
   */
  def promData(v: Viewer,
               e: PromQL.Expr,
               start: Long,
               end: Long,
               maxRows: Int = 5000000): IO[Either[String, Map[PromQL.Expr.Selector, List[Prom.Series]]]] = {
    val sels = PromQL.selectors(e).map(PromQL.bare).distinct
    val from = (start - PromQL.lookback(e) - Prom.LookbackDelta) * 1000
    val to = end * 1000
    // 読んだ行の数（式のすべてのセレクターの合計）
    val read = new java.util.concurrent.atomic.AtomicLong(0)
    if sels.size > QueryEngine.MaxPromSelectors then
      IO.pure(Left(s"式のセレクターが多すぎます（${QueryEngine.MaxPromSelectors} 個まで）"))
    else
      sels.traverse { sel =>
        Prom.metricName(sel) match {
          case None => IO.pure(Left("メトリクスの名前を指定してください（例: http_requests_total{job=\"api\"}）"))
          case Some(name) => visibleStream(v, StreamType.Metrics, name).flatMap {
              case None => IO.pure(Right(sel -> Nil)) // ないメトリクス・見られないメトリクスは空
              case Some(_) =>
                val conds = Prom.sqlConds(sel)
                val where = if conds.isEmpty then "" else conds.mkString(" WHERE ", " AND ", "")
                val sql = s"SELECT * FROM ${SqlText.ident(name)}$where ORDER BY ${SqlText.ident(Columns.Timestamp)}"
                val eqs = sel.matchers.filter(m => m.op == PromQL.MatchOp.Eq && m.label != "__name__").flatMap(m =>
                  Names.column(m.label).map(_ -> Set(m.value))
                ).toMap
                session(v, StreamType.Metrics, List(name), from, to, eqs) { s =>
                  val acc = scala.collection.mutable.LinkedHashMap.empty[
                    Prom.Labels,
                    (scala.collection.mutable.ArrayBuilder.ofLong, scala.collection.mutable.ArrayBuilder.ofDouble)
                  ]
                  s.scan(sql) { rs =>
                    if read.incrementAndGet() > maxRows then
                      throw QueryError(s"読む点が多すぎます（$maxRows まで）。時間の範囲を狭めるか、ラベルで絞ってください")
                    val md = rs.getMetaData
                    var ts = 0L; var value = Double.NaN
                    val labels = scala.collection.immutable.Map.newBuilder[String, String]
                    (1 to md.getColumnCount).foreach { i =>
                      md.getColumnLabel(i) match {
                        case Columns.Timestamp => ts = rs.getLong(i)
                        case "value" => value = { val d = rs.getDouble(i); if rs.wasNull() then Double.NaN else d }
                        case "__type__" => ()
                        case c => Option(rs.getObject(i)).foreach(o => labels += c -> o.toString)
                      }
                    }
                    val l = labels.result()
                    if Prom.regexOk(sel, l) then {
                      val (tb, vb) = acc.getOrElseUpdate(
                        l,
                        (
                          new scala.collection.mutable.ArrayBuilder.ofLong,
                          new scala.collection.mutable.ArrayBuilder.ofDouble
                        )
                      )
                      tb += ts / 1000; vb += value
                    }
                  }
                  sel -> acc.toList.map((l, b) => Prom.Series(l, b._1.result(), b._2.result()))
                }.map(_.map(_._1))
            }
        }
      }.map(_.sequence.map(_.toMap))
  }

  // ---------------------------------------------------------------- キャッシュの掃除

  /** キャッシュが上限を超えていたら、使ったのが古いものから消す。 */
  def cleanCache: IO[Long] = IO.blocking {
    if !Files.exists(cfg.cacheDir) then 0L
    else {
      val files = Files.walk(cfg.cacheDir).iterator().asScala.filter(p => Files.isRegularFile(p)).toList
        .flatMap(p => scala.util.Try((p, Files.size(p), Files.getLastModifiedTime(p).toMillis)).toOption)
      val total = files.map(_._2).sum
      stats.set("skorimako_cache_bytes", Map.empty, total)
      // 書き出す前の行の一時ファイルが残っていたら消す（10分より古いもの）
      files.filter((p, _, t) => p.startsWith(liveDir) && t < System.currentTimeMillis() - 600000).foreach((p, _, _) =>
        scala.util.Try(Files.deleteIfExists(p))
      )
      if total <= cfg.cacheMaxBytes then 0L
      else {
        val target = (cfg.cacheMaxBytes * 0.8).toLong
        var cur = total
        var freed = 0L
        files.sortBy(_._3).foreach {
          (p, size, _) =>
            if cur > target && scala.util.Try(Files.deleteIfExists(p)).getOrElse(false) then {
              cur -= size; freed += size
            }
        }
        freed
      }
    }
  }

  def forgetCached(key: String): IO[Unit] = IO.blocking(Files.deleteIfExists(local(key))).void
}

object QueryEngine {
  def create(store: StreamStore,
             objects: ObjectStore,
             duck: DuckConfig,
             cfg: QueryConfig,
             acl: Acl,
             stats: Stats): IO[QueryEngine] =
    for {
      _ <- IO.blocking(Files.createDirectories(cfg.cacheDir))
      sem <- Semaphore[IO](cfg.concurrency.toLong)
      lv <- Ref.of[IO, LiveRows]((_, _, _, _) => IO.pure(Map.empty))
      pu <- Ref.of[IO, Map[String, (Semaphore[IO], Int)]](Map.empty)
    } yield new QueryEngine(store, objects, duck, cfg, sem, acl, stats, lv, pu)

  /** PromQL の 1 つの式のセレクターの数の上限。 */
  val MaxPromSelectors = 20

  /** 予期しない失敗: 中身はコード付きでログにだけ出し、利用者にはコードと決まった文言だけを返す。 */
  def internal(code: ErrorCode, e: Throwable): IO[String] =
    sbirds.observe.Log.report(code, e.toString, e).as(s"${code.message}（${code.code}）")

  def text(j: Json): String = j.asString.getOrElse(if j.isNull then "" else j.noSpaces)

  /** DuckDB のエラーの文言から、手元のパスなどを除く。 */
  def clean(msg: String): String =
    Option(msg).getOrElse("SQL の誤り").replaceAll("'/[^']*'", "'…'").linesIterator.take(6).mkString("\n").take(800)
}
