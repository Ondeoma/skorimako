package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.util.UUID

/**
 * リンクの要約（stui などが、メッセージに貼られた skorimako の URL に名前を出すため）。POST /api/links/lookup {urls}。
 * 対応: /traces/{id}・/alerts/{id}・/dashboards/{id}・/logs?stream=…&where=…・/metrics?metric=…。見られないものは返さない。
 */
final class LinkRoutes(streams: StreamStore,
                       alerts: AlertAccess,
                       apps: AppStore,
                       acl: Acl,
                       val sessions: SessionAuth,
                       cfg: ApiConfig) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // stui の「リンクの名前」の形: {"ids": ["traces/…", "alerts/…", "dashboards/…"]} → {"links": [{id, kind, title, summary, status, icon}]}
    case req @ POST -> Root / "api" / "links" / "titles" => postLinksTitles(req)
    case req @ POST -> Root / "api" / "links" / "lookup" => postLinksLookup(req)
  }

  private def postLinksTitles(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[Json](req) { j =>
        val ids =
          j.hcursor.get[List[String]]("ids").getOrElse(Nil).map(_.trim.toLowerCase).filter(idRe.matches).distinct
        if ids.size > 100 then err(ErrorCode.LinkIdsTooMany)
        else
          ids.traverse(id => info(s, "/" + id).handleError(_ => None).map(_.map(titleJson(id, _)))).flatMap(xs =>
            Ok(Json.obj("links" -> Json.fromValues(xs.flatten)))
          )
      }
    )

  private def postLinksLookup(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[LinkLookupReq](req)(r =>
        r.urls.distinct.take(100).traverse(u => info(s, u.trim).handleError(_ => None)).flatMap(xs => Ok(xs.flatten))
      )
    )

  private def info(s: AuthSession, url: String): IO[Option[LinkInfoDto]] =
    Uri.fromString(url).toOption.filter(u => u.path.renderString.startsWith("/") && sameSite(url)) match {
      case None => IO.pure(None)
      case Some(u) =>
        u.path.segments.map(_.decoded()).toList match {
          case "traces" :: id :: Nil =>
            streams.findTrace(id.toLowerCase).flatMap(_.headOption.traverse {
              (sid, mi, ma) =>
                streams.byId(sid).flatMap(_.traverse(st => acl.visible(s.viewer, StreamType.Traces, st.name))).flatMap {
                  case Some(true) =>
                    streams.searchTraces(
                      TraceSearchReq(mi, mi, None, None, None, None, None, Some(1000)),
                      List(sid -> ""),
                      1000
                    ).map(
                      _.find(_.traceId == id.toLowerCase).map(t =>
                        LinkInfoDto(
                          url,
                          "trace",
                          s"${t.service}: ${t.rootName}",
                          s"${t.durationMicros / 1000.0}ms・スパン ${t.spanCount}" +
                            (if t.errorCount > 0 then s"・エラー ${t.errorCount}" else ""),
                          Some(if t.errorCount > 0 then "error" else "ok")
                        )
                      )
                    )
                  case _ => IO.pure(None)
                }
            }).map(_.flatten)
          case "alerts" :: id :: Nil =>
            // 見られるルールだけ（SQL のルールは、SQL が参照するストリームをすべて見られる人だけ）
            uuidOpt(id).flatTraverse(alerts.rule(s.viewer, _)).map(_.map { r =>
              LinkInfoDto(
                url,
                "alert",
                r.name,
                s"${AlertState.label(r.state)}・値 ${r.lastValue.map(AlertLogic.fmt).getOrElse("-")}",
                Some(r.state)
              )
            })
          case "dashboards" :: id :: Nil =>
            uuidOpt(id).flatTraverse(apps.dashboard).map(_.map(d =>
              LinkInfoDto(url, "dashboard", d.name, s"パネル ${d.panels.size}", None)
            ))
          case "logs" :: Nil =>
            val st = u.query.params.getOrElse("stream", "default")
            acl.visible(s.viewer, StreamType.Logs, st).map(ok =>
              Option.when(ok)(
                LinkInfoDto(
                  url,
                  "logs",
                  s"ログ: $st",
                  u.query.params.get("where").filter(_.nonEmpty).getOrElse("すべて"),
                  None
                )
              )
            )
          case "metrics" :: Nil =>
            u.query.params.get("metric").flatTraverse(m =>
              acl.visible(s.viewer, StreamType.Metrics, m).map(ok =>
                Option.when(ok)(LinkInfoDto(url, "metric", s"メトリクス: $m", "", None))
              )
            )
          case _ => IO.pure(None)
        }
    }

  private def sameSite(url: String): Boolean =
    !url.startsWith("http") || url.startsWith(cfg.publicUrl.stripSuffix("/") + "/")

  /** stui の連携の形（ID ごと）の1件: id は traces/{id}・alerts/{id}・dashboards/{id}。 */
  private def titleJson(id: String, i: LinkInfoDto): Json = {
    val icon = (i.kind, i.status) match {
      case ("trace", Some("error")) => "⚠️"
      case ("trace", _) => "🧭"
      case ("alert", Some("firing")) => "🔴"
      case ("alert", Some("error")) => "⚠️"
      case ("alert", _) => "🟢"
      case ("dashboard", _) => "📊"
      case _ => "📈"
    }
    Json.obj(
      "id" -> id.asJson,
      "kind" -> i.kind.asJson,
      "title" -> i.title.asJson,
      "summary" -> i.summary.asJson,
      "status" -> i.status.asJson,
      "icon" -> icon.asJson
    )
  }

  private val idRe =
    "^(traces/[0-9a-f]{1,64}|(alerts|dashboards)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$".r

}
