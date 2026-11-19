package skorimako.api

import cats.effect.*
import cats.effect.std.Dispatcher
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.opentelemetry.proto.collector.logs.v1.{ExportLogsServiceRequest, ExportLogsServiceResponse, LogsServiceGrpc}
import io.opentelemetry.proto.collector.metrics.v1.{
  ExportMetricsServiceRequest, ExportMetricsServiceResponse, MetricsServiceGrpc
}
import io.opentelemetry.proto.collector.trace.v1.{
  ExportTraceServiceRequest, ExportTraceServiceResponse, TraceServiceGrpc
}
import skorimako.core.Names
import skorimako.engine.*
import skorimako.shared.StreamType

import java.util.concurrent.TimeUnit

/**
 * OTLP/gRPC（既定の 4317 番）。認証はメタデータの authorization（Bearer の APIキー、ingest か write）。
 * ストリームはメタデータの stream-name（既定 default）。gzip は gRPC の標準の圧縮として受ける。
 */
object GrpcIngest {

  private val AuthKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
  private val StreamKey = Metadata.Key.of("stream-name", Metadata.ASCII_STRING_MARSHALLER)
  private val CtxToken = Context.key[String]("skorimako-token")
  private val CtxStream = Context.key[String]("skorimako-stream")

  /** メタデータをコンテキストに移す（サービスの中から読むため）。 */
  private object Capture extends ServerInterceptor {
    def interceptCall[Q, R](
      call: ServerCall[Q, R],
      headers: Metadata,
      next: ServerCallHandler[Q, R]
    ): ServerCall.Listener[Q] = {
      val token = Option(headers.get(AuthKey)).map(_.trim).map(h =>
        if h.toLowerCase.startsWith("bearer ") then h.drop(7).trim else h
      ).getOrElse("")
      val ctx = Context.current().withValue(
        CtxToken,
        token
      ).withValue(CtxStream, Option(headers.get(StreamKey)).getOrElse("default"))
      Contexts.interceptCall(ctx, call, headers, next)
    }
  }

  def resource(
    port: Int,
    ingest: IngestService,
    sessions: SessionAuth,
    maxBytes: Int
  ): Resource[IO, Unit] =
    Dispatcher.parallel[IO].flatMap { d =>
      /** 認証してから取り込み、gRPC の状態で応える。 */
      def handle[Res](
        streamType: String,
        observer: StreamObserver[Res],
        ok: Res
      )(
        parse: (String, Long) => Either[String, Batch]
      ): Unit = {
        val token = CtxToken.get()
        val stream = Names.stream(CtxStream.get())
        val io = sessions.fromKey(token).flatMap {
          case None =>
            IO.pure(Left(Status.UNAUTHENTICATED.withDescription("APIキー（ingest スコープ）を authorization で送ってください")))
          case Some(s) if !s.canIngest => IO.pure(Left(Status.PERMISSION_DENIED.withDescription("このAPIキーでは取り込めません")))
          case Some(_) =>
            stream match {
              case Left(e) => IO.pure(Left(Status.INVALID_ARGUMENT.withDescription(e)))
              case Right(st) =>
                IO.realTime.map(_.toMicros).flatMap(now => IO(parse(st, now))).flatMap {
                  case Left(e) => IO.pure(Left(Status.INVALID_ARGUMENT.withDescription(e)))
                  case Right(b) => ingest.ingest(streamType, b).map(_.left.map(r =>
                      (if r.status == 429 then Status.RESOURCE_EXHAUSTED else Status.UNAVAILABLE).withDescription(
                        r.message
                      )
                    ))
                }
            }
        }
        d.unsafeRunAndForget(io.attempt.map {
          case Right(Right(_)) => observer.onNext(ok); observer.onCompleted()
          case Right(Left(st)) => observer.onError(st.asRuntimeException())
          case Left(e) => observer.onError(Status.INTERNAL.withDescription("取り込めませんでした").asRuntimeException())
        })
      }

      val logs = new LogsServiceGrpc.LogsServiceImplBase {
        override def `export`(
          req: ExportLogsServiceRequest,
          obs: StreamObserver[ExportLogsServiceResponse]
        ): Unit =
          handle(StreamType.Logs, obs, ExportLogsServiceResponse.getDefaultInstance)(
            (
              st,
              now
            ) => Right(Otlp.logsOf(req, st, now))
          )
      }
      val traces = new TraceServiceGrpc.TraceServiceImplBase {
        override def `export`(
          req: ExportTraceServiceRequest,
          obs: StreamObserver[ExportTraceServiceResponse]
        ): Unit =
          handle(StreamType.Traces, obs, ExportTraceServiceResponse.getDefaultInstance)(
            (
              st,
              now
            ) => Right(Otlp.tracesOf(req, st, now))
          )
      }
      val metrics = new MetricsServiceGrpc.MetricsServiceImplBase {
        override def `export`(
          req: ExportMetricsServiceRequest,
          obs: StreamObserver[ExportMetricsServiceResponse]
        ): Unit =
          handle(StreamType.Metrics, obs, ExportMetricsServiceResponse.getDefaultInstance)(
            (
              _,
              now
            ) => Right(Otlp.metricsOf(req, now))
          )
      }

      Resource.make(IO.blocking {
        NettyServerBuilder.forPort(port)
          .maxInboundMessageSize(maxBytes)
          .addService(ServerInterceptors.intercept(logs, Capture))
          .addService(ServerInterceptors.intercept(traces, Capture))
          .addService(ServerInterceptors.intercept(metrics, Capture))
          .build().start()
      })(s => IO.blocking { s.shutdown(); s.awaitTermination(5, TimeUnit.SECONDS) }.void)
        .evalMap(_ => sbirds.observe.Log.println(s"[skorimako] OTLP/gRPC listening on :$port"))
    }
}
