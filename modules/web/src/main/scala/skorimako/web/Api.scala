package skorimako.web

import io.circe.*
import io.circe.syntax.*
import skorimako.shared.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** バックエンドの REST API（呼び出しの共通部分は sbirds.web.ApiBase. 401 はログイン画面へ、エラーは {"error": ...} の文言にする）. */
object Api extends sbirds.web.ApiBase {

  // ---------------------------------------------------------------- 認証・自分

  def clientConfig(): Future[ClientConfigDto] = get[ClientConfigDto]("/api/client-config")
  def me(): Future[MeDto] = get[MeDto]("/api/me")
  def devLogin(name: String, email: String, admin: Boolean, returnTo: String): Future[Json] =
    post[Json](
      "/auth/dev-login",
      Json.obj("name" -> name.asJson, "email" -> email.asJson, "admin" -> admin.asJson, "returnTo" -> returnTo.asJson)
    )

  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))

  // ---------------------------------------------------------------- データ

  def streams(streamType: Option[String] = None): Future[List[StreamDto]] =
    get[List[StreamDto]]("/api/streams" + query("type" -> streamType))
  def serviceNames: Future[List[String]] = get[List[String]]("/api/service-names")
  def metricServices: Future[Map[String, List[String]]] = get[Map[String, List[String]]]("/api/metrics/services")
  def stream(streamType: String, name: String): Future[StreamDetailDto] =
    get[StreamDetailDto](s"/api/streams/$streamType/${enc(name)}")
  def sql(r: SqlQueryReq): Future[QueryResultDto] = post[QueryResultDto]("/api/query/sql", r.asJson)
  def logs(r: LogSearchReq): Future[LogSearchDto] = post[LogSearchDto]("/api/query/logs", r.asJson)
  def metrics(r: MetricQueryReq): Future[MetricResultDto] = post[MetricResultDto]("/api/query/metrics", r.asJson)
  def traces(r: TraceSearchReq): Future[List[TraceSummaryDto]] =
    post[List[TraceSummaryDto]]("/api/traces/search", r.asJson)
  def trace(id: String): Future[TraceDto] = get[TraceDto](s"/api/traces/${enc(id)}")

}
