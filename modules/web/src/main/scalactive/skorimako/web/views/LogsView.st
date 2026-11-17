<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*
import io.circe.Json

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ログ: ストリーム・時間の範囲・条件（SQL の WHERE）・キーワードで探す. 時間ごとの件数のグラフ（ドラッグで範囲を狭める）、
// 行を開くとすべての列（値で絞り込める）. SQL のタブでは自由に集計できる.
case class State(streams: RV[List[StreamDto]] = RV(Nil),
                 stream: RV[String] = RV(Query.get("stream").getOrElse(Prefs.get("logs.stream", "default"))),
                 fields: RV[List[FieldDto]] = RV(Nil),
                 where: RV[String] = RV(Query.get("where").getOrElse("")),
                 keyword: RV[String] = RV(Query.get("q").getOrElse("")),
                 mode: RV[String] = RV("search"),
                 sql: RV[String] = RV(""),
                 result: RV[Option[LogSearchDto]] = RV(None),
                 rows: RV[List[Map[String, Json]]] = RV(Nil),
                 sqlResult: RV[Option[QueryResultDto]] = RV(None),
                 open: RV[Set[Int]] = RV(Set.empty),
                 cols: RV[List[String]] = RV(Nil),
                 loading: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""),
                 chart: RV[ChartSpec] = RV(ChartSpec("bar", Nil, height = 120, emptyText = "")),
                 fieldQ: RV[String] = RV(""),
                 live: RV[Boolean] = RV(false),
                 timer: RV[Option[Int]] = RV(None),
                 lastTs: RV[Long] = RV(0L))

def initState() = State()

def unmounted(s: State): Unit = stopLive(s)

/** ライブ: 2秒ごとに、前に出した行より新しいものを上に足す（書き出す前の行も出る）. */
def toggleLive(s: State): Unit = if (s.live.v) stopLive(s) else {
  s.live := true
  s.lastTs := s.rows.v.headOption.flatMap(_.get(Columns.Timestamp)).flatMap(Rows.long).getOrElse((scala.scalajs.js.Date.now() * 1000).toLong - 60000000L)
  s.timer := Some(org.scalajs.dom.window.setInterval(() => tail(s), 2000))
}

def stopLive(s: State): Unit = { s.timer.v.foreach(org.scalajs.dom.window.clearInterval); s.timer := None; s.live := false }

def tail(s: State): Unit = {
  val end = (scala.scalajs.js.Date.now() * 1000).toLong
  Api.logs(LogSearchReq(s.stream.v, s.lastTs.v + 1, end, Some(Store.serviceWhere(s.where.v)), Some(s.keyword.v), Some(200), Some(0), Some("desc"))).foreach { r =>
    val rows = Rows.of(r.result)
    if (rows.nonEmpty) {
      rows.headOption.flatMap(_.get(Columns.Timestamp)).flatMap(Rows.long).foreach(s.lastTs := _)
      s.rows := (rows ++ s.rows.v).take(1000)
      s.open := Set.empty
    }
  }
}

def liveCls(s: State) = s.live.mapC(l => if (l) "live on" else "live")

val PageSize = 100

def mounted(s: State): Unit = {
  pageTitle("ログ")
  Query.get("service").foreach(Store.setService)
  Store.report(Api.streams(Some(StreamType.Logs))) { ss =>
    s.streams := ss
    if (ss.nonEmpty && !ss.exists(_.name == s.stream.v)) s.stream := ss.head.name
    if (ss.nonEmpty) { loadFields(s); search(s) }
  }
  Store.onReload(search(s))
}

def loadFields(s: State): Unit = {
  s.cols := Prefs.get("logs.cols." + s.stream.v, "").split(',').toList.filter(_.nonEmpty)
  Api.stream(StreamType.Logs, s.stream.v).foreach(d => s.fields := d.fields)
  if (s.sql.v.isEmpty || s.sql.v.startsWith("SELECT * FROM \""))
    s.sql := s"SELECT * FROM \"${s.stream.v}\"\nORDER BY _timestamp DESC\nLIMIT 100"
}

def changeStream(s: State, v: String): Unit = {
  s.stream := v; Prefs.set("logs.stream", v); s.where := ""; loadFields(s); search(s)
}

def chartOf(s: State, r: LogSearchDto): ChartSpec =
  ChartSpec("bar", List(ChartSeries("件数", r.histogram.map(b => (b.t / 1000.0, Some(b.count.toDouble))))), height = 120, legend = false,
    onSelect = Some((a, b) => Store.setRange(TimeSel.custom(a, b))), emptyText = "該当するログはありません")

def search(s: State, more: Boolean = false): Unit = if (s.mode.v == "sql") runSql(s) else {
  val (start, end) = Store.range.v.micros
  val offset = if (more) s.rows.v.size else 0
  s.loading := true; s.error := ""
  if (!more) Query.replace("stream" -> Some(s.stream.v), "where" -> Some(s.where.v.trim), "q" -> Some(s.keyword.v.trim))
  Api.logs(LogSearchReq(s.stream.v, start, end, Some(Store.serviceWhere(s.where.v)), Some(s.keyword.v), Some(PageSize), Some(offset), None)).onComplete {
    case Success(r) =>
      s.loading := false
      val rows = Rows.of(r.result)
      if (more) s.rows := s.rows.v ++ rows else { s.rows := rows; s.open := Set.empty; s.result := Some(r); s.chart := chartOf(s, r) }
    case Failure(e) => s.loading := false; s.error := e.getMessage
  }
}

def runSql(s: State): Unit = {
  val (start, end) = Store.range.v.micros
  s.loading := true; s.error := ""
  Api.sql(SqlQueryReq(StreamType.Logs, s.sql.v, start, end, Some(1000))).onComplete {
    case Success(r) => s.loading := false; s.sqlResult := Some(r)
    case Failure(e) => s.loading := false; s.error := e.getMessage
  }
}

/** 本文として出す値. */
def message(row: Map[String, Json]): String =
  List("body", "message", "msg", "log").flatMap(row.get).find(!_.isNull).map(Fmt.cell)
    .getOrElse(row.filter((k, v) => !v.isNull && !Set(Columns.Timestamp).contains(k)).map((k, v) => s"$k=${Fmt.cell(v)}").mkString(" "))
    .take(1000)

def level(row: Map[String, Json]): String =
  List("severity_text", "level", "severity", "log_level").flatMap(row.get).find(!_.isNull).map(Fmt.cell).getOrElse("")

def service(row: Map[String, Json]): String = List("service_name", "app", "host_name").flatMap(row.get).find(!_.isNull).map(Fmt.cell).getOrElse("")

def time(row: Map[String, Json]): String = row.get(Columns.Timestamp).flatMap(Rows.long).map(Fmt.micros).getOrElse("")

def toggle(s: State, i: Int): Unit = s.open := (if (s.open.v.contains(i)) s.open.v - i else s.open.v + i)

def isOpen(s: State, i: Int) = s.open.mapC(_.contains(i))

def entries(row: Map[String, Json]): List[(String, Json)] = row.toList.filter(!_._2.isNull).sortBy(_._1)

def literal(j: Json): String = if (j.isNumber || j.isBoolean) j.noSpaces else "'" + Fmt.cell(j).replace("'", "''") + "'"

def ident(k: String): String = if (k.matches("[a-z_][a-z0-9_]*")) k else "\"" + k + "\""

def filter(s: State, k: String, j: Json, op: String): Unit = {
  val c = s"${ident(k)} $op ${literal(j)}"
  s.where := (if (s.where.v.trim.isEmpty) c else s"${s.where.v.trim} AND $c")
  search(s)
}

def toggleCol(s: State, k: String): Unit = {
  s.cols := (if (s.cols.v.contains(k)) s.cols.v.filterNot(_ == k) else s.cols.v :+ k)
  Prefs.set("logs.cols." + s.stream.v, s.cols.v.mkString(","))
}

def colCls(s: State, k: String) = s.cols.mapC(cs => if (cs.contains(k)) "list-item active" else "list-item")

def shownFields(s: State) = computed { val q = s.fieldQ.v.trim.toLowerCase; s.fields.v.filter(f => q.isEmpty || f.name.contains(q)) }

def total(s: State) = s.result.mapC(_.map(r => s"${Fmt.count(r.total)} 件").getOrElse(""))
def hasMore(s: State) = computed { s.result.v.exists(r => s.rows.v.size < r.total) }
def indexed(s: State) = s.rows.mapC(_.zipWithIndex)
def tabCls(s: State, m: String) = s.mode.mapC(x => if (x == m) "active" else "")
def setMode(s: State, m: String): Unit = { s.mode := m; search(s) }
def noStreams(s: State) = s.streams.mapC(_.isEmpty)
def colVal(row: Map[String, Json], c: String): String = row.get(c).map(Fmt.cell).getOrElse("").take(80)
def traceLink(k: String, j: Json): Boolean = k == "trace_id" && Fmt.cell(j).nonEmpty
</script>

<template>
  <div class="page wide">
    <div class="toolbar">
      <h1 class="grow">ログ</h1>
      <select :value="s.stream" @change="changeStream(s, Dom.value(event))">
        <option v-for="st in s.streams" :value="st.name" :selected="s.stream.mapC(_ == st.name)">{{ st.name }}</option>
      </select>
      <ServicePicker />
      <TimeRange />
    </div>
    <p v-if="noStreams(s)" class="empty">ログのストリームがまだありません。OTLP（/v1/logs）か JSON（/api/ingest/{ストリーム}/_json）で送ってください。</p>
    <nav class="tabs">
      <button :class='tabCls(s, "search")' @click='setMode(s, "search")'>検索</button>
      <button :class='tabCls(s, "sql")' @click='setMode(s, "sql")'>SQL</button>
    </nav>

    <div v-if='s.mode.mapC(_ == "search")'>
      <form class="toolbar" @submit.prevent="search(s)">
        <input type="search" class="grow mono" v-model="s.where" placeholder="条件（SQL の WHERE。例: severity_text = 'ERROR' AND service_name = 'api'）">
        <input type="search" v-model="s.keyword" placeholder="本文に含む語">
        <button type="submit" class="primary">検索</button>
        <button type="button" :class="liveCls(s)" @click="toggleLive(s)" title="2秒ごとに新しいログを上に足す">{{ s.live.mapC(l => if (l) "■ ライブを止める" else "▶ ライブ") }}</button>
      </form>
      <p v-if="s.error.mapC(_.nonEmpty)" class="error mono">{{ s.error }}</p>
      <Chart :spec="s.chart" />
      <div class="split">
        <aside>
          <input type="search" v-model="s.fieldQ" placeholder="列を探す" class="fq">
          <p class="muted small">クリックで一覧に列を足す</p>
          <div v-for="f in shownFields(s)" :key="f.name" :class="colCls(s, f.name)" @click="toggleCol(s, f.name)" :title="f.fieldType">{{ f.name }}</div>
        </aside>
        <div>
          <p class="muted small">{{ total(s) }}<span v-if="s.loading">（検索中…）</span><span v-if="s.live" class="ok">・ライブで表示中（最新1000行）</span></p>
          <div class="table-wrap">
            <table class="list logs">
              <thead><tr><th>日時</th><th>重大度</th><th>サービス</th><th v-for="c in s.cols">{{ c }}</th><th>本文</th></tr></thead>
              <tbody>
                <template v-for="ri in indexed(s)">
                  <tr class="clickable" @click="toggle(s, ri._2)">
                    <td class="nowrap mono">{{ time(ri._1) }}</td>
                    <td><span :class='"lv " + Fmt.level(level(ri._1))'>{{ level(ri._1) }}</span></td>
                    <td class="small">{{ service(ri._1) }}</td>
                    <td v-for="c in s.cols" class="small">{{ colVal(ri._1, c) }}</td>
                    <td class="msg mono">{{ message(ri._1) }}</td>
                  </tr>
                  <tr v-if="isOpen(s, ri._2)" class="detail">
                    <td :colspan="4 + s.cols.v.size">
                      <div class="kvg">
                        <template v-for="kv in entries(ri._1)">
                          <div class="k">{{ kv._1 }}</div>
                          <div class="v mono">{{ Fmt.cell(kv._2) }}
                            <a v-if="traceLink(kv._1, kv._2)" :href='"/traces/" + Fmt.cell(kv._2)' @click.prevent='Store.go("/traces/" + Fmt.cell(kv._2))'>トレースを見る →</a>
                          </div>
                          <div class="acts">
                            <button class="small ghost" title="この値で絞り込む" @click='filter(s, kv._1, kv._2, "=")'>＝</button>
                            <button class="small ghost" title="この値を除く" @click='filter(s, kv._1, kv._2, "!=")'>≠</button>
                            <button class="small ghost" title="コピー" @click="Dom.copy(Fmt.cell(kv._2))">⧉</button>
                          </div>
                        </template>
                      </div>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
          <p><button v-if="hasMore(s)" class="small" @click="search(s, true)">さらに表示</button></p>
        </div>
      </div>
    </div>

    <div v-if='s.mode.mapC(_ == "sql")'>
      <div class="hint">DuckDB の SQL です。FROM にストリームの名前を書きます（例: <code>SELECT service_name, count(*) FROM "default" GROUP BY 1</code>）。
        <code>_timestamp</code> はマイクロ秒で、<code>to_timestamp(_timestamp / 1e6)</code> で日時になります。対象は選んだ時間の範囲だけです。</div>
      <textarea class="code" v-model="s.sql"></textarea>
      <p class="row"><button class="primary" @click="runSql(s)">実行</button><span v-if="s.loading" class="muted small">実行中…</span>
        <span class="muted small">{{ s.sqlResult.mapC(_.map(r => s"${r.rows.size} 行・${r.tookMs}ms・${r.scannedFiles} ファイル（${Fmt.bytes(r.scannedBytes)}）").getOrElse("")) }}</span></p>
      <p v-if="s.error.mapC(_.nonEmpty)" class="error mono">{{ s.error }}</p>
      <ResultTable v-for="r in s.sqlResult.mapC(_.toList)" :result="r" />
    </div>
  </div>
</template>

<style scoped>
.fq { width: 100%; margin-bottom: .3rem; }
.live.on { background: var(--ok); border-color: var(--ok); color: #fff; }
.logs td { vertical-align: top; }
.msg { white-space: pre-wrap; word-break: break-all; min-width: 360px; font-size: .8rem; }
tr.detail > td { background: var(--side); }
.kvg { display: grid; grid-template-columns: minmax(120px, 220px) minmax(0, 1fr) auto; gap: 0 .6rem; align-items: start; }
.kvg .k { font-size: .78rem; color: var(--muted); word-break: break-all; padding: .15rem 0; }
.kvg .v { font-size: .8rem; word-break: break-all; white-space: pre-wrap; padding: .15rem 0; }
.kvg .acts { white-space: nowrap; }
.kvg .acts button { padding: 0 .3rem; }
</style>
