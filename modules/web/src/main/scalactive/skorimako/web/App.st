<script lang="scala">
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import org.scalajs.dom
import skorimako.web.parts.*
import skorimako.web.views.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 画面全体の枠: 左のサイドバー（ホーム・ログ・メトリクス・トレース・フロントエンド・ダッシュボード・アラート・ストリーム）と、ルートごとの画面
case class State(ready: RV[Boolean] = RV(false), menu: RV[Boolean] = RV(false))

def initState() = State()

val routes: PathPatternRoutes = List(
  ("/", _ => HomeView()),
  ("/login", _ => LoginView()),
  ("/logs", _ => LogsView()),
  ("/metrics", _ => MetricsView()),
  ("/traces", _ => TracesView()),
  ("/traces/:id", r => TraceView(traceId = r.pathname.groups("id"))),
  ("/services", _ => ServicesView()),
  ("/rum", _ => RumView()),
  ("/dashboards", _ => DashboardsView()),
  ("/dashboards/:id", r => DashboardView(dashboardId = UUID.fromString(r.pathname.groups("id")))),
  ("/alerts", _ => AlertsView()),
  ("/alerts/new", _ => AlertEditView(ruleId = None)),
  ("/alerts/:id", r => AlertEditView(ruleId = Some(UUID.fromString(r.pathname.groups("id"))))),
  ("/streams", _ => StreamsView()),
  ("/streams/:type/:name", r => StreamView(streamType = r.pathname.groups("type"), name = r.pathname.groups("name"))),
  ("/settings", _ => SettingsView()),
  ("/admin", _ => AdminView()),
)

def mounted(s: State): Unit = {
  Fallback.install(routes)
  // 画面を移ったら、スマホの幅のサイドバーを閉じる
  Router.afterEach((_, _) => Store.sideOpen := false)
  Api.clientConfig().foreach(c => Store.config := Some(c))
  if (dom.window.location.pathname.startsWith("/login")) s.ready := true
  else Toasts.report(Api.me()) { me =>
    Store.me := Some(me)
    AppNames.set(me.appNames)
    s.ready := true
  }
}

def logout(): Unit = Api.logout().onComplete(r => dom.window.location.href = r.getOrElse("/"))

val nav: List[(String, String, String)] = List(
  ("/", "🏠", "ホーム"),
  ("/logs", "📜", "ログ"),
  ("/metrics", "📈", "メトリクス"),
  ("/traces", "🧭", "トレース"),
  ("/services", "🔗", "サービス"),
  ("/rum", "🖥", "フロントエンド"),
  ("/dashboards", "📊", "ダッシュボード"),
  ("/alerts", "🔔", "アラート"),
  ("/streams", "🗂", "ストリーム"),
)

def userName = Store.me.mapC(_.map(_.name).getOrElse(""))
def loggedIn = Store.me.mapC(_.isDefined)
def sideClass = Store.sideOpen.mapC(o => if (o) "side open" else "side")
</script>

<template>
  <div class="shell">
    <aside v-if="loggedIn" :class="sideClass">
      <RouterLink to="/" class="brand"><img :src='AppNames.logo("/logo.svg").v' alt="" height="28" class="brand-logo"><span>{{ AppNames.self }}</span></RouterLink>
      <nav class="scroll">
        <RouterLink v-for="n in nav" :to="n._1" class="item" active-class="active"><span class="ic">{{ n._2 }}</span><span class="name">{{ n._3 }}</span></RouterLink>
      </nav>
      <div class="foot">
        <RouterLink to="/settings" class="item" active-class="active"><span class="ic">⚙️</span><span class="name">設定・APIキー</span></RouterLink>
        <RouterLink v-if="Store.isAdmin" to="/admin" class="item" active-class="active"><span class="ic">🛡</span><span class="name">管理</span></RouterLink>
        <div class="me"><span class="name">{{ userName }}</span><button class="ghost small" @click="logout()">ログアウト</button></div>
      </div>
    </aside>
    <div v-if="Store.sideOpen" class="side-bg" @click="Store.sideOpen := false"></div>
    <div class="main">
      <header v-if="loggedIn" class="top only-sp">
        <button class="ghost" @click="Store.sideOpen := !Store.sideOpen.v">☰</button>
        <img :src='AppNames.logo("/logo.svg").v' alt="" height="24" class="brand-logo"><b>{{ AppNames.self }}</b>
      </header>
      <main class="content">
        <RouterComponent v-if="s.ready" :routes="routes" :reloadOnQueryChange="false" />
      </main>
    </div>
  </div>
  <DialogHost />
  <ToastHost />
</template>

<style scoped>
.shell { display: flex; height: 100vh; height: 100dvh; overflow: hidden; }
.side { width: 220px; flex: none; background: var(--side-bg); color: var(--side-fg); display: flex; flex-direction: column; min-height: 0; }
.brand { display: flex; align-items: center; gap: .5rem; padding: .9rem 1rem; color: var(--side-fg); font-weight: 700; font-size: 1.05rem; }
.brand:hover { text-decoration: none; }
.brand-logo { display: block; width: auto; max-width: 120px; }
.scroll { flex: 1; overflow-y: auto; padding: .2rem .5rem; }
.item { display: flex; align-items: center; gap: .55rem; padding: .45rem .6rem; border-radius: 7px; color: var(--side-fg); font-size: .92rem; margin-bottom: 1px; }
.item:hover { background: var(--side-hover); text-decoration: none; }
.item.active { background: var(--side-active); }
.ic { width: 1.4rem; text-align: center; }
.foot { border-top: 1px solid rgba(255, 255, 255, .1); padding: .5rem; }
.me { display: flex; align-items: center; gap: .4rem; padding: .4rem .6rem; color: var(--side-muted); font-size: .82rem; }
.me .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.me button { color: var(--side-muted); }
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
.content { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.top { height: 48px; flex: none; display: flex; align-items: center; gap: .5rem; padding: 0 .6rem; border-bottom: 1px solid var(--line); }
.side-bg { display: none; }
@media (max-width: 760px) {
  .side { position: fixed; top: 0; bottom: 0; left: 0; z-index: 300; transform: translateX(-100%); transition: transform .18s ease-out; width: 78vw; max-width: 280px; }
  .side.open { transform: none; box-shadow: var(--shadow); }
  .side-bg { display: block; position: fixed; inset: 0; background: rgba(0, 0, 0, .45); z-index: 250; }
}
</style>
