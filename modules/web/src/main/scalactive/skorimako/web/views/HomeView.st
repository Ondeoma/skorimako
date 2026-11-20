<script lang="scala">
import skorimako.web.*
import skorimako.shared.*


// ホーム: 取り込んだデータの量、発生中のアラート、最近のアラートの履歴、ストリーム
case class State(o: RV[Option[OverviewDto]] = RV(None))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("ホーム"); load(s) }

def load(s: State): Unit = Toasts.report(Api.get[OverviewDto]("/api/overview"))(o => s.o := Some(o))

def streams(s: State) = s.o.mapC(_.map(_.streams).getOrElse(Nil))
def firing(s: State) = s.o.mapC(_.map(_.firing).getOrElse(Nil))
def events(s: State) = s.o.mapC(_.map(_.recentEvents).getOrElse(Nil))
def empty(s: State) = s.o.mapC(_.exists(_.streams.isEmpty))
def typed(s: State, t: String) = s.o.mapC(_.map(_.streams.filter(_.streamType == t)).getOrElse(Nil))
def docs(s: State, t: String) = typed(s, t).mapC(ss => Fmt.count(ss.map(_.docCount).sum))
def size(s: State) = s.o.mapC(o => Fmt.bytes(o.map(_.streams.map(_.storedBytes).sum).getOrElse(0L)))
def today(s: State) = s.o.mapC(o => Fmt.count(o.map(_.ingestedToday).getOrElse(0L)))
def metricCount(s: State) = typed(s, StreamType.Metrics).mapC(_.size.toString)
def open(st: StreamDto): Unit = st.streamType match {
  case StreamType.Logs    => Store.go("/logs?stream=" + Api.enc(st.name))
  case StreamType.Metrics => Store.go("/metrics?metric=" + Api.enc(st.name))
  case StreamType.Traces  => Store.go("/traces")
  case _                  => Store.go("/rum")
}
def last(st: StreamDto): String = st.maxTs.map(t => Fmt.agoMs(t / 1000.0)).getOrElse("-")
def evIcon(e: AlertEventDto): String = e.state match { case "firing" => "🔴"; case "resolved" => "✅"; case _ => "⚠️" }
def nonMetric(s: State) = streams(s).mapC(_.filter(_.streamType != StreamType.Metrics))
</script>

<template>
  <div class="page">
    <h1>ホーム</h1>
    <div class="kpis">
      <div class="kpi"><div class="label">ログ（行）</div><div class="value">{{ docs(s, "logs") }}</div></div>
      <div class="kpi"><div class="label">スパン</div><div class="value">{{ docs(s, "traces") }}</div></div>
      <div class="kpi"><div class="label">メトリクス（種類）</div><div class="value">{{ metricCount(s) }}</div></div>
      <div class="kpi"><div class="label">フロントエンドのイベント</div><div class="value">{{ docs(s, "rum") }}</div></div>
      <div class="kpi"><div class="label">今日の取り込み（行）</div><div class="value">{{ today(s) }}</div></div>
      <div class="kpi"><div class="label">保存量（圧縮後）</div><div class="value">{{ size(s) }}</div></div>
    </div>

    <div v-if="empty(s)" class="panel">
      <h2>はじめに</h2>
      <p>まだデータがありません。OpenTelemetry Collector などから OTLP/HTTP で送ると、ここに出ます。</p>
      <ol>
        <li><a href="/settings" @click.prevent='Store.go("/settings")'>設定</a>で <b>ingest</b> スコープの APIキーを作ります。</li>
        <li>Collector の <code>otlphttp</code> の送り先を <code>{{ Store.config.mapC(_.map(_.publicUrl).getOrElse("")) }}</code> にし、
          <code>Authorization: Bearer &lt;APIキー&gt;</code> を付けます（設定画面に例があります）。</li>
        <li>ブラウザのアプリは<a href="/rum" @click.prevent='Store.go("/rum")'>フロントエンド</a>でアプリを登録し、表示された script を貼ります。</li>
      </ol>
    </div>

    <h2>発生中のアラート</h2>
    <p v-if="firing(s).mapC(_.isEmpty)" class="muted">ありません 🎉</p>
    <div v-for="r in firing(s)" :key="r.id" class="row alert" @click='Store.go("/alerts/" + r.id)'>
      <span :class='"state " + r.state'>{{ AlertState.label(r.state) }}</span>
      <b>{{ r.name }}</b>
      <span class="muted small">{{ StreamType.label(r.streamType) }} / {{ r.stream }}・値 {{ r.lastValue.map(v => Fmt.num(v)).getOrElse("-") }}</span>
      <span class="muted small right">{{ r.lastFiredAt.map(Fmt.ago).getOrElse("") }}</span>
    </div>

    <h2>最近のアラートの履歴</h2>
    <p v-if="events(s).mapC(_.isEmpty)" class="muted">ありません</p>
    <div v-for="e in events(s)" :key="e.id" class="row ev">
      <span>{{ evIcon(e) }}</span><span class="grow">{{ e.message }}</span><span class="muted small">{{ Fmt.ago(e.at) }}</span>
    </div>

    <h2>ストリーム</h2>
    <div class="table-wrap">
      <table class="list">
        <thead><tr><th>種類</th><th>名前</th><th>行数</th><th>保存量</th><th>最新のデータ</th></tr></thead>
        <tbody>
          <tr v-for="st in nonMetric(s)" :key="st.id" class="clickable" @click="open(st)">
            <td>{{ StreamType.label(st.streamType) }}</td><td><b>{{ st.name }}</b></td>
            <td>{{ Fmt.count(st.docCount) }}</td><td>{{ Fmt.bytes(st.storedBytes) }}</td><td class="muted">{{ last(st) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="small"><a href="/streams" @click.prevent='Store.go("/streams")'>すべてのストリーム（メトリクスを含む）→</a></p>
  </div>
</template>

<style scoped>
.alert { padding: .5rem .7rem; border: 1px solid var(--line); border-radius: 8px; margin-bottom: .4rem; cursor: pointer; }
.alert:hover { background: var(--hover); }
.ev { padding: .3rem 0; border-bottom: 1px solid var(--line); font-size: .88rem; }
</style>
