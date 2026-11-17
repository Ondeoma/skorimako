package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, Location}
import skorimako.auth.{Crypto, SecretBox}
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * stui（チャット）との連携のクライアント側: 各ユーザーが stui と OAuth で連携すると、アラートをその人の stui の DM に送れる
 * （stui の POST /api/apps/notify。stui のボット「skorimako」からの DM になる）。
 * stui 側の OAUTH_CLIENTS に skorimako を登録する（redirectUri は {PUBLIC_URL}/api/integrations/stui/callback、scopes は read・write）。
 */
final class StuiClient(cfg: Option[LinkService], tokens: IntegrationTokenStore, http: Client[IO], box: SecretBox, publicUrl: String):

  val Provider = "stui"

  def configured: Boolean = cfg.isDefined

  private def redirectUri: String = s"${publicUrl.stripSuffix("/")}/api/integrations/stui/callback"

  def connected(userId: UUID): IO[Boolean] = tokens.token(userId, Provider).map(_.isDefined)

  private def token(userId: UUID): IO[Option[String]] = tokens.token(userId, Provider).map(_.flatMap(box.decrypt))

  /** その人の stui の DM に送る。 */
  def notify(userId: UUID, text: String): IO[OutboxResult] =
    cfg match
      case None => IO.pure(OutboxResult.Skip("stui との連携が設定されていません（STUI_URL など）"))
      case Some(c) =>
        token(userId).flatMap {
          case None => IO.pure(OutboxResult.Skip("宛先の人が stui と連携していません"))
          case Some(t) =>
            val req = Request[IO](Method.POST, Uri.unsafeFromString(c.apiUrl + "/api/apps/notify"))
              .withEntity(Json.obj("text" -> text.asJson))
              .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)), "User-Agent" -> "skorimako")
            http.run(req).use(r => IO.pure(r.status.code)).timeout(15.seconds).attempt.flatMap {
              case Right(code) if code >= 200 && code < 300 => IO.pure(OutboxResult.Sent)
              // トークンが失効している: 連携を外し、送るのをやめる（その人が連携し直す）
              case Right(401) => tokens.deleteToken(userId, Provider).as(OutboxResult.Skip("stui との連携が切れています（連携し直してください）"))
              case Right(code) => IO.pure(OutboxResult.Retry(s"HTTP $code"))
              case Left(_) => IO.pure(OutboxResult.Retry("stui に接続できませんでした"))
            }
        }

  private def basic(c: LinkService): Authorization = Authorization(BasicCredentials(c.clientId, c.clientSecret))

  private def found(to: String): IO[Response[IO]] = IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to))))

  def routes(auth: SessionAuth, users: UserStore): HttpRoutes[IO] =
    object R extends RouteSupport:
      protected def sessions: SessionAuth = auth
      val r: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ GET -> Root / "api" / "integrations" =>
          authed(req)(s => cfg match
            case None => Ok(List.empty[IntegrationDto])
            case Some(c) => connected(s.user.id).flatMap(on => Ok(List(IntegrationDto(Provider, "stui", c.url, on)))))

        case req @ GET -> Root / "api" / "integrations" / "stui" / "connect" =>
          sessionOnly(req)(s => cfg match
            case None => err(Status.NotFound, "stui との連携が設定されていません")
            case Some(c) =>
              val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48)
              val back = param(req, "returnTo").filter(localPath).getOrElse("/settings")
              tokens.saveState(state, s.user.id, Provider, verifier, back, Instant.now().plusSeconds(600)) *>
                found(Uri.unsafeFromString(c.url + "/oauth/authorize").withQueryParams(Map(
                  "response_type" -> "code", "client_id" -> c.clientId, "redirect_uri" -> redirectUri, "scope" -> "read write",
                  "state" -> state, "code_challenge" -> Crypto.pkceChallenge(verifier), "code_challenge_method" -> "S256")).renderString))

        case req @ GET -> Root / "api" / "integrations" / "stui" / "callback" =>
          (cfg, param(req, "state")) match
            case (Some(c), Some(state)) =>
              tokens.takeState(state, Provider).flatMap {
                case None => found("/settings?integration=stui&result=expired")
                case Some((userId, verifier, back)) =>
                  param(req, "code") match
                    case None => found(s"$back?integration=stui&result=denied")
                    case Some(code) =>
                      val form = UrlForm("grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> redirectUri, "code_verifier" -> verifier)
                      val tr = Request[IO](Method.POST, Uri.unsafeFromString(c.apiUrl + "/oauth/token")).withEntity(form).putHeaders(basic(c))
                      http.expect[Json](tr).timeout(15.seconds).attempt.flatMap {
                        case Right(j) =>
                          j.hcursor.get[String]("access_token").toOption match
                            case Some(t) =>
                              tokens.saveToken(userId, Provider, box.encrypt(t)) *>
                                users.audit(Some(userId), "web", "integration.connect", Some(Provider), Json.obj()) *>
                                found(s"$back?integration=stui&result=connected")
                            case None => found(s"$back?integration=stui&result=error")
                        case Left(_) => found(s"$back?integration=stui&result=error")
                      }
              }
            case _ => err(Status.BadRequest, "state がありません")

        case req @ DELETE -> Root / "api" / "integrations" / "stui" / "connection" =>
          sessionOnly(req)(s => cfg match
            case None => NoContent()
            case Some(c) =>
              token(s.user.id).flatMap(_.traverse_ { t =>
                http.run(Request[IO](Method.POST, Uri.unsafeFromString(c.apiUrl + "/oauth/revoke")).withEntity(UrlForm("token" -> t)).putHeaders(basic(c)))
                  .use(_ => IO.unit).timeout(10.seconds).handleError(_ => ())
              }) *> tokens.deleteToken(s.user.id, Provider) *> users.audit(Some(s.user.id), "web", "integration.disconnect", Some(Provider), Json.obj()) *>
                NoContent())
      }
    R.r

/**
 * リンクの要約（stui などが、メッセージに貼られた skorimako の URL に名前を出すため）。POST /api/links/lookup {urls}。
 * 対応: /traces/{id}・/alerts/{id}・/dashboards/{id}・/logs?stream=…&where=…・/metrics?metric=…。見られないものは返さない。
 */
final class LinkRoutes(streams: StreamStore, alerts: AlertStore, apps: AppStore, acl: Acl, val sessions: SessionAuth, cfg: ApiConfig) extends RouteSupport:

  private def info(s: AuthSession, url: String): IO[Option[LinkInfoDto]] =
    Uri.fromString(url).toOption.filter(u => u.path.renderString.startsWith("/") && sameSite(url)) match
      case None => IO.pure(None)
      case Some(u) =>
        u.path.segments.map(_.decoded()).toList match
          case "traces" :: id :: Nil =>
            streams.findTrace(id.toLowerCase).flatMap(_.headOption.traverse { (sid, mi, ma) =>
              streams.byId(sid).flatMap(_.traverse(st => acl.visible(s.viewer, StreamType.Traces, st.name))).flatMap {
                case Some(true) =>
                  streams.searchTraces(TraceSearchReq(mi, mi, None, None, None, None, None, Some(1000)), List(sid -> ""), 1000).map(
                    _.find(_.traceId == id.toLowerCase).map(t => LinkInfoDto(url, "trace", s"${t.service}: ${t.rootName}",
                      s"${t.durationMicros / 1000.0}ms・スパン ${t.spanCount}" + (if t.errorCount > 0 then s"・エラー ${t.errorCount}" else ""),
                      Some(if t.errorCount > 0 then "error" else "ok"))))
                case _ => IO.pure(None)
              }
            }).map(_.flatten)
          case "alerts" :: id :: Nil =>
            uuidOpt(id).flatTraverse(alerts.rule).flatMap(_.flatTraverse { r =>
              acl.visible(s.viewer, r.streamType, r.stream).map(ok => Option.when(ok || r.mode == AlertMode.Sql)(
                LinkInfoDto(url, "alert", r.name, s"${AlertState.label(r.state)}・値 ${r.lastValue.map(AlertLogic.fmt).getOrElse("-")}", Some(r.state))))
            })
          case "dashboards" :: id :: Nil =>
            uuidOpt(id).flatTraverse(apps.dashboard).map(_.map(d => LinkInfoDto(url, "dashboard", d.name, s"パネル ${d.panels.size}", None)))
          case "logs" :: Nil =>
            val st = u.query.params.getOrElse("stream", "default")
            acl.visible(s.viewer, StreamType.Logs, st).map(ok => Option.when(ok)(
              LinkInfoDto(url, "logs", s"ログ: $st", u.query.params.get("where").filter(_.nonEmpty).getOrElse("すべて"), None)))
          case "metrics" :: Nil =>
            u.query.params.get("metric").flatTraverse(m => acl.visible(s.viewer, StreamType.Metrics, m).map(ok =>
              Option.when(ok)(LinkInfoDto(url, "metric", s"メトリクス: $m", "", None))))
          case _ => IO.pure(None)

  private def sameSite(url: String): Boolean =
    !url.startsWith("http") || url.startsWith(cfg.publicUrl.stripSuffix("/") + "/")

  /** stui の連携の形（ID ごと）の1件: id は traces/{id}・alerts/{id}・dashboards/{id}。 */
  private def titleJson(id: String, i: LinkInfoDto): Json =
    val icon = (i.kind, i.status) match
      case ("trace", Some("error"))  => "⚠️"
      case ("trace", _)              => "🧭"
      case ("alert", Some("firing")) => "🔴"
      case ("alert", Some("error"))  => "⚠️"
      case ("alert", _)              => "🟢"
      case ("dashboard", _)          => "📊"
      case _                         => "📈"
    Json.obj("id" -> id.asJson, "kind" -> i.kind.asJson, "title" -> i.title.asJson, "summary" -> i.summary.asJson, "status" -> i.status.asJson,
      "icon" -> icon.asJson)

  private val idRe = "^(traces/[0-9a-f]{1,64}|(alerts|dashboards)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$".r

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // stui の「リンクの名前」の形: {"ids": ["traces/…", "alerts/…", "dashboards/…"]} → {"links": [{id, kind, title, summary, status, icon}]}
    case req @ POST -> Root / "api" / "links" / "titles" =>
      authed(req)(s => body[Json](req) { j =>
        val ids = j.hcursor.get[List[String]]("ids").getOrElse(Nil).map(_.trim.toLowerCase).filter(idRe.matches).distinct
        if ids.size > 100 then err(Status.BadRequest, "ids は100件までです")
        else ids.traverse(id => info(s, "/" + id).handleError(_ => None).map(_.map(titleJson(id, _)))).flatMap(xs =>
          Ok(Json.obj("links" -> Json.fromValues(xs.flatten))))
      })

    case req @ POST -> Root / "api" / "links" / "lookup" =>
      authed(req)(s => body[LinkLookupReq](req)(r =>
        r.urls.distinct.take(100).traverse(u => info(s, u.trim).handleError(_ => None)).flatMap(xs => Ok(xs.flatten))))
  }
