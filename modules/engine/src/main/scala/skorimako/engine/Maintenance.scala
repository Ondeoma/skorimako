package skorimako.engine

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import skorimako.core.{ErrorCode, Prune}
import skorimako.db.*
import skorimako.storage.ObjectStore

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

final case class MaintenanceConfig(
  retentionDays: Int = 30, // ストリームに保持期間がなければこの日数
  compactMinAgeSeconds: Int = 300, // 書いてからこれだけたったファイルをまとめる
  compactMaxBytes: Long = 256L << 20, // まとめた結果の大きさの上限
  deleteGraceSeconds: Int = 3600 // 削除の印を付けてから S3 から消すまで（検索の途中のもののため）
)

/**
 * 保守: 小さいファイルの圧縮（同じストリーム・同じ時間のものを1つに）、保持期間を過ぎたファイルの削除、削除の印の付いたファイルの片付け。
 * どれも job_lock で1ノードだけが行う。
 */
final class Maintenance(store: StreamStore,
                        objects: ObjectStore,
                        engine: QueryEngine,
                        duck: DuckConfig,
                        cfg: MaintenanceConfig,
                        lock: JobLock,
                        nodeId: String,
                        tmpDir: Path) {

  /** 圧縮を1回。まとめたファイルの数。 */
  def compact: IO[Int] =
    store.compactionGroups(
      cfg.compactMinAgeSeconds,
      cfg.compactMaxBytes,
      50
    ).flatMap(_.traverse(compactGroup).map(_.sum))

  private def compactGroup(group: List[FileRow]): IO[Int] =
    store.byId(group.head.streamId).flatMap {
      case None => IO.pure(0)
      case Some(st) =>
        val dir = tmpDir.resolve("compact-" + java.util.UUID.randomUUID())
        Resource.make(IO.blocking(Files.createDirectories(dir)))(d => IO.blocking(deleteTree(d))).use { d =>
          for {
            locals <- group.traverse(f =>
              objects.download(f.key, d.resolve(s"${f.id}.parquet")).map(Option.when(_)(d.resolve(s"${f.id}.parquet")))
            )
            n <- if locals.exists(_.isEmpty) then
              IO(SelfLog.warn(s"compaction skipped (missing object) for ${st.name}")).as(0)
            else {
              val out = d.resolve("out.parquet")
              for {
                merged <- IO.blocking(Duck.mergeParquet(duck, locals.flatten, out))
                (rows, minTs, maxTs) = merged
                key = Ingester.objectKey(st.streamType, st.name, minTs, nodeId, "c-")
                size <- IO.blocking(Files.size(out))
                _ <- objects.put(key, out)
                ok <- store.replaceFiles(
                  group,
                  NewFile(
                    st.id,
                    key,
                    minTs,
                    maxTs,
                    rows,
                    size,
                    group.map(_.level).max + 1,
                    nodeId,
                    Prune.mergeTags(group.map(_.tags))
                  )
                )
                _ <- if ok then IO.unit else objects.delete(key) // ほかで先に消されていた
              } yield if ok then group.size else 0
            }
          } yield n
        }
    }

  private def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(x => Files.deleteIfExists(x))

  /** 保持期間を過ぎたものに削除の印を付け、印を付けてからしばらくたったファイルを S3 から消す。 */
  def expireAndDelete: IO[(Int, Int)] =
    for {
      now <- IO.realTime.map(_.toMicros)
      expired <- store.expire(cfg.retentionDays, now)
      deleted <- deleteMarked
    } yield (expired, deleted)

  def deleteMarked: IO[Int] =
    store.deletedFiles(cfg.deleteGraceSeconds, 1000).flatMap { fs =>
      fs.parTraverseN(8)((id, key) =>
        (objects.delete(key) *> engine.forgetCached(key)).attempt.map(r => Option.when(r.isRight)(id))
      )
        .flatMap(ids => store.forget(ids.flatten).as(ids.flatten.size))
    }

  def compactLoop: IO[Nothing] =
    Periodic.run(2.minutes, 5.minutes, ErrorCode.CompactionFailed)(
      lock.exclusive("compact", 30.minutes)(compact.flatMap(n =>
        IO.whenA(n > 0)(sbirds.observe.Log.println(s"[skorimako] compacted $n file(s)"))
      ))
    )

  def retentionLoop: IO[Nothing] =
    Periodic.run(5.minutes, 30.minutes, ErrorCode.RetentionFailed)(
      lock.exclusive("retention", 25.minutes)(expireAndDelete.flatMap((e, d) =>
        IO.whenA(
          e + d > 0
        )(sbirds.observe.Log.println(s"[skorimako] retention: expired $e file(s), deleted $d object(s)"))
      ))
    )

  /** キャッシュの掃除（ノードごと）。 */
  def cacheLoop: IO[Nothing] =
    Periodic.run(1.minute, 5.minutes, ErrorCode.CacheCleanupFailed)(engine.cleanCache)
}
