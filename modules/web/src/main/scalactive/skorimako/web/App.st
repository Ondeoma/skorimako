<script lang="scala">
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import org.scalajs.dom
import skorimako.shared.*
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
  Api.clientConfig().foreach(c => Store.config := Some(c))
  if (dom.window.location.pathname.startsWith("/login")) s.ready := true
  else Store.report(Api.me()) { me =>
    Store.me := Some(me)
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

def navCls(path: String) = Router.href.mapC { h =>
  val p = new dom.URL(h).pathname
  if (p == path || (path != "/" && p.startsWith(path + "/"))) "item active" else "item"
}
def userName = Store.me.mapC(_.map(_.name).getOrElse(""))
def loggedIn = Store.me.mapC(_.isDefined)
def sideClass = Store.sideOpen.mapC(o => if (o) "side open" else "side")
</script>

<template>
  <div class="shell">
    <aside v-if="loggedIn" :class="sideClass">
      <a href="/" class="brand" @click.prevent='Store.go("/")'><img src="/logo.svg" alt="" width="28" height="28"><span>skorimako</span></a>
      <nav class="scroll">
        <a v-for="n in nav" :href="n._1" :class="navCls(n._1)" @click.prevent="Store.go(n._1)"><span class="ic">{{ n._2 }}</span><span class="name">{{ n._3 }}</span></a>
      </nav>
      <div class="foot">
        <a href="/settings" :class='navCls("/settings")' @click.prevent='Store.go("/settings")'><span class="ic">⚙️</span><span class="name">設定・APIキー</span></a>
        <a v-if="Store.isAdmin" href="/admin" :class='navCls("/admin")' @click.prevent='Store.go("/admin")'><span class="ic">🛡</span><span class="name">管理</span></a>
        <div class="me"><span class="name">{{ userName }}</span><button class="ghost small" @click="logout()">ログアウト</button></div>
      </div>
    </aside>
    <div v-if="Store.sideOpen" class="side-bg" @click="Store.sideOpen := false"></div>
    <div class="main">
      <header v-if="loggedIn" class="top only-sp">
        <button class="ghost" @click="Store.sideOpen := !Store.sideOpen.v">☰</button>
        <img src="/logo.svg" alt="" width="24" height="24"><b>skorimako</b>
      </header>
      <main class="content">
        <RouterComponent v-if="s.ready" :routes="routes" />
      </main>
    </div>
  </div>
  <DialogHost />
  <div class="toasts">
    <div v-for="t in Store.toasts" :key="t.id" :class='if (t.error) "toast error" else "toast"'>
      <span class="grow" @click="t.link.foreach(l => Store.go(l)); Store.dismiss(t.id)">{{ t.text }}</span>
      <button @click="Store.dismiss(t.id)">×</button>
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100vh; height: 100dvh; overflow: hidden; }
.side { width: 220px; flex: none; background: var(--side-bg); color: var(--side-fg); display: flex; flex-direction: column; min-height: 0; }
.brand { display: flex; align-items: center; gap: .5rem; padding: .9rem 1rem; color: var(--side-fg); font-weight: 700; font-size: 1.05rem; }
.brand:hover { text-decoration: none; }
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
