<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*
import io.circe.Json
import io.circe.syntax.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// フロントエンドの観測（RUM）: 表示の数・セッション・エラー・Web Vitals（p75）、ページごと・エラーごと・ブラウザごと.
// アプリの登録（管理者）と、ページに貼る script.
case class State(apps: RV[List[RumAppDto]] = RV(Nil),
                 app: RV[String] = RV(Prefs.get("rum.app", "")),
                 kpi: RV[Map[String, Json]] = RV(Map.empty),
                 chart: RV[ChartSpec] = RV(ChartSpec("bar", Nil, height = 180)),
                 pages: RV[Option[QueryResultDto]] = RV(None),
                 errors: RV[Option[QueryResultDto]] = RV(None),
                 browsers: RV[Option[QueryResultDto]] = RV(None),
                 failures: RV[Option[QueryResultDto]] = RV(None),
                 error: RV[String] = RV(""),
                 tab: RV[String] = RV("overview"),
                 newName: RV[String] = RV(""),
                 newOrigins: RV[String] = RV(""))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("フロントエンド")
  loadApps(s)
  Store.onReload(load(s))
}

def loadApps(s: State): Unit = Store.report(Api.get[List[RumAppDto]]("/api/rum/apps")) { as =>
  s.apps := as
  if (!as.exists(_.id.toString == s.app.v)) s.app := as.headOption.map(_.id.toString).getOrElse("")
  if (as.isEmpty) s.tab := "apps" else load(s)
}

def current(s: State): Option[RumAppDto] = s.apps.v.find(_.id.toString == s.app.v)

def pick(s: State, v: String): Unit = { s.app := v; Prefs.set("rum.app", v); load(s) }

def lit(v: String): String = "'" + v.replace("'", "''") + "'"

def q(s: State, sql: String): Future[QueryResultDto] = {
  val (start, end) = Store.range.v.micros
  Api.sql(SqlQueryReq(StreamType.Rum, sql, start, end, Some(200)))
}

def load(s: State): Unit = current(s).foreach { a =>
  val from = "\"" + a.stream + "\""
  val w = s"app = ${lit(a.name)}"
  val (start, end) = Store.range.v.micros
  val step = math.max(60000000L, (end - start) / 60)
  s.error := ""
  q(s, s"""SELECT count(*) FILTER (WHERE event_type IN ('view', 'route')) AS views, count(DISTINCT session_id) AS sessions,
           |count(*) FILTER (WHERE event_type = 'error') AS errors, count(*) FILTER (WHERE event_type = 'resource_error') AS failures,
           |quantile_cont(lcp, 0.75) FILTER (WHERE event_type = 'view') AS lcp, quantile_cont(fcp, 0.75) FILTER (WHERE event_type = 'view') AS fcp,
           |quantile_cont(inp, 0.75) FILTER (WHERE event_type = 'view') AS inp, quantile_cont(cls, 0.75) FILTER (WHERE event_type = 'view') AS cls,
           |quantile_cont(ttfb, 0.75) FILTER (WHERE event_type = 'view') AS ttfb, quantile_cont(load_time, 0.75) FILTER (WHERE event_type = 'view') AS load_time
           |FROM $from WHERE $w""".stripMargin).onComplete {
    case scala.util.Success(r) => s.kpi := Rows.of(r).headOption.getOrElse(Map.empty)
    case scala.util.Failure(e) => s.error := e.getMessage
  }
  q(s, s"""SELECT (_timestamp // $step) * $step AS t, count(*) FILTER (WHERE event_type IN ('view', 'route')) AS views,
           |count(*) FILTER (WHERE event_type = 'error') AS errors FROM $from WHERE $w GROUP BY 1 ORDER BY 1""".stripMargin).foreach { r =>
    val rows = Rows.of(r)
    def pts(k: String) = rows.flatMap(x => x.get("t").flatMap(Rows.long).map(t => (t / 1000.0, x.get(k).flatMap(Rows.num))))
    s.chart := ChartSpec("bar", List(ChartSeries("表示", pts("views"), "#2563eb"), ChartSeries("エラー", pts("errors"), "#dc2626")), height = 180,
      onSelect = Some((a, b) => Store.setRange(TimeSel.custom(a, b))))
  }
  q(s, s"""SELECT path AS "ページ", count(*) AS "表示", count(DISTINCT session_id) AS "セッション",
           |round(quantile_cont(load_time, 0.75)) AS "読み込み p75(ms)", round(quantile_cont(lcp, 0.75)) AS "LCP p75(ms)"
           |FROM $from WHERE $w AND event_type IN ('view', 'route') GROUP BY 1 ORDER BY 2 DESC LIMIT 30""".stripMargin).foreach(r => s.pages := Some(r))
  q(s, s"""SELECT message AS "エラー", error_type AS "種類", count(*) AS "件数", count(DISTINCT session_id) AS "セッション",
           |max(path) AS "ページの例" FROM $from WHERE $w AND event_type = 'error' GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 30""".stripMargin)
    .foreach(r => s.errors := Some(r))
  q(s, s"""SELECT browser AS "ブラウザ", os AS "OS", device AS "端末", count(DISTINCT session_id) AS "セッション"
           |FROM $from WHERE $w GROUP BY 1, 2, 3 ORDER BY 4 DESC LIMIT 20""".stripMargin).foreach(r => s.browsers := Some(r))
  q(s, s"""SELECT request_url AS "URL", status AS "HTTP", count(*) AS "件数", round(avg(duration)) AS "平均(ms)"
           |FROM $from WHERE $w AND event_type = 'resource_error' GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 30""".stripMargin)
    .foreach(r => s.failures := Some(r))
}

def kv(s: State, k: String, unit: String = "") = s.kpi.mapC(m => m.get(k).flatMap(Rows.num).map(v => Fmt.num(v, unit)).getOrElse("-"))
/** Web Vitals の評価（良い・改善が必要・悪い）. */
def grade(s: State, k: String, good: Double, poor: Double) = s.kpi.mapC(m => m.get(k).flatMap(Rows.num) match {
  case Some(v) if v <= good => "kpi good"
  case Some(v) if v > poor  => "kpi poor"
  case Some(_)              => "kpi fair"
  case None                 => "kpi"
})

def create(s: State): Unit = {
  val origins = s.newOrigins.v.split("[,\\s]+").toList.map(_.trim).filter(_.nonEmpty)
  Store.report(Api.post[RumAppDto]("/api/rum/apps", RumAppReq(s.newName.v.trim, origins, None).asJson)) { a =>
    s.newName := ""; s.newOrigins := ""; s.app := a.id.toString; Prefs.set("rum.app", s.app.v); loadApps(s)
  }
}

def remove(s: State, a: RumAppDto): Unit = Dialog.confirm(s"アプリ「${a.name}」を削除しますか？（送られてくるイベントを受け付けなくなります。集めたデータは残ります）", "削除する") {
  Store.report(Api.delete(s"/api/rum/apps/${a.id}"))(_ => loadApps(s))
}

def snippet(a: RumAppDto): String = {
  val base = Store.config.v.map(_.publicUrl).getOrElse("")
  s"""<script src="$base/rum.js" data-token="${a.token}" defer></script>"""
}
def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "active" else "")
def isTab(s: State, t: String) = s.tab.mapC(_ == t)
</script>

<template>
  <div class="page wide">
    <div class="toolbar"><h1 class="grow">フロントエンド</h1>
      <select :value="s.app" @change="pick(s, Dom.value(event))"><option v-for="a in s.apps" :value="a.id.toString" :selected="s.app.mapC(_ == a.id.toString)">{{ a.name }}</option></select>
      <TimeRange /></div>
    <nav class="tabs">
      <button :class='tabCls(s, "overview")' @click='s.tab := "overview"'>概要</button>
      <button :class='tabCls(s, "apps")' @click='s.tab := "apps"'>アプリと埋め込み</button>
    </nav>

    <div v-if='isTab(s, "overview")'>
      <p v-if="s.error.mapC(_.nonEmpty)" class="error mono">{{ s.error }}</p>
      <div class="kpis">
        <div class="kpi"><div class="label">表示</div><div class="value">{{ kv(s, "views") }}</div></div>
        <div class="kpi"><div class="label">セッション</div><div class="value">{{ kv(s, "sessions") }}</div></div>
        <div class="kpi"><div class="label">JavaScript のエラー</div><div class="value">{{ kv(s, "errors") }}</div></div>
        <div class="kpi"><div class="label">通信の失敗</div><div class="value">{{ kv(s, "failures") }}</div></div>
        <div :class='grade(s, "lcp", 2500, 4000)'><div class="label">LCP（p75）</div><div class="value">{{ kv(s, "lcp", "ms") }}</div><div class="sub">2.5秒以下が良好</div></div>
        <div :class='grade(s, "inp", 200, 500)'><div class="label">INP（p75）</div><div class="value">{{ kv(s, "inp", "ms") }}</div><div class="sub">200ms 以下が良好</div></div>
        <div :class='grade(s, "cls", 0.1, 0.25)'><div class="label">CLS（p75）</div><div class="value">{{ kv(s, "cls") }}</div><div class="sub">0.1 以下が良好</div></div>
        <div :class='grade(s, "fcp", 1800, 3000)'><div class="label">FCP（p75）</div><div class="value">{{ kv(s, "fcp", "ms") }}</div></div>
        <div :class='grade(s, "ttfb", 800, 1800)'><div class="label">TTFB（p75）</div><div class="value">{{ kv(s, "ttfb", "ms") }}</div></div>
        <div class="kpi"><div class="label">読み込み（p75）</div><div class="value">{{ kv(s, "load_time", "ms") }}</div></div>
      </div>
      <Chart :spec="s.chart" />
      <h2>ページ</h2>
      <ResultTable v-for="r in s.pages.mapC(_.toList)" :result="r" />
      <h2>JavaScript のエラー</h2>
      <ResultTable v-for="r in s.errors.mapC(_.toList)" :result="r" :maxCell="300" />
      <h2>通信の失敗（HTTP 400 以上・つながらない）</h2>
      <ResultTable v-for="r in s.failures.mapC(_.toList)" :result="r" />
      <h2>ブラウザ・端末</h2>
      <ResultTable v-for="r in s.browsers.mapC(_.toList)" :result="r" />
      <p class="small muted">細かい分析はログの画面の SQL（データの種類 rum）でも行えます。</p>
    </div>

    <div v-if='isTab(s, "apps")'>
      <p class="muted small">アプリごとに、ページに貼る script を発行します。トークンはブラウザに見えてよい値で、取り込みだけに使えます。
        「送ってよいサイト」を指定すると、それ以外のサイトからは受け付けません（* はどこでも）。</p>
      <div v-for="a in s.apps" :key="a.id" class="panel">
        <div class="row"><b class="grow">{{ a.name }}</b><span class="muted small">ストリーム {{ a.stream }}・{{ a.origins.mkString(", ") }}</span>
          <button v-if="Store.isAdmin" class="small danger" @click="remove(s, a)">削除</button></div>
        <pre class="code">{{ snippet(a) }}</pre>
        <button class="small" @click="Dom.copy(snippet(a))">コピー</button>
        <span class="muted small">利用者の識別は <code>skorimako.setUser(id)</code>、独自のイベントは <code>skorimako.track('購入', &#123;amount: 1200&#125;)</code>。</span>
      </div>
      <form v-if="Store.isAdmin" class="panel" @submit.prevent="create(s)">
        <h3>アプリを登録する</h3>
        <label class="field"><span>名前</span><input type="text" v-model="s.newName" placeholder="ショップのサイト"></label>
        <label class="field"><span>送ってよいサイト（空白・カンマ区切り。空ならどこでも）</span><input type="text" v-model="s.newOrigins" placeholder="https://shop.example.com"></label>
        <button type="submit" class="primary">登録</button>
      </form>
      <p v-else class="muted small">アプリの登録は管理者が行います。</p>
    </div>
  </div>
</template>

<style scoped>
.kpi.good { border-color: color-mix(in srgb, var(--ok) 50%, var(--line)); }
.kpi.good .value { color: var(--ok); }
.kpi.fair .value { color: var(--warn); }
.kpi.poor { border-color: color-mix(in srgb, var(--danger) 50%, var(--line)); }
.kpi.poor .value { color: var(--danger); }
</style>
