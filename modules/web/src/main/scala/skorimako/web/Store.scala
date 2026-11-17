package skorimako.web

import io.circe.Json
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skorimako.shared.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.{Failure, Success}

final case class Toast(id: Int, text: String, link: Option[String], error: Boolean)

/** 時間の範囲. preset は 15m・1h・6h・24h・7d・30d・custom（custom のときは from・to のミリ秒）. */
final case class TimeSel(preset: String, from: Double = 0, to: Double = 0) {

  /** (始まり, 終わり) のマイクロ秒. 相対の範囲は呼んだ時点の今から. */
  def micros: (Long, Long) = {
    val now = js.Date.now()
    val (a, b) = TimeSel.presetMs(preset) match {
      case Some(ms) => (now - ms, now)
      case None     => (from, to)
    }
    ((a * 1000).toLong, (b * 1000).toLong)
  }

  def label: String = TimeSel.presets.find(_._1 == preset).map(_._2).getOrElse(s"${Fmt.ms(from)} 〜 ${Fmt.ms(to)}")
}

object TimeSel {
  val presets: List[(String, String)] = List("5m" -> "直近5分", "15m" -> "直近15分", "1h" -> "直近1時間", "6h" -> "直近6時間", "24h" -> "直近24時間",
    "7d" -> "直近7日", "30d" -> "直近30日")
  def presetMs(p: String): Option[Double] = p match {
    case "5m"  => Some(5 * 60e3)
    case "15m" => Some(15 * 60e3)
    case "1h"  => Some(3600e3)
    case "6h"  => Some(6 * 3600e3)
    case "24h" => Some(24 * 3600e3)
    case "7d"  => Some(7 * 86400e3)
    case "30d" => Some(30 * 86400e3)
    case _     => None
  }
  def custom(fromMs: Double, toMs: Double): TimeSel = TimeSel("custom", math.min(fromMs, toMs), math.max(fromMs, toMs))
}

/** 画面全体で共有する状態. */
object Store {

  val me: RV[Option[MeDto]] = RV(None)
  val config: RV[Option[ClientConfigDto]] = RV(None)
  val toasts: RV[List[Toast]] = RV(Nil)
  /** サイドバーを開いているか（スマホの幅）. */
  val sideOpen: RV[Boolean] = RV(false)
  /** 時間の範囲（画面をまたいで同じ. この端末に記憶する）. */
  val range: RV[TimeSel] = RV(TimeSel(Prefs.get("range", "1h")))

  /** 読み直しの合図（↻ のボタン）. */
  val tick: RV[Int] = RV(0)

  private var toastSeq = 0

  def refresh(): Unit = tick := tick.v + 1

  /** 時間の範囲が変わったとき・読み直しのときに f を呼ぶ（画面の mounted で使う）. */
  def onReload(f: => Unit): Unit = {
    watch(range, false)((_, _) => f)
    watch(tick, false)((_, _) => f)
  }

  def isAdmin = me.mapC(_.exists(_.isAdmin))
  def admin: Boolean = me.v.exists(_.isAdmin)

  def setRange(t: TimeSel): Unit = {
    range := t
    if (t.preset != "custom") Prefs.set("range", t.preset)
  }

  // ---------------------------------------------------------------- トースト

  def toast(text: String, link: Option[String] = None, error: Boolean = false): Unit = {
    toastSeq += 1
    val t = Toast(toastSeq, text, link, error)
    toasts := (toasts.v :+ t).takeRight(5)
    dom.window.setTimeout(() => dismiss(t.id), if (error) 9000 else 5000)
  }

  def dismiss(id: Int): Unit = toasts := toasts.v.filterNot(_.id == id)

  def error(e: Throwable): Unit = e match {
    case ApiError(401, _) => ()
    case other            => toast(other.getMessage, error = true)
  }

  /** Future の失敗をトーストで知らせる. */
  def report[A](f: Future[A])(ok: A => Unit): Unit = f.onComplete {
    case Success(a) => ok(a)
    case Failure(e) => error(e)
  }

  def go(path: String): Unit = { sideOpen := false; Router.go(path) }

}

/** URL のクエリ. */
object Query {
  def get(k: String): Option[String] = Option(new dom.URL(dom.window.location.href).searchParams.get(k)).filter(_.nonEmpty)
  /** 今の URL のクエリを置き換える（履歴は増やさない）. */
  def replace(params: (String, Option[String])*): Unit =
    dom.window.history.replaceState(null, "", dom.window.location.pathname + Api.query(params*))
}

/** DOM のイベント・クリップボードの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  def copy(text: String, message: String = "コピーしました"): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    Store.toast(message)
  }

}

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

  def bytes(n: Long): String =
    if (n < 1024) s"$n B"
    else if (n < 1024L * 1024) f"${n / 1024.0}%.1f KB"
    else if (n < 1024L * 1024 * 1024) f"${n / 1024.0 / 1024.0}%.1f MB"
    else f"${n / 1024.0 / 1024.0 / 1024.0}%.2f GB"

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
    if (u.startsWith("ERR") || u.startsWith("FATAL") || u.startsWith("CRIT") || u.startsWith("EMERG") || u.startsWith("ALERT")) "error"
    else if (u.startsWith("WARN")) "warn"
    else if (u.startsWith("DEBUG") || u.startsWith("TRACE")) "debug"
    else if (u.isEmpty) "none"
    else "info"
  }

}

/** グラフ（frontend/src/chart.js の uPlot）. */
@js.native
@JSGlobal("skoChart")
private object ChartJs extends js.Object {
  def render(el: dom.Element, spec: js.Object): js.Dynamic = js.native
}

final case class ChartSeries(label: String, points: List[(Double, Option[Double])], color: String = "")

/** グラフの内容. kind は line か bar. points の時刻はミリ秒. onSelect はドラッグで選んだ範囲（ミリ秒）. */
final case class ChartSpec(kind: String, series: List[ChartSeries], unit: String = "", height: Int = 220,
                           onSelect: Option[(Double, Double) => Unit] = None, emptyText: String = "データがありません", legend: Boolean = true)

object ChartFacade {

  def toJs(c: ChartSpec): js.Object = {
    val series = js.Array(c.series.map { s =>
      js.Dynamic.literal(label = s.label, color = (if (s.color.isEmpty) js.undefined else s.color),
        points = js.Array(s.points.map((t, v) => js.Array[js.Any](t, v.fold[js.Any](null)(x => x)))*))
    }*)
    val o = js.Dynamic.literal(kind = c.kind, series = series, unit = c.unit, height = c.height, emptyText = c.emptyText, legend = c.legend)
    c.onSelect.foreach(f => o.onSelect = (a: Double, b: Double) => f(a, b))
    o.asInstanceOf[js.Object]
  }

  def render(el: dom.Element, c: ChartSpec): js.Dynamic = ChartJs.render(el, toJs(c))

  def formatValue(v: Double, unit: String): String =
    dom.window.asInstanceOf[js.Dynamic].skoChartFormat.asInstanceOf[js.UndefOr[js.Function2[Double, String, String]]].toOption
      .map(_(v, unit)).getOrElse(if (v == math.rint(v)) v.toLong.toString else v.toString)

}

/** 画面内のダイアログ（ブラウザ標準の confirm・prompt・alert の代わり）. 表示は parts/DialogHost.st. */
final case class DialogReq(message: String, okLabel: String, danger: Boolean, input: Option[RV[String]], cancel: Boolean,
                           onOk: String => Unit, onCancel: () => Unit)

object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(r: DialogReq): Unit = current := Some(r)

  def confirm(message: String, ok: String = "OK", danger: Boolean = true)(f: => Unit): Unit =
    open(DialogReq(message, ok, danger, None, cancel = true, _ => f, () => ()))

  def prompt(message: String, default: String = "", ok: String = "OK")(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger = false, Some(RV(default)), cancel = true, f, () => ()))

  def alert(message: String): Unit = open(DialogReq(message, "OK", danger = false, None, cancel = false, _ => (), () => ()))

  def close(ok: Boolean): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs {

  def get(key: String, default: String): String =
    scala.util.Try(Option(dom.window.localStorage.getItem("skorimako." + key))).toOption.flatten.getOrElse(default)

  def set(key: String, value: String): Unit = scala.util.Try(dom.window.localStorage.setItem("skorimako." + key, value))

}

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme {

  val current: RV[String] = RV(Prefs.get("theme", "system"))

  def apply(): Unit = {
    val el = dom.document.documentElement
    if (current.v == "system") el.removeAttribute("data-theme") else el.setAttribute("data-theme", current.v)
  }

  def set(v: String): Unit = { current := v; Prefs.set("theme", v); apply() }

  val options: List[(String, String)] = List("system" -> "🖥 自動（OS に合わせる）", "light" -> "☀️ ライト", "dark" -> "🌙 ダーク")

}

/** 検索の結果を行（列の名前 → 値）にする. */
object Rows {
  def of(r: QueryResultDto): List[Map[String, Json]] = r.rows.map(row => r.columns.map(_.name).zip(row).toMap)
  def num(j: Json): Option[Double] = j.asNumber.map(_.toDouble).orElse(j.asString.flatMap(_.toDoubleOption))
  def long(j: Json): Option[Long] = j.asNumber.flatMap(_.toLong).orElse(num(j).map(_.toLong))
}
