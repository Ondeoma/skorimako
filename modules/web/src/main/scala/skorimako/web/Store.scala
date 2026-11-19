package skorimako.web

import io.circe.Json
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skorimako.shared.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

final case class Toast(id: Int, text: String, link: Option[String], error: Boolean)

/** 画面全体で共有する状態. */
object Store {

  val me: RV[Option[MeDto]] = RV(None)
  val config: RV[Option[ClientConfigDto]] = RV(None)
  val toasts: RV[List[Toast]] = RV(Nil)

  /** サイドバーを開いているか（スマホの幅）. */
  val sideOpen: RV[Boolean] = RV(false)

  /** 時間の範囲（画面をまたいで同じ. この端末に記憶する）. */
  val range: RV[TimeSel] = RV(TimeSel(Prefs.get("range", "1h")))

  /** 絞り込むサービス（service_name. 空ならすべて）. 画面をまたいで同じ. URL の ?service= があればそれを使う. */
  val service: RV[String] = RV(Query.get("service").getOrElse(Prefs.get("service", "")))

  /** 選べるサービス（データを送ってきたもの）. */
  val serviceNames: RV[List[String]] = RV(Nil)

  def setService(v: String): Unit = {
    service := v
    Prefs.set("service", v)
  }

  /** いまのサービスの SQL の条件（service_name = '…'）. 画面の条件と AND でつなぐ. */
  def serviceWhere(where: String): String = {
    val c = if (service.v.isEmpty) "" else s"service_name = '${service.v.replace("'", "''")}'"
    List(where.trim, c).filter(_.nonEmpty) match {
      case Nil => ""
      case List(x) => x
      case xs => xs.map(x => s"($x)").mkString(" AND ")
    }
  }

  /** 読み直しの合図（↻ のボタン）. */
  val tick: RV[Int] = RV(0)

  private var toastSeq = 0

  def refresh(): Unit = tick := tick.v + 1

  /** 時間の範囲が変わったとき・読み直しのときに f を呼ぶ（画面の mounted で使う）. */
  def onReload(f: => Unit): Unit = {
    watch(range, false)((_, _) => f)
    watch(tick, false)((_, _) => f)
    watch(service, false)((_, _) => f)
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
    case other => toast(other.getMessage, error = true)
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
  def get(k: String): Option[String] =
    Option(new dom.URL(dom.window.location.href).searchParams.get(k)).filter(_.nonEmpty)

  /** 今の URL のクエリを置き換える（履歴は増やさない）. */
  def replace(params: (String, Option[String])*): Unit =
    dom.window.history.replaceState(null, "", dom.window.location.pathname + Api.query(params*))
}

/** 画面内のダイアログ（ブラウザ標準の confirm・prompt・alert の代わり）. 表示は parts/DialogHost.st. */
final case class DialogReq(message: String,
                           okLabel: String,
                           danger: Boolean,
                           input: Option[RV[String]],
                           cancel: Boolean,
                           onOk: String => Unit,
                           onCancel: () => Unit)

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
