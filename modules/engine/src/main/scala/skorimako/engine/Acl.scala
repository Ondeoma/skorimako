package skorimako.engine

import cats.effect.*
import skorimako.db.StreamStore

import scala.concurrent.duration.*

/**
 * 検索する人。admin はシステム管理者（すべてのストリームを見られる）、groups は IdP のグループ。
 * key は利用者ごとの検索の同時実行の上限の単位（ユーザーの ID）。None はサーバーの中の処理（上限の対象外）。
 */
final case class Viewer(admin: Boolean, groups: Set[String], key: Option[String] = None)

object Viewer {

  /** サーバーの中の処理（アラートの評価・自己監視）。 */
  val system: Viewer = Viewer(admin = true, Set.empty)
}

/**
 * ストリームの閲覧権限。ストリームに閲覧できるグループ（allowed_groups）が決めてあれば、その所属者と管理者だけが見られる。
 * 決めていなければログインした全員。一覧は30秒ごと（と変更のとき）に読み直す。
 */
final class Acl private (store: StreamStore, cache: Ref[IO, (Long, Map[(String, String), Set[String]])]) {

  private def table: IO[Map[(String, String), Set[String]]] =
    IO.monotonic.flatMap { now =>
      cache.get.flatMap {
        (at, m) =>
          if now.toMillis - at < 30000 && at > 0 then IO.pure(m)
          else
            store.restricted.map(_.map((t, n, gs) => (t, n) -> gs.toSet).toMap).flatTap(m =>
              cache.set((now.toMillis, m))
            )
      }
    }

  def invalidate: IO[Unit] = cache.set((0L, Map.empty))

  def visible(v: Viewer, streamType: String, name: String): IO[Boolean] =
    if v.admin then IO.pure(true) else table.map(_.get((streamType, name)).forall(_.exists(v.groups.contains)))

  /** 見られるものだけに絞る。 */
  def filter[A](v: Viewer, streamType: String, xs: List[A])(name: A => String): IO[List[A]] =
    if v.admin then IO.pure(xs)
    else table.map(t => xs.filter(x => t.get((streamType, name(x))).forall(_.exists(v.groups.contains))))

  /** その人が見られないストリーム（種類, 名前）。 */
  def hidden(v: Viewer): IO[List[(String, String)]] =
    if v.admin then IO.pure(Nil) else table.map(_.collect { case (k, gs) if !gs.exists(v.groups.contains) => k }.toList)

  def filterTyped[A](v: Viewer, xs: List[A])(key: A => (String, String)): IO[List[A]] =
    if v.admin then IO.pure(xs) else table.map(t => xs.filter(x => t.get(key(x)).forall(_.exists(v.groups.contains))))
}

object Acl {
  def create(store: StreamStore): IO[Acl] =
    Ref.of[IO, (Long, Map[(String, String), Set[String]])]((0L, Map.empty)).map(new Acl(store, _))
  val refresh: FiniteDuration = 30.seconds
}
