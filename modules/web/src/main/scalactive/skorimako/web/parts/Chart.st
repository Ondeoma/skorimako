<script lang="scala">
import skorimako.web.*
import org.scalajs.dom

import scala.scalajs.js

// 時系列のグラフ（uPlot）. spec を変えると描き直す.
case class Props(spec: RV[ChartSpec])

case class State(el: ElementRef[dom.HTMLElement] = ElementRef(), handle: RV[Option[js.Dynamic]] = RV(None))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  s.el.foreach(el => s.handle := Some(ChartFacade.render(el, props.spec.v)))
  watch(props.spec, false)((_, n) => s.handle.v.foreach(_.update(ChartFacade.toJs(n))))
}

def unmounted(s: State): Unit = {
  s.handle.v.foreach(_.destroy())
  s.handle := None
}
</script>

<template>
  <div class="chart" ref="s.el"></div>
</template>

<style scoped>
.chart { width: 100%; min-width: 0; }
</style>
