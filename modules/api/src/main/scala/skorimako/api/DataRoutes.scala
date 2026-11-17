package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

/** ストリームの一覧・設定・取り込みのルールと、検索（SQL・ログ・メトリクス・トレース・サービス）。見られないストリームは出さない。 */
final class DataRoutes(streams: StreamStore, alerts: AlertStore, engine: QueryEngine, ingester: Ingester, transform: Transform,
    objects: skorimako.storage.ObjectStore, users: UserStore, val sessions: SessionAuth, cfg: ApiConfig) extends RouteSupport:

  private object TypeVar:
    def unapply(s: String): Option[String] = Option.when(StreamType.all.contains(s))(s)

  private val acl = engine.acl

  /** 見られるストリームだけ（見られなければ 404）。 */
  private def withStream(s: AuthSession, t: String, name: String)(f: StreamRow => IO[Response[IO]]): IO[Response[IO]] =
    streams.find(t, name).flatMap {
      case None => err(Status.NotFound, "ストリームが見つかりません")
      case Some(st) => acl.visible(s.viewer, t, name).ifM(f(st), err(Status.NotFound, "ストリームが見つかりません"))
    }

  private def visibleStreams(s: AuthSession, t: Option[String], q: Option[String]): IO[List[StreamDto]] =
    streams.list(t, q, cfg.retentionDays).flatMap(ss => acl.filterTyped(s.viewer, ss)(x => (x.streamType, x.name)))

  private def ruleOk(r: IngestRuleReq): Either[String, IngestRuleReq] = Transform.validate(r)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- ホーム

    case req @ GET -> Root / "api" / "overview" =>
      authed(req)(s =>
        (visibleStreams(s, None, None), alerts.firing, alerts.events(None, 20), streams.ingestedToday).tupled.flatMap { (ss, f, ev, today) =>
          Ok(OverviewDto(ss.filter(_.streamType != StreamType.Metrics) ++ ss.filter(_.streamType == StreamType.Metrics).sortBy(-_.docCount).take(20),
            f.map(_.toDto), ev, today))
        })

    // ---------------------------------------------------------------- ストリーム

    case req @ GET -> Root / "api" / "streams" =>
      authed(req)(s => visibleStreams(s, param(req, "type").filter(StreamType.all.contains), param(req, "q")).flatMap(Ok(_)))

    // データを送ってきたサービスの名前（画面のサービスの絞り込み）
    case req @ GET -> Root / "api" / "service-names" =>
      authed(req)(_ => streams.serviceNames.flatMap(Ok(_)))

    // メトリクスごとの送り元のサービス（{"jvm_memory_used": ["skea", "stui"], …}）
    case req @ GET -> Root / "api" / "metrics" / "services" =>
      authed(req)(_ => streams.metricServices.flatMap(Ok(_)))

    // 列の値の候補（ダッシュボードのかんたん作成の条件）
    case req @ GET -> Root / "api" / "streams" / TypeVar(t) / name / "values" =>
      authed(req)(s => withStream(s, t, name)(st => param(req, "field").filter(_.nonEmpty) match
        case None    => err(Status.BadRequest, "field を指定してください")
        case Some(f) => streams.tagValuesOf(st.id, f, 200).flatMap(Ok(_))))

    // ダッシュボードのかんたん作成: 選んだ指定から SQL を組み立てる（区切りは $__interval のまま）
    case req @ POST -> Root / "api" / "panels" / "sql" =>
      authed(req)(s => body[PanelSqlReq](req)(r =>
        if !StreamType.all.contains(r.spec.streamType) then err(Status.BadRequest, "データの種類が正しくありません")
        else streams.find(r.spec.streamType, r.spec.stream.trim).flatMap {
          case None => err(Status.BadRequest, "ストリーム（メトリクス）が見つかりません")
          case Some(st) =>
            streams.fields(st.id).flatMap { fs =>
              val labels  = fs.map(_.name).filterNot(n => n == Columns.Timestamp || n == "value" || n == "__name__" || n == "__type__")
              val numeric = fs.filter(f => f.fieldType == "int64" || f.fieldType == "float64").map(_.name).toSet
              QueryBuilder.panel(r.spec, r.kind, labels, numeric) match
                case Left(e)          => err(Status.BadRequest, e)
                case Right((sql, u))  => Ok(PanelSqlDto(sql, u))
            }
        }))

    case req @ GET -> Root / "api" / "streams" / TypeVar(t) / name =>
      authed(req)(s => withStream(s, t, name)(st =>
        (streams.dto(st.id, cfg.retentionDays), streams.fields(st.id)).tupled.flatMap {
          case (Some(d), fs) => Ok(StreamDetailDto(d, fs))
          case _             => err(Status.NotFound, "ストリームが見つかりません")
        }))

    case req @ PATCH -> Root / "api" / "streams" / TypeVar(t) / name =>
      admin(req)(s => body[UpdateStreamReq](req) { r =>
        if r.retentionDays.exists(d => d < 0 || d > 3650) then err(Status.BadRequest, "保持期間は 0〜3650 日で指定してください（0 は既定）")
        else if r.maxRowsPerMinute.exists(_ < 0) then err(Status.BadRequest, "取り込みの上限は 0 以上にしてください（0 は上限なし）")
        else withStream(s, t, name)(st =>
          streams.update(st.id, r.retentionDays.map(d => Option.when(d > 0)(d)), r.description,
            r.allowedGroups.map(_.map(_.trim).filter(_.nonEmpty).distinct), r.maxRowsPerMinute.map(m => Option.when(m > 0)(m))) *>
            acl.invalidate *> transform.reload *>
            audit(users, s, "stream.update", Some(s"$t/$name"), r.asJson) *> NoContent())
      })

    case req @ DELETE -> Root / "api" / "streams" / TypeVar(t) / name =>
      admin(req)(s => withStream(s, t, name)(st =>
        // 行と一緒に S3 のファイルも消す（失敗したものは残る。検索からは見えなくなる）
        streams.delete(st.id).flatMap(keys => keys.parTraverseN(8)(k => objects.delete(k).attempt *> engine.forgetCached(k)).as(keys.size)).flatMap { n =>
          audit(users, s, "stream.delete", Some(s"$t/$name"), Json.obj("files" -> n.asJson)) *> NoContent()
        }))

    case req @ GET -> Root / "api" / "ingest" / "pending" => admin(req)(_ => ingester.pending.flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "nodes" =>
      admin(req)(_ => streams.peers("").flatMap(ns => Ok(ns.map((id, url) => Json.obj("id" -> id.asJson, "url" -> url.asJson)))))

    // ---------------------------------------------------------------- 取り込みのルール（管理者）

    case req @ GET -> Root / "api" / "ingest" / "rules" => admin(req)(_ => streams.ingestRules.flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "ingest" / "rules" =>
      admin(req)(s => body[IngestRuleReq](req)(r => ruleOk(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) => streams.createIngestRule(v).flatMap(id =>
          transform.reload *> audit(users, s, "ingest_rule.create", Some(id.toString), v.asJson) *> Created(Json.obj("id" -> id.asJson)))))

    case req @ PUT -> Root / "api" / "ingest" / "rules" / UuidVar(id) =>
      admin(req)(s => body[IngestRuleReq](req)(r => ruleOk(r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(v) => streams.updateIngestRule(id, v).flatMap {
          case false => err(Status.NotFound, "ルールが見つかりません")
          case true  => transform.reload *> audit(users, s, "ingest_rule.update", Some(id.toString), v.asJson) *> NoContent()
        }))

    case req @ DELETE -> Root / "api" / "ingest" / "rules" / UuidVar(id) =>
      admin(req)(s => streams.deleteIngestRule(id).flatMap {
        case false => err(Status.NotFound, "ルールが見つかりません")
        case true  => transform.reload *> audit(users, s, "ingest_rule.delete", Some(id.toString)) *> NoContent()
      })

    // ---------------------------------------------------------------- 検索

    case req @ POST -> Root / "api" / "query" / "sql" =>
      authed(req)(s => body[SqlQueryReq](req)(r =>
        if !StreamType.all.contains(r.streamType) then err(Status.BadRequest, "streamType は logs・metrics・traces・rum のどれかです")
        else query(engine.sql(s.viewer, r))))

    case req @ POST -> Root / "api" / "query" / "logs" => authed(req)(s => body[LogSearchReq](req)(r => query(engine.logs(s.viewer, r))))

    case req @ POST -> Root / "api" / "query" / "metrics" => authed(req)(s => body[MetricQueryReq](req)(r => query(engine.metrics(s.viewer, r))))

    // ---------------------------------------------------------------- トレース・サービス

    case req @ POST -> Root / "api" / "traces" / "search" =>
      authed(req)(s => body[TraceSearchReq](req) { r =>
        traceStreams(s, r.stream).flatMap(ids => streams.searchTraces(r, ids, math.max(1, math.min(r.limit.getOrElse(100), 1000)))).flatMap(Ok(_))
      })

    case req @ GET -> Root / "api" / "traces" / "services" =>
      authed(req) { s =>
        val (start, end) = range(req)
        traceStreams(s, param(req, "stream")).flatMap(ids => streams.services(ids.map(_._1), start, end)).flatMap(Ok(_))
      }

    case req @ GET -> Root / "api" / "services" / "map" =>
      authed(req) { s =>
        val (start, end) = range(req)
        query(engine.serviceMap(s.viewer, param(req, "stream").getOrElse("default"), start, end))
      }

    case req @ GET -> Root / "api" / "services" / "series" =>
      authed(req) { s =>
        val (start, end) = range(req)
        param(req, "service") match
          case None      => err(Status.BadRequest, "service を指定してください")
          case Some(svc) => query(engine.serviceSeries(s.viewer, param(req, "stream").getOrElse("default"), svc, start, end))
      }

    case req @ GET -> Root / "api" / "traces" / id =>
      authed(req)(s => engine.trace(s.viewer, id).flatMap(_.fold(e => err(Status.NotFound, e), Ok(_))))
  }

  private def traceStreams(s: AuthSession, name: Option[String]): IO[List[(java.util.UUID, String)]] =
    visibleStreams(s, Some(StreamType.Traces), None).map(_.filter(x => name.forall(_ == x.name)).map(x => x.id -> x.name))
