<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// サービス（トレースから）: サービスごとの要求・エラー率・所要時間（RED）、サービス間の呼び出しの図、選んだサービスの時系列.
case class State(map: RV[Option[ServiceMapDto]] = RV(None),
                 selected: RV[String] = RV(Query.get("service").getOrElse("")),
                 req: RV[ChartSpec] = RV(ChartSpec("bar", Nil, height = 160)),
                 lat: RV[ChartSpec] = RV(ChartSpec("line", Nil, height = 160)),
                 error: RV[String] = RV(""))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("サービス"); load(s); Store.onReload(load(s)) }

def load(s: State): Unit = {
  val (start, end) = Store.range.v.micros
  Api.get[ServiceMapDto](s"/api/services/map?start=$start&end=$end").onComplete {
    case Success(m) =>
      s.map := Some(m); s.error := ""
      if (s.selected.v.isEmpty) m.services.headOption.foreach(x => s.selected := x.name)
      series(s)
    case Failure(e) => s.error := e.getMessage
  }
}

def pick(s: State, name: String): Unit = { s.selected := name; Query.replace("service" -> Some(name)); series(s) }

def series(s: State): Unit = if (s.selected.v.nonEmpty) {
  val (start, end) = Store.range.v.micros
  Api.get[ServiceSeriesDto](s"/api/services/series?service=${Api.enc(s.selected.v)}&start=$start&end=$end").foreach { d =>
    val sel = Some((a: Double, b: Double) => Store.setRange(TimeSel.custom(a, b)))
    s.req := ChartSpec("bar", List(ChartSeries("要求", d.points.map(p => (p.t / 1000.0, Some((p.requests - p.errors).toDouble))), "#2563eb"),
      ChartSeries("エラー", d.points.map(p => (p.t / 1000.0, Some(p.errors.toDouble))), "#dc2626")), height = 160, onSelect = sel)
    s.lat := ChartSpec("line", List(ChartSeries("p95", d.points.map(p => (p.t / 1000.0, p.p95Ms)), "#d97706")), unit = "ms", height = 160, onSelect = sel)
  }
}

def services(s: State) = s.map.mapC(_.map(_.services).getOrElse(Nil))
def edges(s: State) = s.map.mapC(_.map(_.edges).getOrElse(Nil))
def errRate(x: ServiceStatDto): String = if (x.requests == 0) "-" else f"${x.errors * 100.0 / x.requests}%.1f%%"
def rowCls(s: State, n: String) = s.selected.mapC(x => if (x == n) "clickable sel" else "clickable")

/** 図の配置: サービスを円の上に並べる. */
case class Node(name: String, x: Double, y: Double, err: Boolean)
def nodes(s: State) = s.map.mapC { m =>
  val ns = m.map(_.services).getOrElse(Nil).take(30)
  val n = math.max(1, ns.size)
  ns.zipWithIndex.map { (x, i) =>
    val a = 2 * math.Pi * i / n - math.Pi / 2
    val r = if (n == 1) 0.0 else 130.0
    Node(x.name, 320 + r * math.cos(a), 170 + r * math.sin(a), x.requests > 0 && x.errors * 20 > x.requests)
  }
}
case class Line(x1: Double, y1: Double, x2: Double, y2: Double, label: String, err: Boolean)
def lines(s: State) = computed {
  val ns = nodes(s).v.map(n => n.name -> n).toMap
  edges(s).v.flatMap { e =>
    (ns.get(e.from), ns.get(e.to)) match {
      case (Some(a), Some(b)) =>
        // 円の縁で止める
        val dx = b.x - a.x; val dy = b.y - a.y; val d = math.max(1.0, math.hypot(dx, dy))
        Some(Line(a.x + dx / d * 26, a.y + dy / d * 26, b.x - dx / d * 30, b.y - dy / d * 30, s"${e.calls}", e.errors > 0))
      case _ => None
    }
  }
}
def nodeCls(s: State, n: Node) = s.selected.mapC(x => (if (x == n.name) "node sel" else "node") + (if (n.err) " err" else ""))
private def esc(x: String): String = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** 呼び出しの図（SVG の文字列）. */
def svg(s: State) = computed {
  val sel = s.selected.v
  val ls = lines(s).v.map(l =>
    s"""<line x1="${l.x1}" y1="${l.y1}" x2="${l.x2}" y2="${l.y2}" class="edge${if (l.err) " err" else ""}" marker-end="url(#arr)"/>""" +
      s"""<text x="${(l.x1 + l.x2) / 2}" y="${(l.y1 + l.y2) / 2 - 4}" class="elabel">${esc(l.label)}</text>""").mkString
  val ns = nodes(s).v.map(n =>
    s"""<g class="node${if (n.name == sel) " sel" else ""}${if (n.err) " err" else ""}" data-name="${esc(n.name)}">""" +
      s"""<circle cx="${n.x}" cy="${n.y}" r="24"/><text x="${n.x}" y="${n.y + 40}" class="nlabel">${esc(n.name)}</text></g>""").mkString
  s"""<svg viewBox="0 0 640 340" xmlns="http://www.w3.org/2000/svg"><defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7"
     |markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor"/></marker></defs>$ls$ns</svg>""".stripMargin
}

def clickMap(s: State, e: org.scalajs.dom.Event): Unit =
  Option(e.target.asInstanceOf[org.scalajs.dom.Element].closest("[data-name]")).foreach(el => pick(s, el.getAttribute("data-name")))

def tracesLink(s: State) = s.selected.mapC(x => "/traces?service=" + Api.enc(x))
def logsLink(s: State) = s.selected.mapC(x => "/logs?where=" + Api.enc(s"service_name = '$x'"))
</script>

<template>
  <div class="page wide">
    <div class="toolbar"><h1 class="grow">サービス</h1><TimeRange /></div>
    <p class="muted small">トレースから求めます（サービスの入口のスパン: SERVER・CONSUMER・ルート）。円はサービス、矢印は呼び出し（数字は回数。赤はエラーを含む）。</p>
    <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
    <p v-if="services(s).mapC(_.isEmpty)" class="empty">この時間の範囲にトレースがありません</p>
    <div class="grid2">
      <div class="card mapc">
        <div class="svcmap" v-html="svg(s)" @click="clickMap(s, event)"></div>
      </div>
      <div>
        <h3>{{ s.selected }}</h3>
        <p class="row small"><a :href="tracesLink(s)" @click.prevent="Store.go(tracesLink(s).v)">🧭 トレース</a>
          <a :href="logsLink(s)" @click.prevent="Store.go(logsLink(s).v)">📜 ログ</a></p>
        <p class="muted small">要求とエラー</p>
        <Chart :spec="s.req" />
        <p class="muted small">所要時間（p95）</p>
        <Chart :spec="s.lat" />
      </div>
    </div>
    <h2>サービス</h2>
    <div class="table-wrap">
      <table class="list">
        <thead><tr><th>サービス</th><th>要求</th><th>毎秒</th><th>エラー率</th><th>p50</th><th>p95</th><th>p99</th></tr></thead>
        <tbody>
          <tr v-for="x in services(s)" :key="x.name" :class="rowCls(s, x.name)" @click="pick(s, x.name)">
            <td><b>{{ x.name }}</b></td><td>{{ Fmt.count(x.requests) }}</td><td>{{ Fmt.num(x.rps) }}</td>
            <td :class='if (x.errors > 0) "error" else ""'>{{ errRate(x) }}</td>
            <td>{{ Fmt.num(x.p50Ms, "ms") }}</td><td>{{ Fmt.num(x.p95Ms, "ms") }}</td><td>{{ Fmt.num(x.p99Ms, "ms") }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <h2>呼び出し</h2>
    <div class="table-wrap">
      <table class="list">
        <thead><tr><th>呼び出し元</th><th>呼び出し先</th><th>回数</th><th>エラー</th><th>p95</th></tr></thead>
        <tbody><tr v-for="e in edges(s)"><td>{{ e.from }}</td><td>{{ e.to }}</td><td>{{ Fmt.count(e.calls) }}</td>
          <td :class='if (e.errors > 0) "error" else ""'>{{ e.errors }}</td><td>{{ Fmt.num(e.p95Ms, "ms") }}</td></tr></tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.grid2 { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 1rem; }
@media (max-width: 900px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
.mapc { padding: .4rem; }
tr.sel td { background: var(--accent-weak); }
</style>
