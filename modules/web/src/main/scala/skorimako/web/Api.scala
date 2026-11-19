package skorimako.web

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import skorimako.shared.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** API の呼び出しのエラー. */
final case class ApiError(
  status: Int,
  message: String) extends Exception(message)

/** バックエンドの REST API. 401 はログイン画面へ、エラーは {"error": ...} の文言にする. */
object Api {

  private def request(
    method: dom.HttpMethod,
    url: String,
    body: Option[Json] = None
  ): Future[Option[Json]] = {
    val init = new dom.RequestInit {}
    init.method = method
    init.credentials = dom.RequestCredentials.`same-origin`
    body.foreach { b =>
      init.headers = js.Dictionary("Content-Type" -> "application/json")
      init.body = b.noSpaces
    }
    dom.fetch(url, init).toFuture.flatMap { res =>
      if (res.status == 401) {
        val back = dom.window.location.pathname + dom.window.location.search
        if (!dom.window.location.pathname.startsWith("/login"))
          dom.window.location.href = "/auth/login?returnTo=" + js.URIUtils.encodeURIComponent(back)
        Future.failed(ApiError(401, "ログインしてください"))
      } else if (!res.ok) {
        res.text().toFuture.recover(_ => "").flatMap { t =>
          val msg = parse(
            t
          ).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(s"${res.status} ${res.statusText}")
          Future.failed(ApiError(res.status, msg))
        }
      } else if (res.status == 204) Future.successful(None)
      else res.text().toFuture.map(t => if (t.isEmpty) None else Some(parse(t).fold(throw _, identity)))
    }
  }

  private def as[A: Decoder](
    j: Option[Json]
  ): A = j.getOrElse(Json.Null).as[A].fold(e => throw ApiError(0, e.getMessage), identity)

  def get[A: Decoder](
    url: String
  ): Future[A] = request(dom.HttpMethod.GET, url).map(as[A])
  def post[A: Decoder](
    url: String,
    body: Json = Json.obj()
  ): Future[A] = request(dom.HttpMethod.POST, url, Some(body)).map(as[A])
  def put[A: Decoder](
    url: String,
    body: Json
  ): Future[A] = request(dom.HttpMethod.PUT, url, Some(body)).map(as[A])
  def patch[A: Decoder](
    url: String,
    body: Json
  ): Future[A] = request(dom.HttpMethod.PATCH, url, Some(body)).map(as[A])
  def send(
    method: dom.HttpMethod,
    url: String,
    body: Option[Json] = None
  ): Future[Unit] = request(method, url, body).map(_ => ())
  def delete(
    url: String
  ): Future[Unit] = send(dom.HttpMethod.DELETE, url)
  def postUnit(
    url: String,
    body: Json = Json.obj()
  ): Future[Unit] = send(dom.HttpMethod.POST, url, Some(body))
  def putUnit(
    url: String,
    body: Json
  ): Future[Unit] = send(dom.HttpMethod.PUT, url, Some(body))
  def patchUnit(
    url: String,
    body: Json
  ): Future[Unit] = send(dom.HttpMethod.PATCH, url, Some(body))

  def enc(
    s: String
  ): String = js.URIUtils.encodeURIComponent(s)

  def query(
    params: (String, Option[String])*
  ): String = {
    val q = params.collect { case (k, Some(v)) if v.nonEmpty => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

  // ---------------------------------------------------------------- 認証・自分

  def clientConfig(): Future[ClientConfigDto] = get[ClientConfigDto]("/api/client-config")
  def me(): Future[MeDto] = get[MeDto]("/api/me")
  def devLogin(
    name: String,
    email: String,
    admin: Boolean,
    returnTo: String
  ): Future[Json] =
    post[Json](
      "/auth/dev-login",
      Json.obj("name" -> name.asJson, "email" -> email.asJson, "admin" -> admin.asJson, "returnTo" -> returnTo.asJson)
    )

  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))

  // ---------------------------------------------------------------- データ

  def streams(
    streamType: Option[String] = None
  ): Future[List[StreamDto]] = get[List[StreamDto]]("/api/streams" + query("type" -> streamType))
  def serviceNames: Future[List[String]] = get[List[String]]("/api/service-names")
  def metricServices: Future[Map[String, List[String]]] = get[Map[String, List[String]]]("/api/metrics/services")
  def stream(
    streamType: String,
    name: String
  ): Future[StreamDetailDto] = get[StreamDetailDto](s"/api/streams/$streamType/${enc(name)}")
  def sql(
    r: SqlQueryReq
  ): Future[QueryResultDto] = post[QueryResultDto]("/api/query/sql", r.asJson)
  def logs(
    r: LogSearchReq
  ): Future[LogSearchDto] = post[LogSearchDto]("/api/query/logs", r.asJson)
  def metrics(
    r: MetricQueryReq
  ): Future[MetricResultDto] = post[MetricResultDto]("/api/query/metrics", r.asJson)
  def traces(
    r: TraceSearchReq
  ): Future[List[TraceSummaryDto]] = post[List[TraceSummaryDto]]("/api/traces/search", r.asJson)
  def trace(
    id: String
  ): Future[TraceDto] = get[TraceDto](s"/api/traces/${enc(id)}")

}
