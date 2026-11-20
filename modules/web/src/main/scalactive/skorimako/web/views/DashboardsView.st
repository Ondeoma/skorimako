<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.syntax.*


// ダッシュボードの一覧と作成
case class State(list: RV[List[DashboardBrief]] = RV(Nil))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("ダッシュボード"); load(s) }

def load(s: State): Unit = Toasts.report(Api.get[List[DashboardBrief]]("/api/dashboards"))(s.list := _)

def sample: List[PanelDto] = List(
  PanelDto("p1", "ログの件数", PanelKind.Bar, StreamType.Logs,
    "SELECT (_timestamp // $__interval) * $__interval AS t, count(*) AS \"件数\"\nFROM \"default\" GROUP BY 1 ORDER BY 1", 3, ""),
  PanelDto("p2", "エラーのログ", PanelKind.Stat, StreamType.Logs, "SELECT count(*) FROM \"default\" WHERE severity_text = 'ERROR'", 1, ""),
  PanelDto("p3", "サービスごとのログ", PanelKind.Table, StreamType.Logs,
    "SELECT service_name, count(*) AS n FROM \"default\" GROUP BY 1 ORDER BY 2 DESC LIMIT 20", 4, ""))

def create(s: State): Unit = Dialog.prompt("ダッシュボードの名前", "", "作る") { n =>
  if (n.trim.nonEmpty) Toasts.report(Api.post[DashboardDto]("/api/dashboards", DashboardReq(n.trim, "", sample).asJson))(d => Store.go(s"/dashboards/${d.id}"))
}
</script>

<template>
  <div class="page">
    <div class="toolbar"><h1 class="grow">ダッシュボード</h1><button class="primary" @click="create(s)">＋ ダッシュボードを作る</button></div>
    <p v-if="s.list.mapC(_.isEmpty)" class="empty">ダッシュボードはまだありません。SQL のパネルを並べて、よく見るグラフをまとめられます。</p>
    <div class="cards">
      <a v-for="d in s.list" :key="d.id" class="card dash" :href='"/dashboards/" + d.id' @click.prevent='Store.go("/dashboards/" + d.id)'>
        <b>{{ d.name }}</b><span class="muted small">{{ d.description }}</span>
        <span class="muted small">パネル {{ d.panelCount }}・{{ Fmt.ago(d.updatedAt) }}に更新</span>
      </a>
    </div>
  </div>
</template>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: .8rem; }
.dash { display: flex; flex-direction: column; gap: .3rem; color: var(--fg); }
.dash:hover { border-color: var(--accent); text-decoration: none; }
</style>
