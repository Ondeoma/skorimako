package skorimako.web

import io.circe.Json
import skorimako.shared.*

import scala.scalajs.js

/** 表示の補助. */
object Fmt {

  private def pad(n: Double, w: Int = 2): String = { val s = n.toInt.toString; "0" * math.max(0, w - s.length) + s }

  /** ミリ秒の日時（2026-11-17 09:08:28）. */
  def ms(t: Double): String = {
    val d = new js.Date(t)
    s"${d.getFullYear().toInt}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}"
  }

  /** マイクロ秒の日時（ミリ秒まで）. */
  def micros(us: Long): String = {
    val d = new js.Date(us / 1000.0)
    ms(us / 1000.0) + "." + pad(d.getMilliseconds(), 3)
  }

  def dateTime(t: Timestamp): String = ms(t.toEpochMilli).take(16)

  /** 「3分前」など. */
  def ago(t: Timestamp): String = agoMs(t.toEpochMilli)

  def agoMs(t: Double): String = {
    val diff = (js.Date.now() - t) / 1000
    if (diff < 60) "たった今"
    else if (diff < 3600) s"${(diff / 60).toInt}分前"
    else if (diff < 86400) s"${(diff / 3600).toInt}時間前"
    else if (diff < 86400 * 30) s"${(diff / 86400).toInt}日前"
    else ms(t).take(10)
  }

  export sbirds.web.Formats.bytes

  def count(n: Long): String = {
    val s = n.toString
    s.reverse.grouped(3).mkString(",").reverse
  }

  /** マイクロ秒の長さ（1.2ms・3.4s）. */
  def duration(us: Long): String =
    if (us < 1000) s"${us}µs"
    else if (us < 1000000) f"${us / 1000.0}%.1fms"
    else if (us < 60000000) f"${us / 1e6}%.2fs"
    else f"${us / 6e7}%.1fmin"

  /** 数値（グラフと同じ書き方）. */
  def num(v: Double, unit: String = ""): String = ChartFacade.formatValue(v, unit)

  /** JSON の値の表示. */
  def cell(j: Json): String = j.asString.getOrElse(if (j.isNull) "" else j.noSpaces)

  /** 重大度の CSS の class（error・warn・info・debug）. */
  def level(s: String): String = {
    val u = s.toUpperCase
    if (
      u.startsWith("ERR") || u.startsWith("FATAL") || u.startsWith("CRIT") || u.startsWith("EMERG") ||
      u.startsWith("ALERT")
    ) "error"
    else if (u.startsWith("WARN")) "warn"
    else if (u.startsWith("DEBUG") || u.startsWith("TRACE")) "debug"
    else if (u.isEmpty) "none"
    else "info"
  }

}
