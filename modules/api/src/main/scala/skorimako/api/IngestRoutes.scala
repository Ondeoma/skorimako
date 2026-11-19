package skorimako.api

import cats.effect.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.io.ByteArrayInputStream
import java.util.zip.{GZIPInputStream, InflaterInputStream}

object IngestRoutes {
  private val Protobuf = MediaType.unsafeParse("application/x-protobuf")

  /**
   * OTLP の応答（protobuf なら空のメッセージ＝本文 0 バイト、JSON なら {}）。
   * 本文に Array[Byte] を渡すと circe の EntityEncoder で JSON の配列 [] になり、Collector が protobuf として読めないので、本文は付けない.
   */
  def otlpOk(
    json: Boolean
  ): IO[Response[IO]] =
    if json then IO.pure(Response[IO](Status.Ok).withEntity(Json.obj()))
    else IO.pure(Response[IO](Status.Ok).withContentType(`Content-Type`(Protobuf)))
}

final class IngestRoutes(
  ingest: IngestService,
  apps: AppStore,
  val sessions: SessionAuth,
  cfg: ApiConfig) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "v1" / "logs" => postV1Logs(req)
    case req @ POST -> Root / "v1" / "traces" => postV1Traces(req)
    case req @ POST -> Root / "v1" / "metrics" => postV1Metrics(req)
    case req @ POST -> Root / "api" / "ingest" / name / kind if kind == "_json" || kind == "_multi" =>
      postIngest(req, name, kind)
    case req @ OPTIONS -> Root / "rum" / "v1" / _ => optionsRumV1(req)
    case req @ POST -> Root / "rum" / "v1" / token => postRumV1(req, token)
  }

  private def postV1Logs(
    req: Request[IO]
  ): IO[Response[IO]] =
    otlp(req, StreamType.Logs)(
      (
        b,
        j,
        s,
        now
      ) => Otlp.logs(b, j, s, now)
    )

  private def postV1Traces(
    req: Request[IO]
  ): IO[Response[IO]] =
    otlp(req, StreamType.Traces)(
      (
        b,
        j,
        s,
        now
      ) => Otlp.traces(b, j, s, now)
    )

  private def postV1Metrics(
    req: Request[IO]
  ): IO[Response[IO]] =
    otlp(req, StreamType.Metrics)(
      (
        b,
        j,
        _,
        now
      ) => Otlp.metrics(b, j, now)
    )

  private def postIngest(
    req: Request[IO],
    name: String,
    kind: String
  ): IO[Response[IO]] =
    ingestAuth(req) { _ =>
      Names.stream(name) match {
        case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
        case Right(stream) =>
          bytes(req).flatMap {
            case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
            case Right(b) =>
              val text = new String(b, java.nio.charset.StandardCharsets.UTF_8)
              IO.realTime.map(_.toMicros).flatMap { now =>
                val rows =
                  if kind == "_multi" then JsonRows.ndjson(text, now, cfg.maxIngestRows)
                  else
                    io.circe.parser.parse(text).left.map(e => s"JSON として読めません: ${e.message}").flatMap(JsonRows.logs(
                      _,
                      now,
                      cfg.maxIngestRows
                    ))
                rows match {
                  case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
                  case Right(rs) => accept(StreamType.Logs, Batch(Map(stream -> rs)))(n =>
                      Ok(Json.obj("accepted" -> Json.fromInt(n)))
                    )
                }
              }
          }
      }
    }

  private def optionsRumV1(
    req: Request[IO]
  ): IO[Response[IO]] =
    IO.pure(Response[IO](Status.NoContent).putHeaders(cors(req.headers.get(CIString("Origin")).map(_.head.value))*))

  private def postRumV1(
    req: Request[IO],
    token: String
  ): IO[Response[IO]] = {
    val origin = req.headers.get(CIString("Origin")).map(_.head.value)
    apps.rumApp(token).flatMap {
      case None => err(ErrorCode.RumAppNotFound)
      case Some(app) if !originAllowed(app, origin) => err(ErrorCode.RumOriginNotAllowed)
      case Some(app) =>
        bytes(req).flatMap {
          case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
          case Right(b) =>
            val ua = req.headers.get(CIString("User-Agent")).map(_.head.value).getOrElse("")
            IO.realTime.map(_.toMicros).flatMap { now =>
              io.circe.parser.parse(new String(b, java.nio.charset.StandardCharsets.UTF_8)).left.map(_ =>
                "JSON として読めません"
              )
                .flatMap(JsonRows.rum(_, app.name, ua, now, cfg.maxIngestRows)) match {
                case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
                case Right(rows) =>
                  accept(StreamType.Rum, Batch(Map(app.stream -> rows)))(_ => IO.pure(Response[IO](Status.NoContent)))
              }
            }
        }.map(_.putHeaders(cors(origin)*))
    }
  }

  /** 本文（gzip・deflate なら展開する。展開後の大きさにも上限）。 */
  def bytes(
    req: Request[IO]
  ): IO[Either[String, Array[Byte]]] =
    req.body.compile.to(Array).flatMap { raw =>
      val enc = req.headers.get(CIString("Content-Encoding")).map(_.head.value.trim.toLowerCase).getOrElse("")
      if enc.isEmpty || enc == "identity" then IO.pure(Right(raw))
      else if enc != "gzip" && enc != "deflate" then IO.pure(Left(s"Content-Encoding $enc には対応していません（gzip・deflate）"))
      else
        IO.blocking {
          val in = if enc == "gzip" then new GZIPInputStream(new ByteArrayInputStream(raw))
          else new InflaterInputStream(new ByteArrayInputStream(raw))
          val out = new java.io.ByteArrayOutputStream()
          val buf = new Array[Byte](65536)
          var n = 0; var total = 0L
          while { n = in.read(buf); n > 0 && total <= cfg.maxInflatedBytes } do {
            out.write(buf, 0, n); total += n
          }
          if total > cfg.maxInflatedBytes then Left("展開した本文が大きすぎます") else Right(out.toByteArray)
        }.handleError(_ => Left("圧縮された本文を展開できません"))
    }

  private def isJson(
    req: Request[IO]
  ): Boolean =
    req.contentType.exists(ct => ct.mediaType.subType.contains("json"))

  private def streamName(
    req: Request[IO]
  ): Either[String, String] =
    Names.stream(req.headers.get(
      CIString("stream-name")
    ).orElse(req.headers.get(CIString("X-Skorimako-Stream"))).map(_.head.value).getOrElse("default"))

  /** 取り込める人だけ。 */
  private def ingestAuth(
    req: Request[IO]
  )(
    f: AuthSession => IO[Response[IO]]
  ): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(ErrorCode.IngestApiKeyRequired)
      case Some(s) if !s.canIngest => err(ErrorCode.IngestScopeRequired)
      case Some(s) => f(s)
    }

  /** 行をためる。たまりすぎていたら 503（送り手が再送する）。 */
  private def accept(
    streamType: String,
    batch: Batch
  )(
    ok: Int => IO[Response[IO]]
  ): IO[Response[IO]] =
    ingest.ingest(streamType, batch).flatMap {
      case Right(n) => ok(n)
      case Left(r) =>
        fail(r).map(_.putHeaders("Retry-After" -> "5"))
    }

  private def otlp(
    req: Request[IO],
    streamType: String
  )(
    parse: (Array[Byte], Boolean, String, Long) => Either[String, Batch]
  ): IO[Response[IO]] =
    ingestAuth(req) { _ =>
      streamName(req) match {
        case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
        case Right(stream) =>
          bytes(req).flatMap {
            case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
            case Right(b) =>
              val json = isJson(req)
              IO.realTime.map(_.toMicros).flatMap(now => IO(parse(b, json, stream, now))).flatMap {
                case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
                case Right(batch) => accept(streamType, batch)(_ => IngestRoutes.otlpOk(json))
              }
          }
      }
    }

  private def cors(
    origin: Option[String]
  ): List[Header.ToRaw] =
    List[Header.ToRaw](
      "Access-Control-Allow-Origin" -> origin.getOrElse("*"),
      "Access-Control-Allow-Methods" -> "POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type",
      "Access-Control-Max-Age" -> "86400",
      "Vary" -> "Origin"
    )

  private def originAllowed(
    app: RumAppDto,
    origin: Option[String]
  ): Boolean =
    app.origins.isEmpty || app.origins.contains("*") ||
      origin.exists(o => app.origins.exists(_.stripSuffix("/").equalsIgnoreCase(o)))

}
