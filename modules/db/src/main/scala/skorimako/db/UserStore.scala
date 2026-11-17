package skorimako.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import skorimako.core.*
import skorimako.shared.*

import java.time.Instant
import java.util.UUID

/** ユーザー・グループ・セッション・ログイン途中の状態・APIキー・監査ログ。 */
final class UserStore(xa: Transactor[IO]) extends TxStore(xa):

  private val userCols = fr"u.id, u.sub, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.timezone"

  // ---------------------------------------------------------------- ユーザー

  /** ログイン時に作成・更新する。 */
  def upsert(sub: String, name: String, email: Option[String], avatar: Option[String]): IO[User] = tx(
    sql"""insert into app_user (id, sub, name, email, avatar_url, last_login_at) values (${UUID.randomUUID()}, $sub, $name, $email, $avatar, now())
          on conflict (sub) do update set name = excluded.name, email = excluded.email,
            avatar_url = excluded.avatar_url, last_login_at = now()
          returning id, sub, name, email, avatar_url, is_admin, disabled, timezone""".query[User].unique
  )

  def find(id: UUID): IO[Option[User]] = tx(findC(id))

  def findC(id: UUID): ConnectionIO[Option[User]] =
    (fr"select" ++ userCols ++ fr"from app_user u where u.id = $id").query[User].option

  /** ログイン時に IdP の groups クレームで所属を置き換える。 */
  def syncGroups(userId: UUID, names: List[String]): IO[Unit] = tx {
    val ns = names.map(_.trim).filter(_.nonEmpty).distinct
    for
      ids <- ns.traverse(n =>
        sql"""insert into user_group (id, name) values (${UUID.randomUUID()}, $n)
              on conflict (name) do update set name = excluded.name returning id""".query[UUID].unique)
      _ <- sql"delete from group_member where user_id = $userId".update.run
      _ <- ids.traverse_(g => sql"insert into group_member (group_id, user_id) values ($g, $userId) on conflict do nothing".update.run)
    yield ()
  }

  def groupsOf(userId: UUID): ConnectionIO[List[Group]] =
    sql"select g.id, g.name from user_group g join group_member m on m.group_id = g.id where m.user_id = $userId order by g.name"
      .query[Group].to[List]

  def withGroups(userId: UUID): IO[Option[(User, List[Group])]] = tx(
    findC(userId).flatMap(_.filterNot(_.disabled).traverse(u => groupsOf(u.id).map(u -> _)))
  )

  /** 一覧（管理画面）。q で名前・メールを絞る。 */
  def list(q: Option[String], limit: Int = 500): IO[List[UserDto]] = tx {
    val where = q.map(_.trim).filter(_.nonEmpty).map(s => fr"(u.name ilike ${"%" + s + "%"} or u.email ilike ${"%" + s + "%"})").toList
    (fr"""select u.id, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.last_login_at,
                 coalesce((select array_agg(g.name order by g.name) from user_group g join group_member m on m.group_id = g.id where m.user_id = u.id), '{}')
          from app_user u""" ++ Sql.whereAnd(where) ++ fr"order by u.name limit $limit")
      .query[(UUID, String, Option[String], Option[String], Boolean, Boolean, Option[Instant], List[String])]
      .map((id, n, e, a, ad, d, l, g) => UserDto(id, n, e, a, ad, d, l, g)).to[List]
  }

  def update(id: UUID, isAdmin: Option[Boolean], disabled: Option[Boolean]): IO[Boolean] = tx {
    val sets = List(isAdmin.map(v => fr"is_admin = $v"), disabled.map(v => fr"disabled = $v")).flatten
    if sets.isEmpty then findC(id).map(_.isDefined)
    else
      for
        n <- (fr"update app_user" ++ Sql.set(sets) ++ fr"where id = $id").update.run
        _ <- if disabled.contains(true) then sql"delete from session where user_id = $id".update.run else 0.pure[ConnectionIO]
      yield n > 0
  }

  def updateMe(id: UUID, name: Option[String], timezone: Option[String]): IO[Unit] = tx {
    val sets = List(name.map(_.trim).filter(_.nonEmpty).map(v => fr"name = ${v.take(100)}"), timezone.map(v => fr"timezone = $v")).flatten
    if sets.isEmpty then ().pure[ConnectionIO] else (fr"update app_user" ++ Sql.set(sets) ++ fr"where id = $id").update.run.void
  }

  def briefs(ids: List[UUID]): ConnectionIO[List[UserBrief]] =
    if ids.isEmpty then Nil.pure[ConnectionIO]
    else sql"select id, name, avatar_url from app_user where id = any(${ids.distinct.toArray})".query[UserBrief].to[List]

  // ---------------------------------------------------------------- セッション

  def createSession(hash: Array[Byte], userId: UUID, expires: Instant): IO[Unit] = tx(
    sql"insert into session (token_hash, user_id, expires_at) values ($hash, $userId, $expires)".update.run.void
  )

  def findSession(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group])]] = tx(
    sql"select user_id from session where token_hash = $hash and expires_at > $now".query[UUID].option.flatMap {
      case None => none[(User, List[Group])].pure[ConnectionIO]
      case Some(uid) => findC(uid).flatMap(_.filterNot(_.disabled).traverse(u => groupsOf(u.id).map(u -> _)))
    }
  )

  def deleteSession(hash: Array[Byte]): IO[Unit] = tx(sql"delete from session where token_hash = $hash".update.run.void)

  def purgeExpired(now: Instant): IO[Unit] = tx(
    sql"delete from session where expires_at < $now".update.run *>
      sql"delete from login_state where created_at < now() - interval '1 hour'".update.run.void
  )

  // ---------------------------------------------------------------- ログイン途中の状態（OIDC）

  def saveLoginState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit] = tx(
    sql"insert into login_state (state, verifier, nonce, return_to) values ($state, $verifier, $nonce, $returnTo)".update.run.void
  )

  /** 取り出して消す（1回だけ使える）。 */
  def takeLoginState(state: String): IO[Option[(String, String, String)]] = tx(
    sql"""delete from login_state where state = $state and created_at > now() - interval '15 minutes'
          returning verifier, nonce, return_to""".query[(String, String, String)].option
  )

  // ---------------------------------------------------------------- APIキー

  def createKey(userId: UUID, name: String, prefix: String, hash: Array[Byte], scopes: List[String], expires: Option[Instant]): IO[ApiKeyDto] = tx(
    sql"""insert into api_key (id, user_id, name, prefix, key_hash, scopes, expires_at)
          values (${UUID.randomUUID()}, $userId, $name, $prefix, $hash, ${scopes.toArray}, $expires)
          returning id, name, prefix, scopes, created_at, expires_at, last_used_at""".query[ApiKeyDto].unique
  )

  def keys(userId: UUID): IO[List[ApiKeyDto]] = tx(
    sql"""select id, name, prefix, scopes, created_at, expires_at, last_used_at from api_key
          where user_id = $userId and revoked_at is null order by created_at desc""".query[ApiKeyDto].to[List]
  )

  def revokeKey(userId: UUID, id: UUID): IO[Boolean] = tx(
    sql"update api_key set revoked_at = now() where id = $id and user_id = $userId and revoked_at is null".update.run.map(_ > 0)
  )

  /** 有効なキー。最終使用日時は1分単位で記録する。 */
  def findKey(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group], UUID, List[String])]] = tx(
    sql"""select k.id, k.user_id, k.scopes from api_key k join app_user u on u.id = k.user_id
          where k.key_hash = $hash and k.revoked_at is null and (k.expires_at is null or k.expires_at > $now) and not u.disabled"""
      .query[(UUID, UUID, List[String])].option.flatMap {
        case None => none.pure[ConnectionIO]
        case Some((kid, uid, scopes)) =>
          for
            _  <- sql"update api_key set last_used_at = $now where id = $kid and (last_used_at is null or last_used_at < $now - interval '1 minute')".update.run
            u  <- findC(uid)
            gs <- groupsOf(uid)
          yield u.map(x => (x, gs, kid, scopes))
      }
  )

  // ---------------------------------------------------------------- 監査ログ

  def audit(actor: Option[UUID], via: String, action: String, target: Option[String], detail: Json): IO[Unit] = tx(
    sql"insert into audit_log (actor_id, via, action, target, detail) values ($actor, $via, $action, $target, $detail)".update.run.void
  )

  def auditLog(limit: Int): IO[List[AuditDto]] = tx(
    sql"""select a.id, a.at, u.id, u.name, u.avatar_url, a.via, a.action, a.target, a.detail
          from audit_log a left join app_user u on u.id = a.actor_id order by a.at desc limit $limit"""
      .query[(Long, Instant, Option[UUID], Option[String], Option[String], String, String, Option[String], Json)]
      .map((id, at, uid, un, ua, via, ac, t, d) => AuditDto(id, at, (uid, un).mapN((i, n) => UserBrief(i, n, ua)), via, ac, t, d)).to[List]
  )

  def purgeAudit(days: Int): IO[Int] = tx(sql"delete from audit_log where at < now() - make_interval(days => $days)".update.run)
