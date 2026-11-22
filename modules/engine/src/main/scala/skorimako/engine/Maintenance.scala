package skorimako.engine

import sbirds.db.*
import cats.effect.*
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
  deleteGraceSeconds: Int = 3600, // 削除の印を付けてから S3 から消すまで（検索の途中のもののため）
  compactOpenMinFiles: Int = 60, // まだ続いている時間は、取り込んだままのファイルがこれだけたまったらまとめる
  compactCloseGraceSeconds: Int = 900, // 時間が終わってからこれだけたったら、その時間のファイルを 1 つにまとめる
  compactParallel: Int = 3, // 同時にまとめる束の数（COMPACT_PARALLEL）
  budget: FiniteDuration = 20.minutes // 1 回の保守で続けて働く時間の上限（残りは次の回に）
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

  /** 圧縮を1回（ファイルの多い束から 100 束まで）。まとめたファイルの数。 */
  def compact: IO[Int] =
    IO.realTime.map(_.toMicros).flatMap(now =>
      store.compactionGroups(
        cfg.compactMinAgeSeconds,
        cfg.compactMaxBytes,
        100,
        now,
        cfg.compactCloseGraceSeconds,
        cfg.compactOpenMinFiles
      )
    ).flatMap(_.parTraverseN(math.max(1, cfg.compactParallel))(g =>
      // 1 つの束の失敗で、残りの束を止めない
      compactGroup(g).handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.CompactionFailed, e.toString, e).as(0))
    ).map(_.sum))

  /**
   * 残りがなくなるか、時間の上限（budget）まで、step を繰り返す（取り込みに追いつくように、間をあけずに続ける）。
   * 回ごとにリースを取り直す（延ばす）。取れなければ、ほかのノードに任せて止める。進まなくなったら（0 件）止める。
   */
  private def drain(job: String, step: IO[Int]): IO[Int] =
    IO.monotonic.flatMap { start =>
      def loop(total: Int): IO[Int] =
        lock.acquire(job, cfg.budget + 30.minutes).flatMap {
          case false => IO.pure(total)
          case true =>
            step.flatMap(n =>
              IO.monotonic.flatMap(t =>
                if n == 0 || t - start > cfg.budget then IO.pure(total + n) else loop(total + n)
              )
            )
        }
      loop(0)
    }

  private def compactGroup(group: List[FileRow]): IO[Int] =
    store.byId(group.head.streamId).flatMap {
      case None => IO.pure(0)
      case Some(st) =>
        val dir = tmpDir.resolve("compact-" + java.util.UUID.randomUUID())
        Resource.make(IO.blocking(Files.createDirectories(dir)))(d => IO.blocking(deleteTree(d))).use { d =>
          for {
            locals <- group.parTraverseN(8)(f =>
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

  /** 保持期間を過ぎたものに削除の印を付ける（S3 から消すのは purgeLoop）。 */
  def expire: IO[Int] = IO.realTime.map(_.toMicros).flatMap(store.expire(cfg.retentionDays, _))

  /** 削除の印を付けてからしばらくたったファイルを、S3 から 1000 個まで消す。消した数。 */
  def deleteMarked: IO[Int] =
    store.deletedFiles(cfg.deleteGraceSeconds, 1000).flatMap { fs =>
      fs.parTraverseN(16)((id, key) =>
        (objects.delete(key) *> engine.forgetCached(key)).attempt.map(r => Option.when(r.isRight)(id))
      )
        .flatMap(ids => store.forget(ids.flatten).as(ids.flatten.size))
    }

  def compactLoop: IO[Nothing] =
    Periodic.run(2.minutes, 5.minutes, ErrorCode.CompactionFailed)(
      drain("compact", compact).flatMap(n =>
        IO.whenA(n > 0)(sbirds.observe.Log.println(s"[skorimako] compacted $n file(s)"))
      )
    )

  def retentionLoop: IO[Nothing] =
    Periodic.run(5.minutes, 30.minutes, ErrorCode.RetentionFailed)(
      lock.exclusive("retention", 25.minutes)(expire.flatMap(e =>
        IO.whenA(e > 0)(sbirds.observe.Log.println(s"[skorimako] retention: expired $e file(s)"))
      ))
    )

  /** 削除の印の付いたファイルの片付け（圧縮で印の付く量に追いつくように、残りがなくなるまで続ける）。 */
  def purgeLoop: IO[Nothing] =
    Periodic.run(3.minutes, 5.minutes, ErrorCode.RetentionFailed)(
      drain("purge", deleteMarked).flatMap(d =>
        IO.whenA(d > 0)(sbirds.observe.Log.println(s"[skorimako] purge: deleted $d object(s)"))
      )
    )

  /** キャッシュの掃除（ノードごと）。 */
  def cacheLoop: IO[Nothing] =
    Periodic.run(1.minute, 5.minutes, ErrorCode.CacheCleanupFailed)(engine.cleanCache)
}
