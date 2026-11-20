<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// メトリクス: 左で選び、集計（平均・合計・rate・パーセンタイル）・ラベルでの分け方・条件・区切りを決めてグラフにする.
case class State(res: AsyncState[(MetricResultDto, ChartSpec)],
                 metrics: RV[List[StreamDto]] = RV(Nil),
                 q: RV[String] = RV(""),
                 metric: RV[String] = RV(Router.query.v.get("metric").getOrElse("")),
                 info: RV[Option[StreamDto]] = RV(None),
                 labels: RV[List[String]] = RV(Nil),
                 agg: RV[String] = RV("avg"),
                 groupBy: RV[List[String]] = RV(Nil),
                 where: RV[String] = RV(""),
                 step: RV[String] = RV(""),
                 chart: RV[ChartSpec] = RV(ChartSpec("line", Nil)),
                 showSql: RV[Boolean] = RV(false),
                 mode: RV[String] = RV(if (Router.query.v.get("promql").isDefined) "promql" else "builder"),
                 promql: RV[String] = RV(Router.query.v.get("promql").getOrElse("")),
                 promChart: RV[ChartSpec] = RV(ChartSpec("line", Nil, height = 320)),
                 promError: RV[String] = RV(""),
                 services: RV[Map[String, List[String]]] = RV(Map.empty))

// 結果（とグラフ）は run で読み直す（古い応答は捨てる）
def initState() = {
  lazy val s: State = State(useAsync(fetch(s), immediate = false))
  s
}

val notLabels = Set(Columns.Timestamp, "value", "__name__", "__type__")

def mounted(s: State): Unit = {
  pageTitle("メトリクス")
  watch(s.res.data, false)((_, d) => d.foreach(x => s.chart := x._2))
  Router.query.v.get("service").foreach(Store.setService)
  Api.metricServices.foreach(s.services := _)
  Toasts.report(Api.streams(Some(StreamType.Metrics))) { ms =>
    s.metrics := ms
    if (s.metric.v.isEmpty) ms.headOption.foreach(m => s.metric := m.name)
    if (s.metric.v.nonEmpty) select(s, s.metric.v)
  }
  Store.onReload(if (s.mode.v == "promql") runProm(s) else run(s))
  if (s.mode.v == "promql" && s.promql.v.nonEmpty) runProm(s)
}

/** PromQL（/api/prom の query_range）. */
def runProm(s: State): Unit = if (s.promql.v.trim.nonEmpty) {
  val (start, end) = Store.range.v.micros
  val (a, b) = (start / 1000000, end / 1000000)
  val step = math.max(15L, (b - a) / 120)
  Query.replace("promql" -> Some(s.promql.v.trim))
  s.promError := ""
  Api.get[io.circe.Json]("/api/prom/api/v1/query_range" + Api.query("query" -> Some(s.promql.v.trim), "start" -> Some(a.toString),
    "end" -> Some(b.toString), "step" -> Some(step.toString))).onComplete {
    case Success(j) =>
      val c = j.hcursor
      if (c.get[String]("status").toOption.contains("error")) s.promError := c.get[String]("error").getOrElse("")
      else {
        val rs = c.downField("data").downField("result").as[List[io.circe.Json]].getOrElse(Nil)
        s.promChart := ChartSpec("line", rs.take(50).map { r =>
          val m = r.hcursor.downField("metric").as[Map[String, String]].getOrElse(Map.empty)
          val vs = r.hcursor.downField("values").as[List[(Double, String)]].getOrElse(Nil)
          ChartSeries(if (m.isEmpty) "値" else m.filter(_._1 != "__name__").toList.sorted.map((k, v) => s"$k=$v").mkString(", "),
            vs.map((t, v) => (t * 1000, v.toDoubleOption)))
        }, height = 320, onSelect = Some((x, y) => Store.setRange(TimeSel.custom(x, y))))
      }
    case Failure(e) => s.promError := e.getMessage
  }
}

def setMode(s: State, m: String): Unit = { s.mode := m; if (m == "promql") runProm(s) else run(s) }
def modeCls(s: State, m: String) = s.mode.mapC(x => if (x == m) "active" else "")
def traceOf(x: SeriesDto): Option[String] = x.labels.get("service_name").map(v => "/traces?service=" + Api.enc(v))

/** メトリクスの種類から既定の集計. */
def defaultAgg(m: StreamDto): String = m.metricType match {
  case "counter" => "rate"
  case "delta_counter" => "sum"
  case _ if m.name.endsWith("_count") || m.name.endsWith("_sum") || m.name.endsWith("_bucket") => "rate"
  case _ => "avg"
}

def select(s: State, name: String): Unit = {
  s.metric := name
  if (s.mode.v == "builder") Query.replace("metric" -> Some(name))
  val m = s.metrics.v.find(_.name == name)
  s.info := m
  m.foreach(x => s.agg := defaultAgg(x))
  s.groupBy := (if (name.endsWith("_bucket")) List("le") else Nil)
  s.where := ""
  Api.stream(StreamType.Metrics, name).foreach(d => s.labels := d.fields.map(_.name).filterNot(notLabels).sorted)
  run(s)
}

def run(s: State): Unit = if (s.metric.v.nonEmpty) s.res.reload()

def fetch(s: State): Future[(MetricResultDto, ChartSpec)] = {
  val (start, end) = Store.range.v.micros
  val unit = s.info.v.map(_.unit).getOrElse("")
  val agg = s.agg.v
  Api.metrics(MetricQueryReq(s.metric.v, start, end, agg, s.groupBy.v, Some(Store.serviceWhere(s.where.v)), s.step.v.toIntOption)).map { r =>
    r -> ChartSpec("line", r.series.map(x => ChartSeries(label(x), x.points.map(p => (p.t / 1000.0, p.v)))), unit = if (agg == "rate") (if (unit.isEmpty || unit == "1") "/s" else unit + "/s") else unit,
      height = 300, onSelect = Some((a, b) => Store.setRange(TimeSel.custom(a, b))))
  }
}

def label(x: SeriesDto): String = if (x.labels.isEmpty) "全体" else x.labels.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")

def toggleGroup(s: State, l: String): Unit = { s.groupBy := (if (s.groupBy.v.contains(l)) s.groupBy.v.filterNot(_ == l) else s.groupBy.v :+ l); run(s) }

def filtered(s: State) = computed {
  val q = s.q.v.trim.toLowerCase
  val svc = Store.service.v
  s.metrics.v.filter(m => (q.isEmpty || m.name.contains(q)) && (svc.isEmpty || s.services.v.getOrElse(m.name, Nil).contains(svc))).take(500)
}

def servicesOf(s: State, name: String) = s.services.mapC(_.getOrElse(name, Nil).mkString(" · "))
def itemCls(s: State, n: String) = s.metric.mapC(m => if (m == n) "list-item active" else "list-item")
def groupCls(s: State, l: String) = s.groupBy.mapC(g => if (g.contains(l)) "chip active" else "chip")
def series(s: State) = s.res.data.mapC(_.map(_._1.series).getOrElse(Nil))
def last(x: SeriesDto): String = x.points.reverse.find(_.v.isDefined).flatMap(_.v).map(v => Fmt.num(v)).getOrElse("-")
def maxOf(x: SeriesDto): String = x.points.flatMap(_.v).maxOption.map(v => Fmt.num(v)).getOrElse("-")
def avgOf(x: SeriesDto): String = { val vs = x.points.flatMap(_.v); if (vs.isEmpty) "-" else Fmt.num(vs.sum / vs.size) }
def alertLink(s: State): String =
  "/alerts/new" + Api.query("type" -> Some(StreamType.Metrics), "stream" -> Some(s.metric.v), "agg" -> Some(if (s.agg.v == "rate") "avg" else s.agg.v),
    "field" -> Some("value"), "where" -> Some(s.where.v))
def typeLabel(t: String): String = t match {
  case "counter" => "カウンター（累積）"
  case "delta_counter" => "カウンター（差分）"
  case "updowncounter" => "増減するカウンター"
  case "gauge" => "ゲージ"
  case "histogram" => "ヒストグラム"
  case "summary" => "サマリー"
  case x => x
}
</script>

<template>
  <div class="page wide">
    <div class="toolbar"><h1 class="grow">メトリクス</h1><ServicePicker /><TimeRange /></div>
    <nav class="tabs">
      <button :class='modeCls(s, "builder")' @click='setMode(s, "builder")'>選んで表示</button>
      <button :class='modeCls(s, "promql")' @click='setMode(s, "promql")'>PromQL</button>
    </nav>
    <div v-if='s.mode.mapC(_ == "promql")'>
      <form class="toolbar" @submit.prevent="runProm(s)">
        <input type="search" class="grow mono" v-model="s.promql" placeholder="sum by (service_name) (rate(http_server_requests[5m]))">
        <button type="submit" class="primary">実行</button>
      </form>
      <p class="hint">Prometheus と同じ書き方です（rate・increase・histogram_quantile・sum by・topk など）。メトリクスの名前はストリームの名前（. は _）。
        Grafana からは <code>{{ Store.config.mapC(_.map(_.publicUrl).getOrElse("")) }}/api/prom</code> を Prometheus のデータソースとしてつなげます。</p>
      <p v-if="s.promError.mapC(_.nonEmpty)" class="error mono">{{ s.promError }}</p>
      <Chart :spec="s.promChart" />
    </div>
    <div v-if='s.mode.mapC(_ == "builder")'>
    <p v-if="s.metrics.mapC(_.isEmpty)" class="empty">メトリクスがまだありません。OTLP（/v1/metrics）で送ってください。</p>
    <div class="split">
      <aside>
        <input type="search" v-model="s.q" placeholder="メトリクスを探す" class="fq">
        <div v-for="m in filtered(s)" :key="m.id" :class="itemCls(s, m.name)" @click="select(s, m.name)" :title="m.description" style="flex-direction: column; align-items: stretch; gap: 0">
          <div class="nm">{{ m.name }}</div><div class="svc">{{ servicesOf(s, m.name) }}</div>
        </div>
      </aside>
      <div v-if="s.metric.mapC(_.nonEmpty)">
        <h2 class="mono">{{ s.metric }}</h2>
        <p class="muted small" v-for="i in s.info.mapC(_.toList)">{{ typeLabel(i.metricType) }}<span v-if="i.unit.nonEmpty">・単位 {{ i.unit }}</span>
          <span v-if="i.description.nonEmpty">・{{ i.description }}</span></p>
        <form class="toolbar" @submit.prevent="run(s)">
          <label>集計 <select v-model="s.agg" @change="run(s)"><option v-for="a in MetricAgg.all" :value="a">{{ MetricAgg.label(a) }}</option></select></label>
          <label>区切り <select v-model="s.step" @change="run(s)"><option value="">自動</option><option value="10">10秒</option><option value="60">1分</option>
            <option value="300">5分</option><option value="3600">1時間</option><option value="86400">1日</option></select></label>
          <input type="search" class="grow mono" v-model="s.where" placeholder="条件（例: service_name = 'api'）">
          <button type="submit" class="primary">表示</button>
        </form>
        <div v-if="s.labels.mapC(_.nonEmpty)" class="chips groups"><span class="muted small">ラベルで分ける:</span>
          <button v-for="l in s.labels" :class="groupCls(s, l)" @click="toggleGroup(s, l)">{{ l }}</button></div>
        <p v-if="s.res.failed" class="error mono">{{ s.res.errorMessage }}</p>
        <Chart :spec="s.chart" />
        <p class="muted small" v-if="s.res.loading">読み込み中…</p>
        <div class="table-wrap series">
          <table class="list">
            <thead><tr><th>系列</th><th>最後</th><th>平均</th><th>最大</th><th></th></tr></thead>
            <tbody><tr v-for="x in series(s)"><td class="mono small">{{ label(x) }}</td><td>{{ last(x) }}</td><td>{{ avgOf(x) }}</td><td>{{ maxOf(x) }}</td>
              <td><a v-for="l in traceOf(x).toList" :href="l" @click.prevent="Store.go(l)" class="small">🧭 トレース</a></td></tr></tbody>
          </table>
        </div>
        <p class="row">
          <a class="btn small" :href="alertLink(s)" @click.prevent="Store.go(alertLink(s))">🔔 このメトリクスでアラートを作る</a>
          <button class="small ghost" @click="s.showSql := !s.showSql.v">SQL を見る</button>
        </p>
        <pre v-if="s.showSql" class="code">{{ s.res.data.mapC(_.map(_._1.sql).getOrElse("")) }}</pre>
      </div>
    </div>
    </div>
  </div>
</template>

<style scoped>
.fq { width: 100%; margin-bottom: .3rem; }
.nm { overflow: hidden; text-overflow: ellipsis; }
.svc { font-size: .72rem; color: var(--muted); font-weight: normal; line-height: 1.2; }
h2 { margin-top: 0; word-break: break-all; }
.groups { margin-bottom: .6rem; align-items: center; }
.series { margin-top: .8rem; }
</style>
