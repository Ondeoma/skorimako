package skorimako.engine

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import sbirds.db.{Database, DbConfig}
import skorimako.db.*

import java.util.UUID

/** 圧縮の束の選び方。DB を使うテストは TEST_DATABASE_URL がなければ飛ばす（例: jdbc:postgresql://localhost:54383/skorimako_test）。 */
class CompactionSpec extends munit.FunSuite {

  private val Hour = Compaction.HourMicros

  private def row(id: Long, stream: UUID, minTs: Long, size: Long = 100, level: Int = 0) =
    FileRow(id, stream, s"k$id", minTs, minTs + 1, 1, size, level)

  test("束ね方: ストリーム・時間ごとに束ね、1 つだけの束は捨て、大きさと数の上限で切り、多い束から並べる") {
    val a = UUID.randomUUID(); val b = UUID.randomUUID()
    val files = (1 to 5).toList.map(i => row(i, a, 10 * Hour + i)) ++ // a の 10 時: 5 個
      List(row(6, a, 11 * Hour)) ++ // a の 11 時: 1 個だけ
      (7 to 9).toList.map(i => row(i, b, 10 * Hour + i)) // b の 10 時: 3 個
    val gs = Compaction.groups(files, maxBytes = 1000, maxFiles = 4)
    assertEquals(gs.map(_.map(_.id)), List(List(1L, 2L, 3L, 4L), List(7L, 8L, 9L)))
    // 大きさの上限: 100 + 100 までで、3 個目は入らない
    assertEquals(Compaction.groups(files, maxBytes = 250, maxFiles = 10).map(_.size), List(2, 2))
  }

  private val db = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(
      url,
      sys.env.getOrElse("TEST_DATABASE_USER", "skorimako"),
      sys.env.getOrElse("TEST_DATABASE_PASSWORD", "skorimako")
    )
  )

  test("候補の選び方: 終わった時間はすべて・続いている時間はレベル 0 がたまったときだけ・後ろのストリームも選ばれる") {
    assume(db.isDefined, "TEST_DATABASE_URL がない")
    val now = 1000L * Hour + Hour / 2 // 1000 時の途中
    val closed = 990L * Hour
    val open = 1000L * Hour
    (Database.migrate(db.get) *> Database.transactor(db.get, telemetry = false).use { xa =>
      val store = new StreamStore(xa)
      def add(st: StreamRow, minTs: Long, level: Int = 0) =
        store.addFile(NewFile(st.id, s"t/${UUID.randomUUID()}", minTs, minTs + 1, 1, 100, level, "n", Map.empty), Nil)
      val tag = UUID.randomUUID().toString.take(8)
      for {
        ss <- (1 to 4).toList.traverse(i => store.ensure("logs", s"compact-$tag-$i"))
        List(s1, s2, s3, s4) = ss: @unchecked
        // s1: 終わった時間に、まとめ済み 1 個と新しい 2 個 → 3 個ともまとめる
        _ <- add(s1, closed, level = 1) *> add(s1, closed + 1) *> add(s1, closed + 2)
        // s2: 続いている時間に、まとめ済み 1 個とレベル 0 が 3 個 → レベル 0 の 3 個だけ（しきい値 3）
        _ <- add(s2, open, level = 1) *> (1 to 3).toList.traverse_(i => add(s2, open + i))
        // s3: 続いている時間に、レベル 0 が 2 個 → まだまとめない
        _ <- add(s3, open + 1) *> add(s3, open + 2)
        // s4: 終わった時間に 1 個だけ → まとめるものがない
        _ <- add(s4, closed)
        _ <- IO.sleep(scala.concurrent.duration.DurationInt(50).millis)
        gs <- store.compactionGroups(0, 1L << 20, 100, now, closeGraceSeconds = 900, openMinFiles = 3)
        mine = gs.filter(g => ss.exists(_.id == g.head.streamId)).map(g => (g.head.streamId, g.map(_.level).sorted))
        _ = assertEquals(mine.toSet, Set((s1.id, List(0, 0, 1)), (s2.id, List(0, 0, 0))))
        // 束の数の上限: ファイルの多い束（s1・s2 の 3 個ずつ）から選ばれる
        one <- store.compactionGroups(0, 1L << 20, 1, now, closeGraceSeconds = 900, openMinFiles = 3)
        _ = assertEquals(one.size, 1)
        _ <- ss.traverse_(s => store.delete(s.id))
      } yield ()
    }).unsafeRunSync()
  }
}
