package skorimako.core

import skorimako.shared.{AlertHitDto, AlertState}

import java.time.Instant

/** アラートの判定（値と閾値の比較、状態の移り変わり）。 */
object AlertLogic:

  def compare(op: String, v: Double, threshold: Double): Boolean = op match
    case ">"  => v > threshold
    case ">=" => v >= threshold
    case "<"  => v < threshold
    case "<=" => v <= threshold
    case "==" => v == threshold
    case "!=" => v != threshold
    case _    => false

  /** 評価の結果、どうするか。 */
  enum Action:
    case None
    case Fire        // 発生を知らせる（初めて、または沈黙の時間が過ぎて再び）
    case Resolve     // 解消を知らせる

  /**
   * 前の状態と今回の結果から、次の状態と知らせるかどうかを決める。
   * silenceMinutes が 0 なら、発生中のあいだは繰り返し知らせない。
   */
  def next(prev: String, firing: Boolean, lastNotified: Option[Instant], silenceMinutes: Int, now: Instant): (String, Action) =
    if firing then
      if prev != AlertState.Firing then (AlertState.Firing, Action.Fire)
      else
        val again = silenceMinutes > 0 && lastNotified.forall(_.plusSeconds(silenceMinutes * 60L).compareTo(now) <= 0)
        (AlertState.Firing, if again then Action.Fire else Action.None)
    else if prev == AlertState.Firing then (AlertState.Ok, Action.Resolve)
    else (AlertState.Ok, Action.None)

  /** 値の表示（整数なら小数点を付けない）。 */
  def fmt(v: Double): String =
    if v == math.rint(v) && math.abs(v) < 1e15 then v.toLong.toString
    else BigDecimal(v).round(new java.math.MathContext(6)).bigDecimal.stripTrailingZeros.toPlainString

  def describe(hits: List[AlertHitDto], op: String, threshold: Double, max: Int = 10): String =
    val parts = hits.take(max).map(h => h.group.fold(s"${fmt(h.value)}")(g => s"$g: ${fmt(h.value)}"))
    val more  = if hits.size > max then s" ほか${hits.size - max}件" else ""
    s"${parts.mkString("、")}$more（条件 $op ${fmt(threshold)}）"
