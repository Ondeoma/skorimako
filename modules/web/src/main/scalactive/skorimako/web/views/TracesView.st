<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// トレース: サービス・名前・時間・エラーで探す. 所要時間の散らばりのグラフと一覧. クリックでトレースの詳細（ウォーターフォール）.
case class State(services: RV[List[ServiceDto]] = RV(Nil),
                 service: RV[String] = RV(""),
                 name: RV[String] = RV(""),
                 minMs: RV[String] = RV(""),
                 errors: RV[Boolean] = RV(false),
                 traceId: RV[String] = RV(""),
                 list: RV[List[TraceSummaryDto]] = RV(Nil),
                 chart: RV[ChartSpec] = RV(ChartSpec("line", Nil, height = 160)),
                 loading: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("トレース"); Query.get("service").foreach(Store.setService); search(s); Store.onReload(search(s)) }

def search(s: State): Unit = {
  val (start, end) = Store.range.v.micros
  s.loading := true
  s.service := Store.service.v
  Api.get[List[ServiceDto]]("/api/traces/services" + Api.query("start" -> Some(start.toString), "end" -> Some(end.toString))).foreach(s.services := _)
  Api.traces(TraceSearchReq(start, end, Some(s.service.v), Some(s.name.v), s.minMs.v.toDoubleOption, Some(s.errors.v), None, Some(300))).onComplete {
    case Success(ts) =>
      s.loading := false; s.list := ts
      val (err, ok) = ts.partition(_.errorCount > 0)
      def pts(xs: List[TraceSummaryDto]) = xs.map(t => (t.start / 1000.0, Some(t.durationMicros / 1000.0))).sortBy(_._1)
      s.chart := ChartSpec("bar", List(ChartSeries("正常", pts(ok), "#2563eb"), ChartSeries("エラー", pts(err), "#dc2626")), unit = "ms", height = 160,
        onSelect = Some((a, b) => Store.setRange(TimeSel.custom(a, b))), emptyText = "トレースはありません")
    case Failure(e) => s.loading := false; Store.error(e)
  }
}

def openId(s: State): Unit = { val id = s.traceId.v.trim.toLowerCase; if (id.nonEmpty) Store.go("/traces/" + id) }
def svcCls(s: State, n: String) = Store.service.mapC(x => if (x == n) "chip active" else "chip")
def pickSvc(s: State, n: String): Unit = Store.setService(if (Store.service.v == n) "" else n)
</script>

<template>
  <div class="page wide">
    <div class="toolbar"><h1 class="grow">トレース</h1>
      <form class="row" @submit.prevent="openId(s)"><input type="search" class="mono" v-model="s.traceId" placeholder="トレース ID で開く"></form>
      <ServicePicker />
      <TimeRange /></div>
    <div class="chips svcs">
      <button v-for="x in s.services" :class="svcCls(s, x.name)" @click="pickSvc(s, x.name)">{{ x.name }} <span class="muted">{{ x.traceCount }}</span>
        <span v-if="x.errorCount > 0" class="error">⚠ {{ x.errorCount }}</span></button>
    </div>
    <form class="toolbar" @submit.prevent="search(s)">
      <input type="search" v-model="s.name" placeholder="ルートのスパンの名前（例: GET /orders）" class="grow">
      <input type="number" v-model="s.minMs" placeholder="最小の所要時間(ms)" min="0">
      <label class="check"><input type="checkbox" v-model="s.errors"> エラーだけ</label>
      <button class="primary" type="submit">検索</button>
    </form>
    <Chart :spec="s.chart" />
    <p class="muted small">{{ s.list.mapC(l => s"${l.size} 件（新しい順。最大300件）") }}<span v-if="s.loading">・検索中…</span></p>
    <div class="table-wrap">
      <table class="list">
        <thead><tr><th>開始</th><th>サービス</th><th>名前</th><th>所要時間</th><th>スパン</th><th>エラー</th><th>関わったサービス</th></tr></thead>
        <tbody>
          <tr v-for="t in s.list" :key="t.traceId" class="clickable" @click='Store.go("/traces/" + t.traceId)'>
            <td class="nowrap mono small">{{ Fmt.micros(t.start) }}</td>
            <td>{{ t.service }}</td>
            <td class="mono small">{{ if (t.rootName.isEmpty) "（ルートが未着）" else t.rootName }}</td>
            <td class="nowrap">{{ Fmt.duration(t.durationMicros) }}</td>
            <td>{{ t.spanCount }}</td>
            <td><span v-if="t.errorCount > 0" class="error">⚠ {{ t.errorCount }}</span></td>
            <td class="small muted">{{ t.services.mkString(", ") }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.svcs { margin-bottom: .6rem; }
</style>
