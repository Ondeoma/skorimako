package skorimako.api

import com.google.protobuf.{ByteString, CodedOutputStream}
import io.grpc.{ManagedChannelBuilder, Metadata}
import io.grpc.stub.MetadataUtils
import io.opentelemetry.proto.collector.logs.v1.{ExportLogsServiceRequest, LogsServiceGrpc}
import io.opentelemetry.proto.common.v1.{AnyValue, KeyValue}
import io.opentelemetry.proto.logs.v1.{LogRecord, ResourceLogs, ScopeLogs}
import io.opentelemetry.proto.resource.v1.Resource

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * 開発用の送り手: OTLP/gRPC でログを1件、Prometheus の remote write でカウンターを送る。
 * 使い方: sbt "api/Test/runMain skorimako.api.SendSmoke <APIキー> [gRPC のポート] [HTTP の URL]"
 */
object SendSmoke:

  private def kv(k: String, v: String) = KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setStringValue(v)).build()

  /** WriteRequest（labels・samples だけ）を組み立てる。 */
  private def writeRequest(series: List[(Map[String, String], List[(Double, Long)])]): Array[Byte] =
    def msg(f: CodedOutputStream => Unit): Array[Byte] =
      val bo = new ByteArrayOutputStream(); val o = CodedOutputStream.newInstance(bo); f(o); o.flush(); bo.toByteArray
    msg { o =>
      series.foreach { (labels, samples) =>
        o.writeByteArray(1, msg { ts =>
          labels.toList.sorted.foreach((k, v) => ts.writeByteArray(1, msg { l => l.writeString(1, k); l.writeString(2, v) }))
          samples.foreach((v, t) => ts.writeByteArray(2, msg { s => s.writeDouble(1, v); s.writeInt64(2, t) }))
        })
      }
      // metadata: counter
      o.writeByteArray(3, msg { m => m.writeEnum(1, 1); m.writeString(2, "demo_jobs_total"); m.writeString(4, "処理したジョブ") })
    }

  def main(args: Array[String]): Unit =
    val key  = args(0)
    val port = args.lift(1).map(_.toInt).getOrElse(4319)
    val base = args.lift(2).getOrElse("http://localhost:8101")

    // ---- OTLP/gRPC
    val ch = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
    val md = new Metadata()
    md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), s"Bearer $key")
    md.put(Metadata.Key.of("stream-name", Metadata.ASCII_STRING_MARSHALLER), "grpc")
    val stub = LogsServiceGrpc.newBlockingStub(ch).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md))
    val now = System.currentTimeMillis()
    stub.`export`(ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
      .setResource(Resource.newBuilder().addAttributes(kv("service.name", "grpc-demo")))
      .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder().setTimeUnixNano(now * 1000000)
        .setSeverityText("INFO").setBody(AnyValue.newBuilder().setStringValue("hello via grpc, contact me at taro@example.com"))))).build())
    println("grpc: ok")
    // 認証なしは断られる
    try
      LogsServiceGrpc.newBlockingStub(ch).`export`(ExportLogsServiceRequest.getDefaultInstance)
      println("grpc without key: NG (accepted)")
    catch case e: io.grpc.StatusRuntimeException => println(s"grpc without key: ${e.getStatus.getCode}")
    ch.shutdownNow()

    // ---- remote write（5分ぶん、15秒ごと）
    val pts = (0 until 20).map(i => ((i * 15).toDouble + 100, now - (19 - i) * 15000L)).toList
    val body = writeRequest(List(
      Map("__name__" -> "demo_jobs_total", "job" -> "worker", "instance" -> "a") -> pts,
      Map("__name__" -> "demo_jobs_total", "job" -> "worker", "instance" -> "b") -> pts.map((v, t) => (v * 2, t))))
    val comp = new io.airlift.compress.snappy.SnappyCompressor()
    val out = new Array[Byte](comp.maxCompressedLength(body.length))
    val n = comp.compress(body, 0, body.length, out, 0, out.length)
    val req = HttpRequest.newBuilder(URI.create(s"$base/api/prom/api/v1/write"))
      .header("Authorization", s"Bearer $key").header("Content-Encoding", "snappy").header("Content-Type", "application/x-protobuf")
      .POST(HttpRequest.BodyPublishers.ofByteArray(java.util.Arrays.copyOf(out, n))).build()
    val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    println(s"remote write: ${res.statusCode()} ${res.body()}")
