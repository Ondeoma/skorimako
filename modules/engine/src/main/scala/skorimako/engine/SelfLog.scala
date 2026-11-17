package skorimako.engine

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * skorimako 自身の警告・エラーのログ。標準出力（logback）に出し、同時にためておく（SelfMonitor が自分のログのストリームに取り込む）。
 * 取り込みが止まっていても溢れないよう、たまるのは1万件まで。
 */
object SelfLog:
  final case class Entry(ts: Long, level: String, message: String)
  private val q = new ConcurrentLinkedQueue[Entry]()

  def warn(msg: String): Unit = add("WARN", msg)
  def error(msg: String): Unit = add("ERROR", msg)
  def info(msg: String): Unit = add("INFO", msg)

  private def add(level: String, msg: String): Unit =
    val log = skorimako.core.Log.logger
    level match
      case "ERROR" => log.error(msg)
      case "WARN"  => log.warn(msg)
      case _       => log.info(msg)
    if q.size() < 10000 then q.add(Entry(System.currentTimeMillis() * 1000, level, msg.take(4000)))

  def drain(): List[Entry] =
    val out = List.newBuilder[Entry]
    var e = q.poll()
    while e != null do { out += e; e = q.poll() }
    out.result()
