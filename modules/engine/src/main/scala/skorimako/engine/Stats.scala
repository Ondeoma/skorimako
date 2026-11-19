package skorimako.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, DoubleAdder}
import scala.jdk.CollectionConverters.*

/**
 * このノードの動きの数（自己監視）。名前とラベルごとの累積の値（カウンター）と、今の値（ゲージ）。
 * SelfMonitor が定期的にメトリクスとして skorimako 自身に取り込む。
 */
final class Stats {
  private val counters = new ConcurrentHashMap[(String, Map[String, String]), DoubleAdder]()
  private val gauges = new ConcurrentHashMap[(String, Map[String, String]), AtomicLong]()

  def inc(name: String, labels: Map[String, String] = Map.empty, by: Double = 1): Unit =
    counters.computeIfAbsent((name, labels), _ => new DoubleAdder()).add(by)

  def set(name: String, labels: Map[String, String] = Map.empty, v: Long): Unit =
    gauges.computeIfAbsent((name, labels), _ => new AtomicLong()).set(v)

  /** 処理にかかった秒を足す（{name}_seconds_sum と {name}_total）。 */
  def time[A](name: String, labels: Map[String, String] = Map.empty)(f: => A): A = {
    val t0 = System.nanoTime()
    try f
    finally {
      inc(name + "_seconds_sum", labels, (System.nanoTime() - t0) / 1e9)
      inc(name + "_total", labels)
    }
  }

  def snapshot: (List[((String, Map[String, String]), Double)], List[((String, Map[String, String]), Long)]) =
    (
      counters.asScala.toList.map((k, v) => k -> v.sum()),
      gauges.asScala.toList.map((k, v) => k -> v.get())
    )
}
