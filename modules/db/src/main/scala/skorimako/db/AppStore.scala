package skorimako.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import skorimako.shared.*

import java.time.Instant
import java.util.UUID

/** ダッシュボードとフロントエンドの観測（RUM）のアプリ。 */
final class AppStore(xa: Transactor[IO]) extends TxStore(xa):

  // ---------------------------------------------------------------- ダッシュボード

  def dashboards: IO[List[DashboardBrief]] = tx(
    sql"select id, name, description, jsonb_array_length(panels), updated_at from dashboard order by name".query[DashboardBrief].to[List]
  )

  def dashboard(id: UUID): IO[Option[DashboardDto]] = tx(
    sql"""select d.id, d.name, d.description, d.panels, u.id, u.name, u.avatar_url, d.updated_at
          from dashboard d left join app_user u on u.id = d.created_by where d.id = $id"""
      .query[(UUID, String, String, Json, Option[UUID], Option[String], Option[String], Instant)]
      .map((id, n, d, p, uid, un, ua, at) =>
        DashboardDto(id, n, d, p.as[List[PanelDto]].getOrElse(Nil), (uid, un).mapN((i, x) => UserBrief(i, x, ua)), at)).option
  )

  def createDashboard(r: DashboardReq, by: Option[UUID]): IO[UUID] =
    val id = UUID.randomUUID()
    tx(sql"insert into dashboard (id, name, description, panels, created_by) values ($id, ${r.name}, ${r.description}, ${r.panels.asJson}, $by)".update.run)
      .as(id)

  def updateDashboard(id: UUID, r: DashboardReq): IO[Boolean] = tx(
    sql"""update dashboard set name = ${r.name}, description = ${r.description}, panels = ${r.panels.asJson}, updated_at = now()
          where id = $id""".update.run.map(_ > 0)
  )

  def deleteDashboard(id: UUID): IO[Boolean] = tx(sql"delete from dashboard where id = $id".update.run.map(_ > 0))

  // ---------------------------------------------------------------- RUM のアプリ

  def rumApps: IO[List[RumAppDto]] = tx(
    sql"select id, name, token, origins, stream, created_at from rum_app order by name".query[RumAppDto].to[List]
  )

  def rumApp(token: String): IO[Option[RumAppDto]] = tx(
    sql"select id, name, token, origins, stream, created_at from rum_app where token = $token".query[RumAppDto].option
  )

  def createRumApp(name: String, token: String, origins: List[String], stream: String, by: UUID): IO[RumAppDto] = tx(
    sql"""insert into rum_app (id, name, token, origins, stream, created_by) values (${UUID.randomUUID()}, $name, $token, ${origins.toArray}, $stream, $by)
          returning id, name, token, origins, stream, created_at""".query[RumAppDto].unique
  )

  def updateRumApp(id: UUID, name: String, origins: List[String], stream: String): IO[Boolean] = tx(
    sql"update rum_app set name = $name, origins = ${origins.toArray}, stream = $stream where id = $id".update.run.map(_ > 0)
  )

  def deleteRumApp(id: UUID): IO[Boolean] = tx(sql"delete from rum_app where id = $id".update.run.map(_ > 0))
