<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*
import io.circe.syntax.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 設定: プロフィール・画面の色・APIキー（取り込み・API・MCP）と、送り方の例
case class State(name: RV[String] = RV(""), tz: RV[String] = RV("Asia/Tokyo"),
                 keys: RV[List[ApiKeyDto]] = RV(Nil), keyName: RV[String] = RV(""), scopes: RV[List[String]] = RV(List(KeyScope.Ingest)),
                 days: RV[String] = RV(""), created: RV[Option[String]] = RV(None), tab: RV[String] = RV("collector"),
                 integrations: RV[List[IntegrationDto]] = RV(Nil))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("設定")
  Store.me.v.foreach { m => s.name := m.name; s.tz := m.timezone }
  loadKeys(s)
  loadIntegrations(s)
  // stui の許可の画面から戻ったとき（?integration=stui&result=connected）
  Query.get("integration").foreach { p =>
    Query.get("result") match {
      case Some("connected") => Store.toast(s"${AppNames.nameOf(p)} と連携しました")
      case Some("denied")    => Store.toast(s"${AppNames.nameOf(p)} との連携は許可されませんでした", error = true)
      case _                 => Store.toast(s"${AppNames.nameOf(p)} と連携できませんでした。もう一度お試しください", error = true)
    }
    Query.replace()
  }
}

def loadIntegrations(s: State): Unit = Api.get[List[IntegrationDto]]("/api/integrations").foreach(s.integrations := _)

def connect(p: String): Unit = org.scalajs.dom.window.location.href = s"/api/integrations/$p/connect?returnTo=" + Api.enc("/settings")

def disconnect(s: State, i: IntegrationDto): Unit = Dialog.confirm(s"${i.name} との連携をやめますか？（${i.name} の DM へのアラートの通知が届かなくなります）", "やめる") {
  Store.report(Api.delete(s"/api/integrations/${i.provider}/connection")) { _ => Store.toast(s"${i.name} との連携をやめました"); loadIntegrations(s) }
}

def loadKeys(s: State): Unit = Store.report(Api.get[List[ApiKeyDto]]("/api/me/keys"))(s.keys := _)

def saveMe(s: State): Unit = Store.report(Api.patch[MeDto]("/api/me", UpdateMeReq(Some(s.name.v), Some(s.tz.v)).asJson)) { m =>
  Store.me := Some(m); Store.toast("保存しました")
}

def toggleScope(s: State, sc: String): Unit = s.scopes := (if (s.scopes.v.contains(sc)) s.scopes.v.filterNot(_ == sc) else s.scopes.v :+ sc)
def scopeOn(s: State, sc: String) = s.scopes.mapC(_.contains(sc))

def create(s: State): Unit =
  Store.report(Api.post[CreatedKeyDto]("/api/me/keys", CreateKeyReq(s.keyName.v.trim, s.scopes.v, s.days.v.toIntOption).asJson)) { k =>
    s.created := Some(k.key); s.keyName := ""; loadKeys(s)
  }

def revoke(s: State, k: ApiKeyDto): Unit = Dialog.confirm(s"APIキー「${k.name}」を失効させますか？（このキーを使っている送り手・AI は使えなくなります）", "失効させる") {
  Store.report(Api.delete(s"/api/me/keys/${k.id}"))(_ => loadKeys(s))
}

def base = Store.config.mapC(_.map(_.publicUrl).getOrElse(""))
def keyOr(s: State) = s.created.mapC(_.getOrElse("<APIキー>"))

def collector(s: State) = computed {
  s"""exporters:
     |  otlphttp/skorimako:
     |    endpoint: ${base.v}
     |    headers:
     |      Authorization: "Bearer ${keyOr(s).v}"
     |      stream-name: default        # ログ・トレースのストリーム（省略時 default）
     |    compression: gzip
     |
     |service:
     |  pipelines:
     |    logs:    { receivers: [otlp], processors: [batch], exporters: [otlphttp/skorimako] }
     |    traces:  { receivers: [otlp], processors: [batch], exporters: [otlphttp/skorimako] }
     |    metrics: { receivers: [otlp], processors: [batch], exporters: [otlphttp/skorimako] }""".stripMargin
}

def sdk(s: State) = computed {
  s"""# アプリから直接送る（OpenTelemetry の SDK の環境変数）
     |OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
     |OTEL_EXPORTER_OTLP_ENDPOINT=${base.v}
     |OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer ${keyOr(s).v}
     |OTEL_SERVICE_NAME=my-service""".stripMargin
}

def curl(s: State) = computed {
  s"""curl -X POST ${base.v}/api/ingest/app/_json \\
     |  -H "Authorization: Bearer ${keyOr(s).v}" -H "Content-Type: application/json" \\
     |  -d '[{"level":"error","message":"disk full","host":"web1"}]'""".stripMargin
}

def mcp(s: State) = computed {
  s"""# Claude Code
     |claude mcp add --transport http skorimako ${base.v}/mcp --header "Authorization: Bearer ${keyOr(s).v}"
     |
     |# 設定ファイル（.mcp.json など）
     |{ "mcpServers": { "skorimako": { "type": "http", "url": "${base.v}/mcp",
     |    "headers": { "Authorization": "Bearer ${keyOr(s).v}" } } } }""".stripMargin
}

def grpcPort = Store.config.mapC(_.map(_.grpcPort).getOrElse(0))
def host = Store.config.mapC(_.map(c => new org.scalajs.dom.URL(c.publicUrl).hostname).getOrElse("localhost"))

def grpc(s: State) = computed {
  s"""exporters:
     |  otlp/skorimako:                  # OTLP/gRPC
     |    endpoint: ${host.v}:${grpcPort.v}
     |    headers:
     |      authorization: "Bearer ${keyOr(s).v}"
     |      stream-name: default
     |    tls: { insecure: false }          # TLS を終端するロードバランサーの先なら true にしない
     |
     |# SDK の環境変数
     |OTEL_EXPORTER_OTLP_PROTOCOL=grpc
     |OTEL_EXPORTER_OTLP_ENDPOINT=https://${host.v}:${grpcPort.v}
     |OTEL_EXPORTER_OTLP_HEADERS=authorization=Bearer ${keyOr(s).v}""".stripMargin
}

def prom(s: State) = computed {
  s"""# Prometheus から送る（prometheus.yml）
     |remote_write:
     |  - url: ${base.v}/api/prom/api/v1/write
     |    authorization: { credentials: "${keyOr(s).v}" }      # ingest のキー
     |
     |# Grafana のデータソース（種類 Prometheus）
     |#   URL:            ${base.v}/api/prom
     |#   認証:           Basic（ユーザー名は何でもよい、パスワードに read のキー）
     |#                   または Custom HTTP Header  Authorization: Bearer <read のキー>
     |# PromQL の例:  sum by (service_name) (rate(http_server_requests[5m]))
     |#              histogram_quantile(0.95, sum by (le) (rate(http_server_duration_bucket[5m])))""".stripMargin
}

def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "active" else "")
def example(s: State) = computed { s.tab.v match {
  case "sdk"  => sdk(s).v
  case "curl" => curl(s).v
  case "mcp"  => mcp(s).v
  case "grpc" => grpc(s).v
  case "prom" => prom(s).v
  case _      => collector(s).v
} }
def visibleScopes = Store.isAdmin.mapC(a => KeyScope.all.filter(sc => a || sc != KeyScope.Admin))
</script>

<template>
  <div class="page narrow">
    <h1>設定</h1>
    <h2>プロフィール</h2>
    <div class="row">
      <label class="field grow"><span>名前</span><input type="text" v-model="s.name"></label>
      <label class="field"><span>タイムゾーン</span><input type="text" v-model="s.tz"></label>
    </div>
    <p><button @click="saveMe(s)">保存</button></p>
    <label class="field"><span>画面の色（この端末）</span>
      <select :value="Theme.current" @change="Theme.set(Dom.value(event))"><option v-for="o in Theme.options" :value="o._1">{{ o._2 }}</option></select></label>

    <h2>通知</h2>
    <p class="muted small">あなたが作ったアラートのルールと、あなた宛ての {{ AppNames.of("stui") }} の DM を通知先にしたルールが、発生・解消したときに届きます（ミュート中は届きません）。</p>
    <p><PushToggle /></p>

    <template v-for="i in s.integrations">
      <h2>{{ i.name }} との連携</h2>
      <p class="muted small">連携すると、アラートをあなたの {{ i.name }} の DM に送れます（アラート → 通知先 →「自分の {{ i.name }} の DM を通知先にする」）。</p>
      <p v-if="i.connected" class="row"><span class="ok">✓ 連携しています</span><button class="small" @click="disconnect(s, i)">連携をやめる</button></p>
      <p v-else><button class="primary" @click="connect(i.provider)">{{ i.name }} と連携する</button></p>
    </template>

    <h2>APIキー</h2>
    <p class="muted small">観測データの送り手（OpenTelemetry Collector・SDK）、ほかのシステムからの API、AI（MCP）に使います。
      送り手には <b>ingest</b> だけのキーを使ってください（検索はできません）。</p>
    <div class="table-wrap" v-if="s.keys.mapC(_.nonEmpty)">
      <table class="list">
        <thead><tr><th>名前</th><th>キー</th><th>スコープ</th><th>期限</th><th>最終使用</th><th></th></tr></thead>
        <tbody>
          <tr v-for="k in s.keys" :key="k.id">
            <td>{{ k.name }}</td><td class="key">{{ k.prefix }}…</td><td class="small">{{ k.scopes.mkString(", ") }}</td>
            <td class="small">{{ k.expiresAt.map(Fmt.dateTime).getOrElse("なし") }}</td><td class="small">{{ k.lastUsedAt.map(Fmt.ago).getOrElse("-") }}</td>
            <td><button class="small danger" @click="revoke(s, k)">失効</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <form class="panel" @submit.prevent="create(s)">
      <label class="field"><span>名前</span><input type="text" v-model="s.keyName" placeholder="本番の Collector"></label>
      <div class="field"><span>スコープ</span>
        <label v-for="sc in visibleScopes" class="check"><input type="checkbox" :checked="scopeOn(s, sc)" @change="toggleScope(s, sc)"> <code>{{ sc }}</code> {{ KeyScope.label(sc) }}</label></div>
      <label class="field"><span>有効期限（日。空ならなし）</span><input type="number" min="1" v-model="s.days"></label>
      <button type="submit" class="primary">APIキーを作る</button>
    </form>
    <div v-for="k in s.created.mapC(_.toList)" class="panel created">
      <p><b>APIキーを作りました。</b>この画面を離れると二度と表示できません。下の例にも入れてあります。</p>
      <div class="row"><code class="grow key-full">{{ k }}</code><button class="small" @click="Dom.copy(k)">コピー</button></div>
    </div>

    <h2>送り方・つなぎ方の例</h2>
    <nav class="tabs">
      <button :class='tabCls(s, "collector")' @click='s.tab := "collector"'>OTel Collector</button>
      <button :class='tabCls(s, "sdk")' @click='s.tab := "sdk"'>SDK の環境変数</button>
      <button :class='tabCls(s, "curl")' @click='s.tab := "curl"'>JSON（curl）</button>
      <button v-if="grpcPort.mapC(_ > 0)" :class='tabCls(s, "grpc")' @click='s.tab := "grpc"'>OTLP/gRPC</button>
      <button :class='tabCls(s, "prom")' @click='s.tab := "prom"'>Prometheus・Grafana</button>
      <button :class='tabCls(s, "mcp")' @click='s.tab := "mcp"'>MCP（AI）</button>
    </nav>
    <pre class="code">{{ example(s) }}</pre>
    <button class="small" @click="Dom.copy(example(s).v)">コピー</button>
    <p class="muted small">OTLP/HTTP（protobuf・JSON、gzip）の <code>/v1/logs</code>・<code>/v1/metrics</code>・<code>/v1/traces</code> で受けます。
      OTLP/gRPC は <code>{{ grpcPort }}</code> 番で受けます（0 なら止めています）。Basic 認証しか使えない送り手は、パスワードに APIキーを入れても送れます。</p>
  </div>
</template>

<style scoped>
.created { border-color: var(--ok); }
.key-full { word-break: break-all; background: var(--side); padding: .3rem .5rem; border-radius: 6px; }
</style>
