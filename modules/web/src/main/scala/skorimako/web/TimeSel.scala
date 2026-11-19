package skorimako.web

import scala.scalajs.js

/** 時間の範囲. preset は 15m・1h・6h・24h・7d・30d・custom（custom のときは from・to のミリ秒）. */
final case class TimeSel(
  preset: String,
  from: Double = 0,
  to: Double = 0) {

  /** (始まり, 終わり) のマイクロ秒. 相対の範囲は呼んだ時点の今から. */
  def micros: (Long, Long) = {
    val now = js.Date.now()
    val (a, b) = TimeSel.presetMs(preset) match {
      case Some(ms) => (now - ms, now)
      case None => (from, to)
    }
    ((a * 1000).toLong, (b * 1000).toLong)
  }

  def label: String = TimeSel.presets.find(_._1 == preset).map(_._2).getOrElse(s"${Fmt.ms(from)} 〜 ${Fmt.ms(to)}")
}

object TimeSel {
  val presets: List[(String, String)] = List(
    "5m" -> "直近5分",
    "15m" -> "直近15分",
    "1h" -> "直近1時間",
    "6h" -> "直近6時間",
    "24h" -> "直近24時間",
    "7d" -> "直近7日",
    "30d" -> "直近30日"
  )
  def presetMs(
    p: String
  ): Option[Double] = p match {
    case "5m" => Some(5 * 60e3)
    case "15m" => Some(15 * 60e3)
    case "1h" => Some(3600e3)
    case "6h" => Some(6 * 3600e3)
    case "24h" => Some(24 * 3600e3)
    case "7d" => Some(7 * 86400e3)
    case "30d" => Some(30 * 86400e3)
    case _ => None
  }
  def custom(
    fromMs: Double,
    toMs: Double
  ): TimeSel = TimeSel("custom", math.min(fromMs, toMs), math.max(fromMs, toMs))
}
