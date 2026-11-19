package skorimako.engine

import io.circe.parser.parse
import skorimako.core.*

class OtlpSpec extends munit.FunSuite {

  test("OTLP/JSON のログ: 属性は列に、traceId は16進数のまま") {
    val body =
      """{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api"}}]},
        |"scopeLogs":[{"scope":{"name":"app"},"logRecords":[{"timeUnixNano":"1700000000000000000","severityNumber":17,
        |"body":{"stringValue":"boom"},"traceId":"5b8efff798038103d269b633813fc60c","spanId":"eee19b7ec3c1b174",
        |"attributes":[{"key":"http.status","value":{"intValue":"500"}},{"key":"ctx","value":{"kvlistValue":{"values":[{"key":"a","value":{"boolValue":true}}]}}}]}]}]}]}""".stripMargin
    val b = Otlp.logs(body.getBytes("UTF-8"), json = true, "default", 0L).toOption.get
    val r = b.rows("default").head
    assertEquals(r.ts, 1700000000000000L)
    assertEquals(r.str("service_name"), Some("api"))
    assertEquals(r.str("severity_text"), Some("ERROR"))
    assertEquals(r.str("trace_id"), Some("5b8efff798038103d269b633813fc60c"))
    assertEquals(r.fields.get("http_status"), Some(Value.Int(500)))
    assertEquals(r.fields.get("ctx_a"), Some(Value.Bool(true)))
  }

  test("OTLP/JSON のメトリクス: ヒストグラムは _bucket（累積）・_sum・_count に分ける") {
    val body =
      """{"resourceMetrics":[{"resource":{"attributes":[]},"scopeMetrics":[{"metrics":[{"name":"http.server.duration","unit":"ms",
        |"histogram":{"aggregationTemporality":2,"dataPoints":[{"timeUnixNano":"1700000000000000000","count":"3","sum":30,
        |"bucketCounts":["1","2","0"],"explicitBounds":[10,100]}]}}]}]}]}""".stripMargin
    val b = Otlp.metrics(body.getBytes("UTF-8"), json = true, 0L).toOption.get
    val buckets = b.rows("http_server_duration_bucket").map(r => r.str("le").get -> r.fields("value"))
    assertEquals(buckets.toList, List("10" -> Value.Dbl(1), "100" -> Value.Dbl(3), "+Inf" -> Value.Dbl(3)))
    assertEquals(b.rows("http_server_duration_count").head.fields("value"), Value.Dbl(3))
    assertEquals(b.meta("http_server_duration_sum").unit, "ms")
  }

  test("JSON のログ: 時刻の桁を見分け、入れ子は _ でつなぐ") {
    val rows =
      JsonRows.logs(parse("""[{"ts": 1700000000123, "a": {"b": 1}, "msg": "x"}]""").toOption.get, 0L, 10).toOption.get
    assertEquals(rows.head.ts, 1700000000123000L)
    assertEquals(rows.head.fields.get("a_b"), Some(Value.Int(1)))
  }

  test("SQL の検査") {
    assert(SqlText.checkQuery("select * from \"default\";").isRight)
    assert(SqlText.checkQuery("select 1; drop table x").isLeft)
    assert(SqlText.checkQuery("delete from x").isLeft)
    assertEquals(SqlText.checkQuery("SELECT * FROM Logs1 JOIN \"My\" ON 1=1").map(_._2.contains("logs1")), Right(true))
    assert(SqlText.checkCondition("a = 1) or (1=1").isLeft)
    assert(SqlText.checkCondition("a = ';'").isRight)
  }
}

class OtlpProtobufSpec extends munit.FunSuite {
  import com.google.protobuf.ByteString
  import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
  import io.opentelemetry.proto.common.v1.{AnyValue, KeyValue}
  import io.opentelemetry.proto.resource.v1.Resource
  import io.opentelemetry.proto.trace.v1.{ResourceSpans, ScopeSpans, Span, Status}

  private def kv(k: String, v: String) =
    KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setStringValue(v)).build()

  test("OTLP/protobuf のスパン: 所要時間・状態・サービス名、トレースの索引") {
    val trace = ByteString.copyFrom(Array.tabulate[Byte](16)(i => (i + 1).toByte))
    def span(id: Int, parent: Option[Int], name: String, start: Long, end: Long, err: Boolean) = {
      val b =
        Span.newBuilder().setTraceId(trace).setSpanId(ByteString.copyFrom(Array.fill[Byte](8)(id.toByte))).setName(name)
          .setKind(Span.SpanKind.SPAN_KIND_SERVER).setStartTimeUnixNano(start).setEndTimeUnixNano(end)
      parent.foreach(p => b.setParentSpanId(ByteString.copyFrom(Array.fill[Byte](8)(p.toByte))))
      if err then b.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))
      b.build()
    }
    val req = ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
      .setResource(Resource.newBuilder().addAttributes(kv("service.name", "api")))
      .addScopeSpans(ScopeSpans.newBuilder()
        .addSpans(span(1, None, "GET /", 1000000000L, 1500000000L, err = true))
        .addSpans(span(2, Some(1), "db", 1100000000L, 1200000000L, err = false)))).build()
    val rows = Otlp.traces(req.toByteArray, json = false, "default", 0L).toOption.get.rows("default")
    assertEquals(rows.size, 2)
    val root = rows.find(_.str("operation_name").contains("GET /")).get
    assertEquals(root.ts, 1000000L)
    assertEquals(root.fields("duration"), skorimako.core.Value.Int(500000L))
    assertEquals(root.str("status_code"), Some("ERROR"))
    assertEquals(root.str("parent_span_id"), Some(""))
    assertEquals(root.str("trace_id"), Some("0102030405060708090a0b0c0d0e0f10"))
    val parts = Ingester.traceParts(rows)
    assertEquals(parts.map(p => (p.rootName, p.spans, p.errors, p.maxTs - p.minTs)), List(("GET /", 2, 1, 500000L)))
  }

  test("壊れた protobuf は Left") {
    assert(Otlp.logs(Array[Byte](1, 2, 3, 4, 5), json = false, "default", 0L).isLeft)
  }
}
