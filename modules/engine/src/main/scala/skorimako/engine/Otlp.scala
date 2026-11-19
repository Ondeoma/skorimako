package skorimako.engine

import com.google.protobuf.{ByteString, Message}
import com.google.protobuf.util.JsonFormat
import io.circe.Json
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.{AnyValue, KeyValue}
import io.opentelemetry.proto.metrics.v1.{AggregationTemporality, Metric, NumberDataPoint}
import io.opentelemetry.proto.trace.v1.{Span, Status}
import skorimako.core.*

import java.util.{Base64, HexFormat}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** メトリクスの説明（ストリームに記録する）。 */
final case class MetricMeta(
  description: String,
  unit: String,
  metricType: String)

/** 取り込む内容: ストリーム名ごとの行。メトリクスは説明も。 */
final case class Batch(
  rows: Map[String, Vector[Row]],
  meta: Map[String, MetricMeta] = Map.empty) {
  def size: Int = rows.values.map(_.size).sum
  def ++(
    o: Batch
  ): Batch =
    Batch(
      (rows.keySet ++ o.rows.keySet).map(k =>
        k -> (rows.getOrElse(k, Vector.empty) ++ o.rows.getOrElse(k, Vector.empty))
      ).toMap,
      meta ++ o.meta
    )
}

/**
 * OTLP（OpenTelemetry Protocol）の要求を行に直す。protobuf と JSON（OTLP/HTTP）の両方を受ける。
 * 属性は列名の規則（英小文字・数字・_）に直して列にする（service.name → service_name）。入れ子の属性は _ でつなぐ。
 */
object Otlp {

  private val hex = HexFormat.of()

  // ---------------------------------------------------------------- 読み込み

  /** OTLP/JSON は traceId・spanId を16進数で書く（protobuf の JSON 表現は base64）ので、読む前に直す。 */
  private def fixIds(
    j: Json
  ): Json =
    j.arrayOrObject(
      j,
      a => Json.fromValues(a.map(fixIds)),
      o =>
        Json.fromFields(o.toList.map {
          case (k @ ("traceId" | "spanId" | "parentSpanId"), v) =>
            k -> v.asString.filter(s => s.nonEmpty && s.length % 2 == 0 && s.forall(c => Character.digit(c, 16) >= 0))
              .map(s => Json.fromString(Base64.getEncoder.encodeToString(hex.parseHex(s)))).getOrElse(v)
          case (k, v) => k -> fixIds(v)
        })
    )

  private def parseJson[B <: Message.Builder](
    body: Array[Byte],
    b: B
  ): Either[String, B] =
    io.circe.parser.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8)).left.map(e =>
      s"JSON として読めません: ${e.message}"
    )
      .flatMap(j =>
        scala.util.Try(JsonFormat.parser().ignoringUnknownFields().merge(fixIds(j).noSpaces, b)).toEither
          .left.map(e => s"OTLP の JSON として読めません: ${Option(e.getMessage).getOrElse("").take(200)}").map(_ => b)
      )

  def logs(
    body: Array[Byte],
    json: Boolean,
    stream: String,
    nowMicros: Long
  ): Either[String, Batch] =
    (if json then parseJson(body, ExportLogsServiceRequest.newBuilder()).map(_.build())
     else scala.util.Try(ExportLogsServiceRequest.parseFrom(body)).toEither.left.map(_ => "OTLP の protobuf として読めません"))
      .map(r => Batch(Map(stream -> logRows(r, nowMicros))))

  def traces(
    body: Array[Byte],
    json: Boolean,
    stream: String,
    nowMicros: Long
  ): Either[String, Batch] =
    (if json then parseJson(body, ExportTraceServiceRequest.newBuilder()).map(_.build())
     else scala.util.Try(ExportTraceServiceRequest.parseFrom(body)).toEither.left.map(_ => "OTLP の protobuf として読めません"))
      .map(r => Batch(Map(stream -> spanRows(r, nowMicros))))

  def metrics(
    body: Array[Byte],
    json: Boolean,
    nowMicros: Long
  ): Either[String, Batch] =
    (if json then parseJson(body, ExportMetricsServiceRequest.newBuilder()).map(_.build())
     else
       scala.util.Try(ExportMetricsServiceRequest.parseFrom(body)).toEither.left.map(_ => "OTLP の protobuf として読めません")
    )
      .map(r => metricRows(r, nowMicros))

  /** 解釈済みの要求から（gRPC）。 */
  def logsOf(
    r: ExportLogsServiceRequest,
    stream: String,
    nowMicros: Long
  ): Batch = Batch(Map(stream -> logRows(r, nowMicros)))
  def tracesOf(
    r: ExportTraceServiceRequest,
    stream: String,
    nowMicros: Long
  ): Batch = Batch(Map(stream -> spanRows(r, nowMicros)))
  def metricsOf(
    r: ExportMetricsServiceRequest,
    nowMicros: Long
  ): Batch = metricRows(r, nowMicros)

  // ---------------------------------------------------------------- 属性

  private def idHex(
    b: ByteString
  ): String = if b.isEmpty then "" else hex.formatHex(b.toByteArray)

  private def anyJson(
    v: AnyValue
  ): Json = v.getValueCase match {
    case AnyValue.ValueCase.STRING_VALUE => Json.fromString(v.getStringValue)
    case AnyValue.ValueCase.BOOL_VALUE => Json.fromBoolean(v.getBoolValue)
    case AnyValue.ValueCase.INT_VALUE => Json.fromLong(v.getIntValue)
    case AnyValue.ValueCase.DOUBLE_VALUE => Json.fromDoubleOrNull(v.getDoubleValue)
    case AnyValue.ValueCase.ARRAY_VALUE => Json.fromValues(v.getArrayValue.getValuesList.asScala.map(anyJson))
    case AnyValue.ValueCase.KVLIST_VALUE =>
      Json.fromFields(v.getKvlistValue.getValuesList.asScala.map(kv => kv.getKey -> anyJson(kv.getValue)))
    case AnyValue.ValueCase.BYTES_VALUE =>
      Json.fromString(Base64.getEncoder.encodeToString(v.getBytesValue.toByteArray))
    case _ => Json.Null
  }

  /** 属性を列に足す（入れ子の kvlist は prefix_key、配列は JSON の文字列）。 */
  private def addAttrs(
    out: mutable.Map[String, Value],
    kvs: java.util.List[KeyValue],
    prefix: String = ""
  ): Unit =
    kvs.asScala.foreach { kv =>
      Names.column(prefix + kv.getKey).foreach { name =>
        val v = kv.getValue
        v.getValueCase match {
          case AnyValue.ValueCase.STRING_VALUE => out(name) = Value.Str(v.getStringValue)
          case AnyValue.ValueCase.BOOL_VALUE => out(name) = Value.Bool(v.getBoolValue)
          case AnyValue.ValueCase.INT_VALUE => out(name) = Value.Int(v.getIntValue)
          case AnyValue.ValueCase.DOUBLE_VALUE => out(name) = Value.Dbl(v.getDoubleValue)
          case AnyValue.ValueCase.KVLIST_VALUE => addAttrs(out, v.getKvlistValue.getValuesList, name + "_")
          case AnyValue.ValueCase.VALUE_NOT_SET => ()
          case _ => out(name) = Value.Str(anyJson(v).noSpaces)
        }
      }
    }

  private def micros(
    nanos: Long,
    fallback: Long
  ): Long = if nanos > 0 then nanos / 1000 else fallback

  private val severityNames = Vector(
    "",
    "TRACE",
    "TRACE",
    "TRACE",
    "TRACE",
    "DEBUG",
    "DEBUG",
    "DEBUG",
    "DEBUG",
    "INFO",
    "INFO",
    "INFO",
    "INFO",
    "WARN",
    "WARN",
    "WARN",
    "WARN",
    "ERROR",
    "ERROR",
    "ERROR",
    "ERROR",
    "FATAL",
    "FATAL",
    "FATAL",
    "FATAL"
  )

  // ---------------------------------------------------------------- ログ

  private def logRows(
    r: ExportLogsServiceRequest,
    now: Long
  ): Vector[Row] = {
    val out = Vector.newBuilder[Row]
    r.getResourceLogsList.asScala.foreach { rl =>
      val res = mutable.LinkedHashMap.empty[String, Value]
      addAttrs(res, rl.getResource.getAttributesList)
      rl.getScopeLogsList.asScala.foreach { sl =>
        sl.getLogRecordsList.asScala.foreach { lr =>
          val f = mutable.LinkedHashMap.empty[String, Value]
          f ++= res
          addAttrs(f, lr.getAttributesList)
          if sl.hasScope && sl.getScope.getName.nonEmpty then f("scope_name") = Value.Str(sl.getScope.getName)
          val body = lr.getBody
          body.getValueCase match {
            case AnyValue.ValueCase.STRING_VALUE => f("body") = Value.Str(body.getStringValue)
            case AnyValue.ValueCase.VALUE_NOT_SET => ()
            case AnyValue.ValueCase.KVLIST_VALUE =>
              f("body") = Value.Str(anyJson(body).noSpaces)
              addAttrs(f, body.getKvlistValue.getValuesList, "body_")
            case _ => f("body") = Value.Str(anyJson(body).asString.getOrElse(anyJson(body).noSpaces))
          }
          val sn = lr.getSeverityNumberValue
          val st = Option(lr.getSeverityText).filter(_.nonEmpty).orElse(severityNames.lift(sn).filter(_.nonEmpty))
          st.foreach(s => f("severity_text") = Value.Str(s))
          if sn > 0 then f("severity_number") = Value.Int(sn.toLong)
          if !lr.getTraceId.isEmpty then f("trace_id") = Value.Str(idHex(lr.getTraceId))
          if !lr.getSpanId.isEmpty then f("span_id") = Value.Str(idHex(lr.getSpanId))
          if lr.getEventName.nonEmpty then f("event_name") = Value.Str(lr.getEventName)
          out += Row(micros(lr.getTimeUnixNano, micros(lr.getObservedTimeUnixNano, now)), f.toMap)
        }
      }
    }
    out.result()
  }

  // ---------------------------------------------------------------- トレース

  private def kindName(
    s: Span
  ): String = s.getKind match {
    case Span.SpanKind.SPAN_KIND_INTERNAL => "INTERNAL"
    case Span.SpanKind.SPAN_KIND_SERVER => "SERVER"
    case Span.SpanKind.SPAN_KIND_CLIENT => "CLIENT"
    case Span.SpanKind.SPAN_KIND_PRODUCER => "PRODUCER"
    case Span.SpanKind.SPAN_KIND_CONSUMER => "CONSUMER"
    case _ => "UNSPECIFIED"
  }

  private def statusName(
    s: Status
  ): String = s.getCode match {
    case Status.StatusCode.STATUS_CODE_OK => "OK"
    case Status.StatusCode.STATUS_CODE_ERROR => "ERROR"
    case _ => "UNSET"
  }

  private def spanRows(
    r: ExportTraceServiceRequest,
    now: Long
  ): Vector[Row] = {
    val out = Vector.newBuilder[Row]
    r.getResourceSpansList.asScala.foreach { rs =>
      val res = mutable.LinkedHashMap.empty[String, Value]
      addAttrs(res, rs.getResource.getAttributesList)
      rs.getScopeSpansList.asScala.foreach { ss =>
        ss.getSpansList.asScala.foreach { sp =>
          val f = mutable.LinkedHashMap.empty[String, Value]
          f ++= res
          addAttrs(f, sp.getAttributesList)
          if ss.hasScope && ss.getScope.getName.nonEmpty then f("scope_name") = Value.Str(ss.getScope.getName)
          val start = micros(sp.getStartTimeUnixNano, now)
          val end = math.max(start, micros(sp.getEndTimeUnixNano, start))
          f("trace_id") = Value.Str(idHex(sp.getTraceId))
          f("span_id") = Value.Str(idHex(sp.getSpanId))
          f("parent_span_id") = Value.Str(idHex(sp.getParentSpanId))
          f("operation_name") = Value.Str(sp.getName)
          f("span_kind") = Value.Str(kindName(sp))
          f("end_time") = Value.Int(end)
          f("duration") = Value.Int(end - start)
          f("status_code") = Value.Str(statusName(sp.getStatus))
          if sp.getStatus.getMessage.nonEmpty then f("status_message") = Value.Str(sp.getStatus.getMessage)
          if !f.contains("service_name") then f("service_name") = Value.Str("unknown")
          if sp.getEventsCount > 0 then
            f("events") = Value.Str(Json.fromValues(sp.getEventsList.asScala.map { e =>
              Json.obj(
                "time" -> Json.fromLong(micros(e.getTimeUnixNano, start)),
                "name" -> Json.fromString(e.getName),
                "attributes" ->
                  Json.fromFields(e.getAttributesList.asScala.map(kv => kv.getKey -> anyJson(kv.getValue)))
              )
            }).noSpaces)
          if sp.getLinksCount > 0 then
            f("links") = Value.Str(Json.fromValues(sp.getLinksList.asScala.map { l =>
              Json.obj(
                "trace_id" -> Json.fromString(idHex(l.getTraceId)),
                "span_id" -> Json.fromString(idHex(l.getSpanId))
              )
            }).noSpaces)
          out += Row(start, f.toMap)
        }
      }
    }
    out.result()
  }

  // ---------------------------------------------------------------- メトリクス

  private def metricRows(
    r: ExportMetricsServiceRequest,
    now: Long
  ): Batch = {
    val rows = mutable.LinkedHashMap.empty[String, Vector[Row]]
    val meta = mutable.LinkedHashMap.empty[String, MetricMeta]
    def add(
      name: String,
      typ: String,
      ts: Long,
      labels: collection.Map[String, Value],
      value: Double,
      m: Metric,
      metaType: String
    ): Unit =
      Names.metric(name).foreach { s =>
        val f = labels.toMap ++
          Map("__name__" -> Value.Str(s), "__type__" -> Value.Str(typ), "value" -> Value.Dbl(value))
        rows(s) = rows.getOrElse(s, Vector.empty) :+ Row(ts, f)
        if !meta.contains(s) then meta(s) = MetricMeta(m.getDescription.take(1000), m.getUnit.take(50), metaType)
      }
    r.getResourceMetricsList.asScala.foreach { rm =>
      val res = mutable.LinkedHashMap.empty[String, Value]
      addAttrs(res, rm.getResource.getAttributesList)
      rm.getScopeMetricsList.asScala.foreach { sm =>
        sm.getMetricsList.asScala.foreach { m =>
          def labels(
            attrs: java.util.List[KeyValue]
          ): mutable.LinkedHashMap[String, Value] = {
            val l = mutable.LinkedHashMap.empty[String, Value]
            l ++= res
            addAttrs(l, attrs)
            l.remove("value"); l.remove("__name__"); l.remove("__type__")
            l
          }
          def num(
            p: NumberDataPoint
          ): Double = if p.getValueCase == NumberDataPoint.ValueCase.AS_INT then p.getAsInt.toDouble else p.getAsDouble
          val name = m.getName
          m.getDataCase match {
            case Metric.DataCase.GAUGE =>
              m.getGauge.getDataPointsList.asScala.foreach(p =>
                add(name, "gauge", micros(p.getTimeUnixNano, now), labels(p.getAttributesList), num(p), m, "gauge")
              )
            case Metric.DataCase.SUM =>
              val s = m.getSum
              val typ =
                if !s.getIsMonotonic then "updowncounter"
                else if s.getAggregationTemporality == AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA then
                  "delta_counter"
                else "counter"
              s.getDataPointsList.asScala.foreach(p =>
                add(name, typ, micros(p.getTimeUnixNano, now), labels(p.getAttributesList), num(p), m, typ)
              )
            case Metric.DataCase.HISTOGRAM =>
              m.getHistogram.getDataPointsList.asScala.foreach { p =>
                val ts = micros(p.getTimeUnixNano, now)
                val l = labels(p.getAttributesList)
                val bounds = p.getExplicitBoundsList.asScala.map(_.doubleValue).toVector
                val counts = p.getBucketCountsList.asScala.map(_.longValue).toVector
                // Prometheus と同じく、le 以下の累積の件数にする
                var acc = 0L
                counts.zipWithIndex.foreach {
                  (
                    c,
                    i
                  ) =>
                    acc += c
                    val le = bounds.lift(i).map(AlertLogic.fmt).getOrElse("+Inf")
                    add(
                      name + "_bucket",
                      "histogram",
                      ts,
                      l.clone() += ("le" -> Value.Str(le)),
                      acc.toDouble,
                      m,
                      "histogram"
                    )
                }
                if p.hasSum then add(name + "_sum", "histogram", ts, l, p.getSum, m, "histogram")
                add(name + "_count", "histogram", ts, l, p.getCount.toDouble, m, "histogram")
              }
            case Metric.DataCase.EXPONENTIAL_HISTOGRAM =>
              m.getExponentialHistogram.getDataPointsList.asScala.foreach { p =>
                val ts = micros(p.getTimeUnixNano, now)
                val l = labels(p.getAttributesList)
                if p.hasSum then add(name + "_sum", "histogram", ts, l, p.getSum, m, "histogram")
                add(name + "_count", "histogram", ts, l, p.getCount.toDouble, m, "histogram")
                if p.hasMin then add(name + "_min", "histogram", ts, l, p.getMin, m, "histogram")
                if p.hasMax then add(name + "_max", "histogram", ts, l, p.getMax, m, "histogram")
              }
            case Metric.DataCase.SUMMARY =>
              m.getSummary.getDataPointsList.asScala.foreach { p =>
                val ts = micros(p.getTimeUnixNano, now)
                val l = labels(p.getAttributesList)
                p.getQuantileValuesList.asScala.foreach(q =>
                  add(
                    name,
                    "summary",
                    ts,
                    l.clone() += ("quantile" -> Value.Str(AlertLogic.fmt(q.getQuantile))),
                    q.getValue,
                    m,
                    "summary"
                  )
                )
                add(name + "_sum", "summary", ts, l, p.getSum, m, "summary")
                add(name + "_count", "summary", ts, l, p.getCount.toDouble, m, "summary")
              }
            case _ => ()
          }
        }
      }
    }
    Batch(rows.toMap, meta.toMap)
  }
}
