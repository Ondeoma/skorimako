package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

/**
 * Prometheus との互換。
 *  - remote write: POST /api/prom/push・/api/prom/api/v1/write（snappy の protobuf。ingest か write のキー）
 *  - HTTP API（Grafana の Prometheus のデータソースの URL を {skorimako}/api/prom にする）:
 *    /api/v1/query・query_range・labels・label/{name}/values・series・metadata・status/buildinfo
 */
final class PromRoutes(ingest: IngestService,
                       engine: QueryEngine,
                       streams: StreamStore,
                       val sessions: SessionAuth,
                       cfg: ApiConfig)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "prom" / "push" => postPromPush(req)
    case req @ POST -> Root / "api" / "prom" / "api" / "v1" / "write" => postPromV1Write(req)
    case req @ (GET | POST) -> Root / "api" / "prom" / "api" / "v1" / "query" => getPromV1Query(req)
    case req @ (GET | POST) -> Root / "api" / "prom" / "api" / "v1" / "query_range" => getPromV1QueryRange(req)
    case req @ (GET | POST) -> Root / "api" / "prom" / "api" / "v1" / "labels" => getPromV1Labels(req)
    case req @ (GET | POST) -> Root / "api" / "prom" / "api" / "v1" / "label" / name / "values" =>
      getPromV1LabelValues(req, name)
    case req @ (GET | POST) -> Root / "api" / "prom" / "api" / "v1" / "series" => getPromV1Series(req)
    case req @ GET -> Root / "api" / "prom" / "api" / "v1" / "metadata" => getPromV1Metadata(req)
    case req @ GET -> Root / "api" / "prom" / "api" / "v1" / "status" / "buildinfo" => getPromV1StatusBuildinfo(req)
  }

  private def postPromPush(req: Request[IO]): IO[Response[IO]] =
    write(req)

  private def postPromV1Write(req: Request[IO]): IO[Response[IO]] =
    write(req)

  private def getPromV1Query(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      params(req).flatMap { p =>
        (
          first(p, "query").toRight("query を指定してください"),
          first(p, "time").map(time).getOrElse(Right(System.currentTimeMillis()))
        ).tupled match {
          case Left(e) => bad(e)
          case Right((q, t)) => evaluate(s.viewer, q, t, t, 0).flatMap(_.fold(bad(_), r => ok(resultJson(r))))
        }
      }
    )

  private def getPromV1QueryRange(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      params(req).flatMap { p =>
        (for {
          q <- first(p, "query").toRight("query を指定してください")
          st <- first(p, "start").toRight("start を指定してください").flatMap(time)
          en <- first(p, "end").toRight("end を指定してください").flatMap(time)
          sp <- first(p, "step").toRight("step を指定してください").flatMap(step)
        } yield (q, st, en, sp)) match {
          case Left(e) => bad(e)
          case Right((q, st, en, sp)) =>
            evaluate(s.viewer, q, st / sp * sp, en, sp).flatMap(_.fold(bad(_), r => ok(resultJson(r))))
        }
      }
    )

  private def getPromV1Labels(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ =>
      streams.fieldNames(StreamType.Metrics).flatMap(ns =>
        ok(
          Json.fromValues(ns.filterNot(n => n == Columns.Timestamp || n == "value" || n == "__type__").map(_.asJson))
        )
      )
    )

  private def getPromV1LabelValues(req: Request[IO], name: String): IO[Response[IO]] =
    authed(req)(s =>
      if name == "__name__" then
        streams.list(Some(StreamType.Metrics), None, cfg.retentionDays).flatMap(ss =>
          engine.acl.filter(s.viewer, StreamType.Metrics, ss)(_.name)
        ).flatMap(ss => ok(Json.fromValues(ss.map(_.name.asJson))))
      else streams.tagValues(StreamType.Metrics, name, 10000).flatMap(vs => ok(Json.fromValues(vs.map(_.asJson))))
    )

  private def getPromV1Series(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      params(req).flatMap { p =>
        val now = System.currentTimeMillis()
        val st = first(p, "start").flatMap(x => time(x).toOption).getOrElse(now - 3600000)
        val en = first(p, "end").flatMap(x => time(x).toOption).getOrElse(now)
        val matches = p.getOrElse("match[]", Nil)
        if matches.isEmpty then bad("match[] を指定してください")
        else
          matches.traverse(m =>
            evaluate(s.viewer, s"last_over_time($m[${math.max(1, (en - st) / 1000)}s])", en, en, 0)
          ).flatMap { rs =>
            rs.collectFirst { case Left(e) => e } match {
              case Some(e) => bad(e)
              case None =>
                val ls = rs.collect { case Right(Prom.Result.Vec(_, xs)) => xs.map(_._1) }.flatten.distinct
                ok(Json.fromValues(ls.map(labelsJson)))
            }
          }
      }
    )

  private def getPromV1Metadata(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      streams.list(Some(StreamType.Metrics), None, cfg.retentionDays).flatMap(ss =>
        engine.acl.filter(s.viewer, StreamType.Metrics, ss)(_.name)
      )
        .flatMap(ss =>
          ok(Json.fromFields(ss.map(m =>
            m.name -> Json.arr(Json.obj(
              "type" ->
                (m.metricType match {
                  case "counter" | "delta_counter" => "counter"
                  case "" | "prometheus" => "unknown"
                  case t => t
                }).asJson,
              "help" -> m.description.asJson,
              "unit" -> m.unit.asJson
            ))
          )))
        )
    )

  private def getPromV1StatusBuildinfo(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ => ok(Json.obj("version" -> "2.53.0".asJson, "application" -> s"skorimako ${cfg.version}".asJson)))

  private def ok(data: Json): IO[Response[IO]] = Ok(Json.obj("status" -> "success".asJson, "data" -> data))
  private def bad(msg: String, status: Status = Status.BadRequest, kind: String = "bad_data"): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(Json.obj(
      "status" -> "error".asJson,
      "errorType" -> kind.asJson,
      "error" -> msg.asJson
    )))

  /** パラメーター（クエリと、POST のフォームの両方）。 */
  private def params(req: Request[IO]): IO[Map[String, List[String]]] = {
    val q = req.multiParams.view.mapValues(_.toList).toMap
    if req.method == Method.POST then
      req.as[UrlForm].attempt.map(_.fold(_ => q, f => q ++ f.values.view.mapValues(_.toList).toMap))
    else IO.pure(q)
  }

  /** 時刻（UNIX 秒の小数か RFC3339）をミリ秒に。 */
  private def time(s: String): Either[String, Long] =
    s.toDoubleOption.map(d => (d * 1000).toLong)
      .orElse(scala.util.Try(java.time.OffsetDateTime.parse(s).toInstant.toEpochMilli).toOption)
      .toRight(s"時刻 $s が読めません")

  private def step(s: String): Either[String, Long] =
    s.toDoubleOption.map(d => (d * 1000).toLong).map(Right(_)).getOrElse(PromQL.duration(s)).flatMap(ms =>
      Either.cond(ms > 0, ms, "step は正の値にしてください")
    )

  private def fmt(v: Double): String =
    if v.isNaN then "NaN"
    else if v.isPosInfinity then "+Inf"
    else if v.isNegInfinity then "-Inf" else BigDecimal(v).bigDecimal.stripTrailingZeros.toPlainString

  private def labelsJson(l: Prom.Labels): Json = Json.fromFields(l.toList.sortBy(_._1).map((k, v) => k -> v.asJson))

  /** 時刻は UNIX 秒（指数表記にしない）。 */
  private def secs(t: Long): Json = Json.fromBigDecimal(BigDecimal(t) / 1000)
  private def pt(t: Long, v: Double): Json = Json.arr(secs(t), fmt(v).asJson)

  private def resultJson(r: Prom.Result): Json = r match {
    case Prom.Result.Matrix(ss) =>
      Json.obj(
        "resultType" -> "matrix".asJson,
        "result" -> Json.fromValues(ss.map((l, ps) =>
          Json.obj("metric" -> labelsJson(l), "values" -> Json.fromValues(ps.map(pt)))
        ))
      )
    case Prom.Result.Vec(t, xs) =>
      Json.obj(
        "resultType" -> "vector".asJson,
        "result" -> Json.fromValues(xs.map((l, v) => Json.obj("metric" -> labelsJson(l), "value" -> pt(t, v))))
      )
    case Prom.Result.Scal(t, v) => Json.obj("resultType" -> "scalar".asJson, "result" -> pt(t, v))
    case Prom.Result.Strg(t, s) => Json.obj("resultType" -> "string".asJson, "result" -> Json.arr(secs(t), s.asJson))
  }

  /** PromQL を評価する（start・end・step はミリ秒。step が 0 なら時刻 end の問い合わせ）。 */
  def evaluate(v: Viewer, q: String, start: Long, end: Long, stepMs: Long): IO[Either[String, Prom.Result]] =
    (if q.length > PromRoutes.MaxQueryLength then Left(s"式が長すぎます（${PromRoutes.MaxQueryLength} 文字まで）")
     else PromQL.parse(q)) match {
      case Left(e) => IO.pure(Left(e))
      case Right(expr) =>
        if stepMs > 0 && (end - start) / stepMs > 11000 then IO.pure(Left("点が多すぎます（11000 まで）。step を大きくしてください"))
        else
          engine.promData(v, expr, start, end).flatMap {
            case Left(e) => IO.pure(Left(e))
            case Right(data) =>
              IO.blocking(if stepMs > 0 then Prom.range(expr, data, start, end, stepMs)
              else Prom.instant(expr, data, end)).attempt.flatMap {
                case Right(r) => IO.pure(Right(r))
                case Left(e: QueryError) => IO.pure(Left(e.message))
                // 予期しない失敗の中身はログにだけ出す
                case Left(e) => QueryEngine.internal(ErrorCode.PromEvalFailed, e).map(Left(_))
              }
          }
    }

  private def first(p: Map[String, List[String]], k: String): Option[String] =
    p.get(k).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def write(req: Request[IO]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(ErrorCode.IngestPromApiKeyRequired)
      case Some(s) if !s.canIngest => err(ErrorCode.IngestScopeRequired)
      case Some(_) =>
        req.body.compile.to(Array).flatMap { raw =>
          val snappy = req.headers.get(CIString("Content-Encoding")).forall(_.head.value.equalsIgnoreCase("snappy"))
          IO.realTime.map(_.toMicros).flatMap { now =>
            (if snappy then PromWrite.decompress(raw) else Right(raw)).flatMap(PromWrite.parse(_, now)) match {
              case Left(e) => err(ErrorCode.IngestPayloadInvalid, e)
              case Right(b) => ingest.ingest(StreamType.Metrics, b).flatMap {
                  case Right(_) => NoContent()
                  case Left(r) => fail(r)
                }
            }
          }
        }
    }

}

object PromRoutes {

  /** PromQL の式の長さの上限（文字）。 */
  val MaxQueryLength = 10000
}
