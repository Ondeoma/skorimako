package skorimako.web

import io.circe.Json
import io.github.ondeoma.scalactive.components.ComponentManager.*
import skorimako.shared.*

/** 画面全体で共有する状態. */
object Store {

  val me: RV[Option[MeDto]] = RV(None)
  val config: RV[Option[ClientConfigDto]] = RV(None)

  /** サイドバーを開いているか（スマホの幅）. */
  val sideOpen: RV[Boolean] = RV(false)

  /** 時間の範囲（画面をまたいで同じ. この端末に記憶する）. */
  val range: RV[TimeSel] = RV(TimeSel(Prefs.get("range", "1h")))

  /** 絞り込むサービス（service_name. 空ならすべて）. 画面をまたいで同じ. URL の ?service= があればそれを使う. */
  val service: RV[String] = RV(Router.query.v.get("service").getOrElse(Prefs.get("service", "")))

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

  def go(path: String): Unit = { sideOpen := false; Router.go(path) }

}

/** URL のクエリ. */
object Query {

  /** 今の URL のクエリを params だけにする（履歴は増やさない. 空の値は付けない）. 画面は作り直さない（App の reloadOnQueryChange）. */
  def replace(params: (String, Option[String])*): Unit = {
    val drop = (Router.query.v.keySet -- params.map(_._1)).toSeq.map(_ -> None)
    Router.setQuery((drop ++ params.map((k, v) => k -> v.filter(_.nonEmpty)))*)
  }
}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs extends sbirds.web.LocalPrefs("skorimako")

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme extends sbirds.web.ThemeSwitch(Prefs)

/** 検索の結果を行（列の名前 → 値）にする. */
object Rows {
  def of(r: QueryResultDto): List[Map[String, Json]] = r.rows.map(row => r.columns.map(_.name).zip(row).toMap)
  def num(j: Json): Option[Double] = j.asNumber.map(_.toDouble).orElse(j.asString.flatMap(_.toDoubleOption))
  def long(j: Json): Option[Long] = j.asNumber.flatMap(_.toLong).orElse(num(j).map(_.toLong))
}
