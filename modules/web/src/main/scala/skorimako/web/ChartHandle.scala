package skorimako.web

import org.scalajs.dom

import scala.scalajs.js

/** 描いたグラフ. 幅の変化（ResizeObserver）とダークモードの切り替え（色を取り直す）に追従する. */
final class ChartHandle private[web] (el: dom.HTMLElement, initial: ChartSpec) {

  private var plot: js.Dynamic = null
  private var current = initial

  private def draw(): Unit = {
    if (plot != null) { plot.destroy(); plot = null }
    el.innerHTML = ""
    if (current.series.isEmpty || current.series.forall(_.points.isEmpty)) {
      val p = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
      p.className = "chart-empty"
      p.textContent = if (current.emptyText.nonEmpty) current.emptyText else "データがありません"
      p.style.height = s"${ChartFacade.height(current)}px"
      el.appendChild(p)
    } else plot =
      js.Dynamic.newInstance(ChartFacade.uPlot)(ChartFacade.options(el, current), ChartFacade.align(current.series), el)
  }

  draw()

  private val onResize: js.Function0[Unit] = () =>
    if (plot != null)
      plot.setSize(js.Dynamic.literal(width = math.max(200, el.clientWidth), height = ChartFacade.height(current)))
  private val ro = js.Dynamic.newInstance(js.Dynamic.global.ResizeObserver)(onResize)
  ro.observe(el)

  private val mq = dom.window.matchMedia("(prefers-color-scheme: dark)").asInstanceOf[js.Dynamic]
  private val onTheme: js.Function1[js.Any, Unit] = _ => draw()
  mq.addEventListener("change", onTheme)

  def update(s: ChartSpec): Unit = { current = s; draw() }

  def destroy(): Unit = {
    ro.disconnect()
    mq.removeEventListener("change", onTheme)
    if (plot != null) { plot.destroy(); plot = null }
    el.innerHTML = ""
  }

}
