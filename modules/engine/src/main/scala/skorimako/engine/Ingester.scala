package skorimako.engine

import cats.effect.*
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.circe.Json
import skorimako.core.*
import skorimako.db.*
import skorimako.shared.*
import skorimako.storage.ObjectStore

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final case class IngestConfig(
  dataDir: Path, // WAL と一時ファイルの置き場所（ノードごと）
  nodeId: String,
  flushInterval: FiniteDuration = 10.seconds,
  flushRows: Int = 200000, // 1つのストリームにこれだけたまったら早めに書き出す
  maxBufferedRows: Long = 2000000, // これを超えたら受け付けない（503。送り手が再送する）
  walSync: Boolean = false // 書くたびに fsync する（遅いが、OS ごと落ちても失わない）
)

/** 1回の書き出しの結果。 */
final case class FlushStats(files: Int, rows: Long, failed: Int)

/**
 * 取り込み: 受けた行をストリームごとにメモリにため、同時にローカルの WAL（追記のファイル）にも書く。
 * flushInterval ごとに Parquet にして S3 に置き、PostgreSQL に登録してから WAL を消す。
 * 起動時に WAL が残っていれば（前回の異常終了）、読み直して書き出す。
 */
final class Ingester private (cfg: IngestConfig,
                              duck: DuckConfig,
                              store: StreamStore,
                              objects: ObjectStore,
                              mutex: Mutex[IO],
                              stats: Stats) {

  private type Key = (String, String) // (種類, ストリーム名)

  // mutex の中だけで触る
  private val buffers = mutable.LinkedHashMap.empty[Key, mutable.ArrayBuffer[Row]]
  private val metas = mutable.Map.empty[String, MetricMeta]
  private var buffered = 0L
  private var since = System.nanoTime()
  private var segment: Path = null
  private var writer: BufferedWriter = null
  private var channel: java.nio.channels.FileChannel = null

  private val walDir = cfg.dataDir.resolve("wal")
  private val tmpDir = cfg.dataDir.resolve("tmp")

  private def openSegment(): Unit = {
    segment = walDir.resolve(f"${System.currentTimeMillis()}%015d-${UUID.randomUUID().toString.take(8)}.wal")
    val out = new java.io.FileOutputStream(segment.toFile)
    channel = out.getChannel
    writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16)
  }

  private def walWrite(streamType: String, stream: String, rows: Iterable[Row], meta: Option[MetricMeta]): Unit = {
    val j = Json.obj(
      "t" -> Json.fromString(streamType),
      "s" -> Json.fromString(stream),
      "m" -> meta.fold(Json.Null)(m =>
        Json.obj(
          "d" -> Json.fromString(m.description),
          "u" -> Json.fromString(m.unit),
          "k" -> Json.fromString(m.metricType)
        )
      ),
      "r" -> Json.fromValues(rows.map(_.toJson))
    )
    writer.write(j.noSpaces)
    writer.write('\n')
  }

  private def walFlush(): Unit = {
    writer.flush()
    if cfg.walSync then channel.force(false)
  }

  private def addLocal(streamType: String, stream: String, rows: Iterable[Row]): Unit = {
    val b = buffers.getOrElseUpdate((streamType, stream), mutable.ArrayBuffer.empty)
    b ++= rows
    buffered += rows.size
  }

  /** 登録済みのストリーム（書き出す前でも名前で検索できるよう、初めて受けたときに登録する）。 */
  private val known = java.util.concurrent.ConcurrentHashMap.newKeySet[(String, String)]()

  private def register(streamType: String, names: Iterable[String]): IO[Unit] =
    names.toList.filterNot(n => known.contains((streamType, n))).traverse_(n =>
      store.ensure(streamType, n).flatMap(_ => IO(known.add((streamType, n)))).void
    )

  /** 受けた行を加える。たまりすぎていたら Left（503 で断る）。 */
  def ingest(streamType: String, batch: Batch): IO[Either[String, Int]] =
    if batch.rows.isEmpty then IO.pure(Right(0))
    else
      register(streamType, batch.rows.keys) *> mutex.lock.surround(IO.blocking {
        if buffered + batch.size > cfg.maxBufferedRows then Left("取り込みが混み合っています。しばらくしてから送り直してください")
        else {
          batch.rows.foreach {
            (stream, rows) =>
              if rows.nonEmpty then {
                walWrite(streamType, stream, rows, batch.meta.get(stream))
                addLocal(streamType, stream, rows)
              }
          }
          batch.meta.foreach((s, m) => metas((streamType, s).toString) = m)
          walFlush()
          stats.inc("skorimako_ingested_rows_total", Map("type" -> streamType), batch.size)
          stats.set("skorimako_buffered_rows", Map.empty, buffered)
          Right(batch.size)
        }
      })

  /** 書き出す前の行（検索に出すため）。names のストリームの、時刻が範囲に入るもの。 */
  def snapshot(streamType: String, names: Set[String], start: Long, end: Long): IO[Map[String, Vector[Row]]] =
    mutex.lock.surround(IO {
      buffers.collect {
        case ((t, n), rows) if t == streamType && names.contains(n) =>
          n -> rows.iterator.filter(r => r.ts >= start && r.ts <= end).toVector
      }.filter(_._2.nonEmpty).toMap
    })

  /** たまっている行の数（ストリームごと）。 */
  def pending: IO[Map[String, Long]] =
    mutex.lock.surround(IO(buffers.map((k, v) => s"${k._1}/${k._2}" -> v.size.toLong).toMap))

  /** 書き出す時期か。 */
  private def due: IO[Boolean] = mutex.lock.surround(IO {
    buffered > 0 &&
    ((System.nanoTime() - since).nanos >= cfg.flushInterval || buffers.values.exists(_.size >= cfg.flushRows))
  })

  /** たまっている行をすべて書き出す。失敗したストリームの行はバッファと新しい WAL に戻す。 */
  def flush: IO[FlushStats] =
    IO.monotonic.flatMap(t0 =>
      flush0.flatTap(r =>
        IO.monotonic.map { t1 =>
          stats.inc("skorimako_flush_seconds_sum", Map.empty, (t1 - t0).toMillis / 1000.0)
          stats.inc("skorimako_flush_total")
          stats.inc("skorimako_flush_files_total", Map.empty, r.files)
          if r.failed > 0 then stats.inc("skorimako_flush_failures_total", Map.empty, r.failed)
          stats.set("skorimako_buffered_rows", Map.empty, 0)
        }
      )
    )

  private def flush0: IO[FlushStats] =
    for {
      taken <- mutex.lock.surround(IO.blocking {
        val t = buffers.toList.map((k, v) => k -> v.toVector)
        val m = metas.toMap
        val old = segment
        buffers.clear(); metas.clear(); buffered = 0; since = System.nanoTime()
        writer.close()
        openSegment()
        (t, m, old)
      })
      (batches, ms, oldSeg) = taken
      results <- batches.traverse { case ((t, s), rows) =>
        writeOne(t, s, rows, ms.get((t, s).toString)).attempt.map {
          case Right(n) => Right(n)
          case Left(e) => Left((t, s, rows, e))
        }
      }
      failed = results.collect { case Left(f) => f }
      _ <- failed.traverse_((t, s, _, e) =>
        IO(SelfLog.warn(
          s"flush failed for $t/$s (${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(200)}); will retry"
        ))
      )
      _ <- mutex.lock.surround(IO.blocking {
        failed.foreach((t, s, rows, _) => { walWrite(t, s, rows, ms.get((t, s).toString)); addLocal(t, s, rows) })
        if failed.nonEmpty then walFlush()
        Files.deleteIfExists(oldSeg)
      })
    } yield FlushStats(results.count(_.isRight), results.collect { case Right(n) => n }.sum, failed.size)

  /** 1つのストリームの行を Parquet にして S3 に置き、登録する。 */
  private def writeOne(streamType: String, stream: String, rows: Vector[Row], meta: Option[MetricMeta]): IO[Long] =
    for {
      st <- store.ensure(streamType, stream)
      inferred = rows.foldLeft(Map.empty[String, String]) {
        (acc, r) =>
          r.fields.foldLeft(acc) { case (a, (k, v)) =>
            a.updated(k, a.get(k).fold(v.fieldType)(FieldTypes.widen(_, v.fieldType)))
          }
      }
      types <- store.mergeFields(st.id, inferred - Columns.Timestamp)
      _ <- meta.traverse_(m => store.setMetricMeta(st.id, m.description, m.unit, m.metricType))
      minTs = rows.iterator.map(_.ts).min
      maxTs = rows.iterator.map(_.ts).max
      key = Ingester.objectKey(streamType, stream, minTs, cfg.nodeId, "")
      size <- Resource.make(IO.blocking(Files.createTempFile(tmpDir, "out-", ".parquet")))(p =>
        IO.blocking(Files.deleteIfExists(p)).void
      ).use { out =>
        IO.blocking(Duck.writeParquet(duck, rows, types, out)) *> objects.put(key, out) *> IO.blocking(Files.size(out))
      }
      traces = if streamType == StreamType.Traces then Ingester.traceParts(rows) else Nil
      tags = Prune.tags(rows).filter((k, _) => types.get(k).contains(FieldType.Utf8))
      _ <- store.addFile(NewFile(st.id, key, minTs, maxTs, rows.size.toLong, size, 0, cfg.nodeId, tags), traces)
      _ = stats.inc("skorimako_stored_bytes_total", Map("type" -> streamType), size.toDouble)
    } yield rows.size.toLong

  /** 書き出しのループ（1秒ごとに時期を確かめる）。 */
  def loop: IO[Nothing] =
    (IO.sleep(1.second) *>
      due.ifM(flush.void.handleErrorWith(e => IO(SelfLog.error(s"flush error: $e"))), IO.unit)).foreverM

  /** 止めるとき: 残りを書き出す（失敗しても WAL に残るので、次の起動で書き出す）。 */
  def shutdown: IO[Unit] =
    flush.attempt.void *> mutex.lock.surround(IO.blocking(if writer != null then writer.close()))

  /** 前回の WAL を読み込む（起動時）。 */
  private def recover: IO[Int] =
    IO.blocking {
      Files.createDirectories(walDir); Files.createDirectories(tmpDir)
      // 前回の一時ファイルは要らない
      Files.list(tmpDir).iterator().asScala.toList.foreach(p => scala.util.Try(Files.deleteIfExists(p)))
      val segs =
        Files.list(walDir).iterator().asScala.filter(_.toString.endsWith(".wal")).toList.sortBy(_.getFileName.toString)
      var n = 0
      segs.foreach { seg =>
        Files.lines(seg, StandardCharsets.UTF_8).iterator().asScala.foreach { line =>
          // 途中で切れた最後の行などは読み飛ばす
          io.circe.parser.parse(line).toOption.foreach { j =>
            val c = j.hcursor
            (c.get[String]("t"), c.get[String]("s")).tupled.toOption.foreach {
              (t, s) =>
                val rows = c.get[Vector[Json]]("r").getOrElse(Vector.empty).flatMap(Row.fromJson)
                addLocal(t, s, rows)
                c.downField("m").focus.filterNot(_.isNull).foreach { m =>
                  val mc = m.hcursor
                  metas((t, s).toString) = MetricMeta(
                    mc.get[String]("d").getOrElse(""),
                    mc.get[String]("u").getOrElse(""),
                    mc.get[String]("k").getOrElse("")
                  )
                }
                n += rows.size
            }
          }
        }
      }
      openSegment()
      // 読み込んだ分は新しい WAL に書き直し、古いものは消す
      buffers.foreach((k, rows) => walWrite(k._1, k._2, rows, metas.get(k.toString)))
      writer.flush()
      segs.foreach(Files.deleteIfExists)
      n
    }
}

object Ingester {

  def create(cfg: IngestConfig,
             duck: DuckConfig,
             store: StreamStore,
             objects: ObjectStore,
             stats: Stats): IO[Ingester] =
    for {
      m <- Mutex[IO]
      i = new Ingester(cfg, duck, store, objects, m, stats)
      n <- m.lock.surround(i.recover)
      _ <- IO.whenA(n > 0)(sbirds.observe.Log.println(s"[skorimako] recovered $n row(s) from WAL"))
    } yield i

  /** S3 のキー: {種類}/{ストリーム}/{年}/{月}/{日}/{時}/{ノード}-{乱数}.parquet（時刻は最も古い行の UTC）。 */
  def objectKey(streamType: String, stream: String, minTs: Long, node: String, prefix: String): String = {
    val t = Instant.ofEpochSecond(minTs / 1000000).atOffset(ZoneOffset.UTC)
    val n = node.replaceAll("[^A-Za-z0-9_-]", "_").take(40)
    f"$streamType/$stream/${t.getYear}%04d/${t.getMonthValue}%02d/${t.getDayOfMonth}%02d/${t.getHour}%02d/$prefix$n-${UUID.randomUUID()}.parquet"
  }

  /** スパンの行からトレースの索引に足す内容。 */
  def traceParts(rows: Seq[Row]): List[TracePart] =
    rows.groupBy(_.str("trace_id").getOrElse("")).toList.filter(_._1.nonEmpty).map {
      (id, spans) =>
        val root = spans.find(_.str("parent_span_id").forall(_.isEmpty))
        TracePart(
          id,
          spans.map(_.ts).min,
          spans.map(r => r.fields.get("end_time").collect { case Value.Int(v) => v }.getOrElse(r.ts)).max,
          root.flatMap(_.str("service_name")).getOrElse(""),
          root.flatMap(_.str("operation_name")).getOrElse(""),
          spans.flatMap(_.str("service_name")).distinct.sorted.toList,
          spans.size,
          spans.count(_.str("status_code").contains("ERROR"))
        )
    }
}
