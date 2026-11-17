package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.lang.management.ManagementFactory
import scala.concurrent.duration.*

/**
 * 自己監視: このノードの動き（取り込み・書き出し・検索・アラート・キャッシュ）と JVM の状態を、30秒ごとに skorimako 自身へ
 * メトリクス（skorimako_*・jvm_*・process_*）として取り込む。警告・エラーはログのストリーム skorimako に取り込む。
 * 最初に起動したノードが「skorimako 自身」のダッシュボードを作る。
 */
final class SelfMonitor(ingest: IngestService, stats: Stats, apps: AppStore, streams: StreamStore, lock: JobLock, nodeId: String):

  private val base: Map[String, Value] = Map("service_name" -> Value.Str("skorimako"), "node" -> Value.Str(nodeId))
  private val LogStream = "skorimako"

  private val descriptions: Map[String, (String, String)] = Map(
    "skorimako_ingested_rows_total" -> ("受け付けた行（種類ごと）", "1"),
    "skorimako_stored_bytes_total" -> ("S3 に書いた Parquet の大きさ", "By"),
    "skorimako_buffered_rows" -> ("書き出す前の行", "1"),
    "skorimako_flush_seconds_sum" -> ("書き出しにかかった秒の合計", "s"),
    "skorimako_flush_total" -> ("書き出しの回数", "1"),
    "skorimako_flush_failures_total" -> ("書き出しに失敗したストリームの数", "1"),
    "skorimako_queries_total" -> ("検索の回数", "1"),
    "skorimako_query_seconds_sum" -> ("検索にかかった秒の合計", "s"),
    "skorimako_query_errors_total" -> ("失敗した検索", "1"),
    "skorimako_query_files_pruned_total" -> ("値の一覧で読まずに済んだファイル", "1"),
    "skorimako_alert_evaluations_total" -> ("アラートの評価の回数", "1"),
    "skorimako_alert_notifications_total" -> ("アラートの通知の送信", "1"),
    "skorimako_push_sent_total" -> ("プッシュ通知の送信（結果ごと）", "1"),
    "skorimako_cache_bytes" -> ("検索のキャッシュの大きさ", "By"),
    "jvm_memory_heap_used_bytes" -> ("JVM のヒープの使用量", "By"),
    "jvm_memory_heap_max_bytes" -> ("JVM のヒープの上限", "By"),
    "jvm_threads" -> ("JVM のスレッドの数", "1"),
    "process_cpu_utilization" -> ("プロセスの CPU 使用率", "1"),
    "process_uptime_seconds" -> ("起動してからの秒", "s"))

  private def metricBatch(now: Long): Batch =
    val (counters, gauges) = stats.snapshot
    val rt = Runtime.getRuntime
    val os = ManagementFactory.getOperatingSystemMXBean
    val cpu = os match
      case x: com.sun.management.OperatingSystemMXBean => x.getProcessCpuLoad
      case _ => -1.0
    val jvm = List(
      ("jvm_memory_heap_used_bytes", Map.empty[String, String], (rt.totalMemory() - rt.freeMemory()).toDouble, "gauge"),
      ("jvm_memory_heap_max_bytes", Map.empty[String, String], rt.maxMemory().toDouble, "gauge"),
      ("jvm_threads", Map.empty[String, String], ManagementFactory.getThreadMXBean.getThreadCount.toDouble, "gauge"),
      ("process_uptime_seconds", Map.empty[String, String], ManagementFactory.getRuntimeMXBean.getUptime / 1000.0, "gauge")
    ) ++ Option.when(cpu >= 0)(("process_cpu_utilization", Map.empty[String, String], cpu, "gauge"))
    val all = counters.map { case ((n, l), v) => (n, l, v, "counter") } ++ gauges.map { case ((n, l), v) => (n, l, v.toDouble, "gauge") } ++ jvm
    val rows = all.groupMap(_._1)((_, l, v, t) => Row(now, base ++ l.map((k, x) => k -> Value.Str(x)) ++
      Map("__name__" -> Value.Str("x"), "__type__" -> Value.Str(t), "value" -> Value.Dbl(v))))
    Batch(rows.map((n, rs) => n -> rs.map(r => r.copy(fields = r.fields + ("__name__" -> Value.Str(n)))).toVector),
      all.map(x => x._1 -> MetricMeta(descriptions.get(x._1).map(_._1).getOrElse(""), descriptions.get(x._1).map(_._2).getOrElse(""), x._4)).toMap)

  def tick: IO[Unit] =
    IO.realTime.map(_.toMicros).flatMap { now =>
      val logs = SelfLog.drain().map(e => Row(e.ts, base ++ Map("severity_text" -> Value.Str(e.level), "body" -> Value.Str(e.message))))
      ingest.ingest(StreamType.Metrics, metricBatch(now)).void *>
        IO.whenA(logs.nonEmpty)(ingest.ingest(StreamType.Logs, Batch(Map(LogStream -> logs.toVector))).void)
    }

  def loop: IO[Nothing] = Periodic.run(20.seconds, 30.seconds)(e => s"[skorimako] self monitor failed: $e")(tick)

  /** 「skorimako 自身」のダッシュボード（なければ作る。1ノードだけ）。パネルが読むストリームと列も先に作っておく。 */
  def ensureDashboard: IO[Unit] =
    val metricFields = Map("value" -> FieldType.Float64, "node" -> FieldType.Utf8, "service_name" -> FieldType.Utf8, "type" -> FieldType.Utf8,
      "result" -> FieldType.Utf8, "__name__" -> FieldType.Utf8, "__type__" -> FieldType.Utf8)
    lock.exclusive("self-dashboard", 5.minutes)(
      descriptions.keys.toList.traverse_(n => streams.ensure(StreamType.Metrics, n).flatMap(st => streams.mergeFields(st.id, metricFields))) *>
      streams.ensure(StreamType.Logs, LogStream).flatMap(st => streams.mergeFields(st.id,
        Map("severity_text" -> FieldType.Utf8, "body" -> FieldType.Utf8, "node" -> FieldType.Utf8, "service_name" -> FieldType.Utf8))) *>
      apps.dashboards.flatMap { ds =>
      IO.whenA(!ds.exists(_.name == SelfMonitor.DashboardName))(apps.createDashboard(SelfMonitor.dashboard, None).void)
    })

object SelfMonitor:
  val DashboardName = "skorimako 自身"

  private def rate(metric: String, by: String, unit: String = "") =
    s"""WITH d AS (SELECT _timestamp, $by, value - lag(value) OVER (PARTITION BY node, $by ORDER BY _timestamp) AS dv FROM $metric)
       |SELECT (_timestamp // $$__interval) * $$__interval AS t, $by, sum(greatest(dv, 0)) / ($$__interval / 1e6) AS v
       |FROM d WHERE dv IS NOT NULL GROUP BY 1, 2 ORDER BY 1""".stripMargin

  def dashboard: DashboardReq = DashboardReq(DashboardName, "skorimako 自身の取り込み・書き出し・検索・アラート・JVM（自己監視）", List(
    PanelDto("s1", "取り込み（行/秒、種類ごと）", PanelKind.Line, StreamType.Metrics, rate("skorimako_ingested_rows_total", "type"), 2, "/s"),
    PanelDto("s2", "書き出す前の行（ノードごと）", PanelKind.Line, StreamType.Metrics,
      "SELECT (_timestamp // $__interval) * $__interval AS t, node, max(value) AS v FROM skorimako_buffered_rows GROUP BY 1, 2 ORDER BY 1", 2, ""),
    PanelDto("s3", "検索（回/秒、種類ごと）", PanelKind.Line, StreamType.Metrics, rate("skorimako_queries_total", "type"), 2, "/s"),
    PanelDto("s4", "書き出しの失敗", PanelKind.Stat, StreamType.Metrics,
      "SELECT coalesce(max(value) - min(value), 0) FROM skorimako_flush_failures_total", 1, ""),
    PanelDto("s5", "検索の失敗", PanelKind.Stat, StreamType.Metrics,
      "SELECT coalesce(max(value) - min(value), 0) FROM skorimako_query_errors_total", 1, ""),
    PanelDto("s6", "JVM のヒープ（ノードごと）", PanelKind.Line, StreamType.Metrics,
      "SELECT (_timestamp // $__interval) * $__interval AS t, node, max(value) AS v FROM jvm_memory_heap_used_bytes GROUP BY 1, 2 ORDER BY 1", 2, "By"),
    PanelDto("s7", "CPU 使用率（ノードごと）", PanelKind.Line, StreamType.Metrics,
      "SELECT (_timestamp // $__interval) * $__interval AS t, node, avg(value) * 100 AS v FROM process_cpu_utilization GROUP BY 1, 2 ORDER BY 1", 2, "%"),
    PanelDto("s8", "skorimako の警告・エラー", PanelKind.Table, StreamType.Logs,
      "SELECT to_timestamp(_timestamp / 1e6) AS \"日時\", node, severity_text, body FROM skorimako ORDER BY _timestamp DESC LIMIT 50", 4, "")
  ))
