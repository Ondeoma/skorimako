package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skorimako.core.*
import skorimako.db.*
import skorimako.shared.*

import java.util.UUID

/** ダッシュボードと、フロントエンドの観測（RUM）のアプリ。 */
final class AppRoutes(
  apps: AppStore,
  streams: StreamStore,
  users: UserStore,
  val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- ダッシュボード

    case req @ GET -> Root / "api" / "dashboards" => getDashboards(req)
    case req @ POST -> Root / "api" / "dashboards" => postDashboards(req)
    case req @ GET -> Root / "api" / "dashboards" / UuidVar(id) => getDashboard(req, id)
    case req @ PUT -> Root / "api" / "dashboards" / UuidVar(id) => putDashboard(req, id)
    case req @ DELETE -> Root / "api" / "dashboards" / UuidVar(id) => deleteDashboard(req, id)
    case req @ GET -> Root / "api" / "rum" / "apps" => getRumApps(req)
    case req @ POST -> Root / "api" / "rum" / "apps" => postRumApps(req)
    case req @ PUT -> Root / "api" / "rum" / "apps" / UuidVar(id) => putRumApp(req, id)
    case req @ DELETE -> Root / "api" / "rum" / "apps" / UuidVar(id) => deleteRumApp(req, id)
  }

  private def getDashboards(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(_ => apps.dashboards.flatMap(Ok(_)))

  private def postDashboards(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[DashboardReq](req)(r =>
        dashOk(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) => apps.createDashboard(v, Some(s.user.id)).flatMap(id =>
              audit(users, s, "dashboard.create", Some(id.toString)) *> apps.dashboard(id).flatMap(Created(_))
            )
        }
      )
    )

  private def getDashboard(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(_ => apps.dashboard(id).flatMap(_.fold(err(ErrorCode.DashboardNotFound))(Ok(_))))

  private def putDashboard(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[DashboardReq](req)(r =>
        dashOk(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(v) => apps.updateDashboard(id, v).flatMap {
              case false => err(ErrorCode.DashboardNotFound)
              case true => audit(users, s, "dashboard.update", Some(id.toString)) *> apps.dashboard(id).flatMap(Ok(_))
            }
        }
      )
    )

  private def deleteDashboard(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      apps.deleteDashboard(id).flatMap {
        case false => err(ErrorCode.DashboardNotFound)
        case true => audit(users, s, "dashboard.delete", Some(id.toString)) *> NoContent()
      }
    )

  private def getRumApps(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(_ => apps.rumApps.flatMap(Ok(_)))

  private def postRumApps(
    req: Request[IO]
  ): IO[Response[IO]] =
    admin(req)(s =>
      body[RumAppReq](req)(r =>
        rumOk(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right((name, origins, stream)) =>
            prepareRum(stream) *>
              apps.createRumApp(name, "rum_" + Crypto.randomToken(18), origins, stream, s.user.id).flatMap(a =>
                audit(users, s, "rum.create", Some(a.id.toString)) *> Created(a)
              )
        }
      )
    )

  private def putRumApp(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    admin(req)(s =>
      body[RumAppReq](req)(r =>
        rumOk(r) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right((name, origins, stream)) => prepareRum(stream) *>
              apps.updateRumApp(id, name, origins, stream).flatMap {
                case false => err(ErrorCode.RumAppNotFound)
                case true => audit(users, s, "rum.update", Some(id.toString)) *> NoContent()
              }
        }
      )
    )

  private def deleteRumApp(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    admin(req)(s =>
      apps.deleteRumApp(id).flatMap {
        case false => err(ErrorCode.RumAppNotFound)
        case true => audit(users, s, "rum.delete", Some(id.toString)) *> NoContent()
      }
    )

  /** RUM のストリームに、SDK が送る列を先に登録しておく（まだ送られていない列も画面の集計で使えるように）。 */
  private val rumFields: Map[String, String] =
    (List(
      "event_type",
      "app",
      "session_id",
      "view_id",
      "url",
      "path",
      "referrer",
      "title",
      "viewport",
      "language",
      "user_agent",
      "browser",
      "browser_version",
      "os",
      "device",
      "user_id",
      "message",
      "error_type",
      "source",
      "stack",
      "request_url",
      "method",
      "name",
      "navigation_type"
    )
      .map(_ -> FieldType.Utf8) ++
      List(
        "lcp",
        "fcp",
        "inp",
        "ttfb",
        "load_time",
        "dom_content_loaded",
        "duration",
        "status",
        "line",
        "column",
        "transfer_size"
      ).map(_ -> FieldType.Int64) ++
      List("cls" -> FieldType.Float64)).toMap

  private def prepareRum(
    stream: String
  ): IO[Unit] =
    streams.ensure(StreamType.Rum, stream).flatMap(st => streams.mergeFields(st.id, rumFields)).void

  private def dashOk(
    r: DashboardReq
  ): Either[String, DashboardReq] =
    for {
      _ <- Either.cond(r.name.trim.nonEmpty && r.name.trim.length <= 200, (), "名前を入力してください（200文字まで）")
      _ <- Either.cond(r.panels.size <= 100, (), "パネルは100個までです")
      _ <- r.panels.traverse_(p =>
        for {
          _ <- Either.cond(PanelKind.all.contains(p.kind), (), s"パネル「${p.title}」の種類が正しくありません")
          _ <- Either.cond(StreamType.all.contains(p.streamType), (), s"パネル「${p.title}」のデータの種類が正しくありません")
          _ <- SqlText.checkQuery(p.sql).left.map(e => s"パネル「${p.title}」: $e")
        } yield ()
      )
    } yield r.copy(
      name = r.name.trim,
      description = r.description.trim.take(2000),
      panels = r.panels.map(p => p.copy(width = math.max(1, math.min(p.width, 4)), title = p.title.take(200)))
    )

  private def rumOk(
    r: RumAppReq
  ): Either[String, (String, List[String], String)] =
    for {
      _ <- Either.cond(r.name.trim.nonEmpty, (), "名前を入力してください")
      stream <- Names.stream(r.stream.getOrElse("default"))
      origins = r.origins.map(_.trim.stripSuffix("/")).filter(_.nonEmpty).distinct
      _ <- origins.find(o => o != "*" && !(o.startsWith("https://") || o.startsWith("http://"))).map(o =>
        s"サイト $o は https://… の形で書いてください"
      )
        .toLeft(())
    } yield (r.name.trim.take(200), origins, stream)

}
