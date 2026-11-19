package skorimako.web

import org.scalajs.dom

import scala.scalajs.js

/**
 * 時系列のグラフ（uPlot）. 画面の部品 parts/Chart.st から使う.
 * uPlot は frontend/main.js が window.uPlot に出す（npm のライブラリなので、読み込みだけ JS）.
 * ドラッグで範囲を選ぶと onSelect を呼ぶ（時間の範囲を狭める）. 幅は親の要素に合わせて変える.
 */
object ChartFacade {

  private val palette = Vector(
    "#2563eb",
    "#16a34a",
    "#d97706",
    "#dc2626",
    "#7c3aed",
    "#0891b2",
    "#db2777",
    "#65a30d",
    "#ea580c",
    "#4f46e5",
    "#0d9488",
    "#b45309",
    "#9333ea",
    "#e11d48",
    "#0284c7"
  )

  private[web] def uPlot: js.Dynamic = dom.window.asInstanceOf[js.Dynamic].uPlot

  private def cssVar(name: String, fallback: String): String = {
    val v = dom.window.getComputedStyle(dom.document.documentElement).getPropertyValue(name).trim
    if (v.nonEmpty) v else fallback
  }

  private def fixed(x: Double, digits: Int): String = x.asInstanceOf[js.Dynamic].toFixed(digits).asInstanceOf[String]

  /** 数値の表示（大きい数は k・M・G、単位が By ならバイト）. */
  def formatValue(v: Double, unit: String): String =
    if (v.isNaN) "-"
    else if (unit == "By" || unit == "bytes") {
      val units = Vector("B", "KB", "MB", "GB", "TB")
      var i = 0
      var x = v
      while (math.abs(x) >= 1024 && i < units.length - 1) { x /= 1024; i += 1 }
      s"${fixed(x, if (x >= 100 || i == 0) 0 else 1)} ${units(i)}"
    } else {
      val a = math.abs(v)
      val suffix = if (unit != null && unit.nonEmpty && unit != "1") s" $unit" else ""
      if (a >= 1e9) fixed(v / 1e9, 1) + "G" + suffix
      else if (a >= 1e6) fixed(v / 1e6, 1) + "M" + suffix
      else if (a >= 1e4) fixed(v / 1e3, 1) + "k" + suffix
      else if (a >= 100 || (!v.isInfinite && v == math.floor(v)))
        (js.Math.round(v): js.Any).asInstanceOf[js.Dynamic].toLocaleString().asInstanceOf[String] + suffix
      else js.Dynamic.global.Number(v.asInstanceOf[js.Dynamic].toPrecision(3)).toString().asInstanceOf[String] + suffix
    }

  /** uPlot から渡る値（null のこともある）の表示. */
  private def formatAny(v: js.Any, unit: String): String =
    if (v == null || js.isUndefined(v)) "-" else formatValue(v.asInstanceOf[Double], unit)

  /** 系列ごとの点を、共通の時刻の並びにそろえる（uPlot のデータの形. 時刻は秒）. */
  private[web] def align(series: List[ChartSeries]): js.Array[js.Array[js.Any]] = {
    val xs = series.flatMap(_.points.map(_._1)).distinct.sorted
    val index = xs.zipWithIndex.toMap
    val ys = series.map { s =>
      val arr = js.Array[js.Any]()
      xs.foreach(_ => arr.push(null))
      s.points.foreach((t, v) => arr(index(t)) = v.fold[js.Any](null)(x => x))
      arr
    }
    js.Array((js.Array[js.Any](xs.map(t => (t / 1000): js.Any)*) :: ys)*)
  }

  private[web] def height(spec: ChartSpec): Int = if (spec.height > 0) spec.height else 220

  private[web] def options(el: dom.HTMLElement, spec: ChartSpec): js.Object = {
    val fg = cssVar("--muted", "#64748b")
    val grid = cssVar("--line", "#e2e8f0")
    val bars = spec.kind == "bar"
    val series = spec.series.zipWithIndex.map {
      (s, i) =>
        val color = if (s.color.nonEmpty) s.color else palette(i % palette.length)
        js.Dynamic.literal(
          label = if (s.label.nonEmpty) s.label else "値",
          stroke = color,
          width = if (bars) 0 else 1.6,
          fill = if (bars) color + "cc" else if (spec.series.length == 1) color + "22" else js.undefined,
          paths = if (bars) uPlot.paths.bars(js.Dynamic.literal(size = js.Array(0.8, 40))) else js.undefined,
          points = js.Dynamic.literal(show = false),
          spanGaps = false,
          value = (
            (_: js.Any, v: js.Any) => formatAny(v, spec.unit)): js.Function2[js.Any, js.Any, String]
        )
    }
    val axisValues: js.Function2[js.Any, js.Array[js.Any], js.Array[String]] =
      (_, vals) => vals.map(v => formatAny(v, spec.unit))
    val onSelect: js.Function1[js.Dynamic, Unit] = u =>
      spec.onSelect.foreach { f =>
        val sel = u.select
        if (sel.width.asInstanceOf[Double] >= 4) {
          val left = sel.left.asInstanceOf[Double]
          val a = u.posToVal(left, "x").asInstanceOf[Double] * 1000
          val b = u.posToVal(left + sel.width.asInstanceOf[Double], "x").asInstanceOf[Double] * 1000
          u.setSelect(js.Dynamic.literal(left = 0, width = 0), false)
          f(a, b)
        }
      }
    js.Dynamic.literal(
      width = math.max(200, el.clientWidth),
      height = height(spec),
      legend = js.Dynamic.literal(show = spec.legend && spec.series.length > 1),
      cursor = js.Dynamic.literal(drag = js.Dynamic.literal(x = spec.onSelect.isDefined, y = false, setScale = false)),
      scales = js.Dynamic.literal(x = js.Dynamic.literal(time = true)),
      axes = js.Array(
        js.Dynamic.literal(
          stroke = fg,
          grid = js.Dynamic.literal(stroke = grid, width = 1),
          ticks = js.Dynamic.literal(stroke = grid)
        ),
        js.Dynamic.literal(
          stroke = fg,
          grid = js.Dynamic.literal(stroke = grid, width = 1),
          ticks = js.Dynamic.literal(stroke = grid),
          size = 60,
          values = axisValues
        )
      ),
      series = js.Array((js.Dynamic.literal() :: series)*),
      hooks = js.Dynamic.literal(setSelect = js.Array(onSelect))
    ).asInstanceOf[js.Object]
  }

  /** el にグラフを描く. 戻り値で描き直し（update）・片付け（destroy）をする. */
  def render(el: dom.HTMLElement, spec: ChartSpec): ChartHandle = new ChartHandle(el, spec)

}
