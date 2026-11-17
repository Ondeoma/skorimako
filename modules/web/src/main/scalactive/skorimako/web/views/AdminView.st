<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.syntax.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 管理（システム管理者）: ユーザー・監査ログ・取り込みの状況（このノード）
case class State(tab: RV[String] = RV("users"), users: RV[List[UserDto]] = RV(Nil), q: RV[String] = RV(""),
                 audit: RV[List[AuditDto]] = RV(Nil), pending: RV[Map[String, Long]] = RV(Map.empty), nodes: RV[List[io.circe.Json]] = RV(Nil))

def initState() = State()

def load(s: State): Unit = {
  Store.report(Api.get[List[UserDto]]("/api/admin/users" + Api.query("q" -> Some(s.q.v))))(s.users := _)
  Api.get[List[AuditDto]]("/api/admin/audit?limit=300").foreach(s.audit := _)
  Api.get[Map[String, Long]]("/api/ingest/pending").foreach(s.pending := _)
  Api.get[List[io.circe.Json]]("/api/nodes").foreach(s.nodes := _)
}

def mounted(s: State): Unit = { pageTitle("管理"); load(s) }

def setUser(s: State, u: UserDto, admin: Option[Boolean], disabled: Option[Boolean]): Unit =
  Store.report(Api.patchUnit(s"/api/admin/users/${u.id}", UpdateUserReq(admin, disabled).asJson))(_ => load(s))

def tab(s: State, v: String) = s.tab.mapC(x => if (x == v) "active" else "")
def isTab(s: State, v: String) = s.tab.mapC(_ == v)
def me(u: UserDto): Boolean = Store.me.v.exists(_.id == u.id)
def pendingList(s: State) = s.pending.mapC(_.toList.sortBy(_._1))
</script>

<template>
  <div class="page">
    <h1>管理</h1>
    <nav class="tabs">
      <button :class='tab(s, "users")' @click='s.tab := "users"'>ユーザー</button>
      <button :class='tab(s, "audit")' @click='s.tab := "audit"'>監査ログ</button>
      <button :class='tab(s, "ingest")' @click='s.tab := "ingest"'>取り込み</button>
    </nav>
    <div v-if='isTab(s, "users")'>
      <form class="row" @submit.prevent="load(s)"><input type="search" v-model="s.q" placeholder="名前・メールで絞り込み"><button type="submit">検索</button></form>
      <p class="muted small">ユーザーは初めてログインしたときに作られます。IdP のグループ（ADMIN_GROUPS）に入っている人は、ここでの設定によらず管理者です。</p>
      <div class="table-wrap">
        <table class="list">
          <thead><tr><th>名前</th><th>メール</th><th>グループ</th><th>最終ログイン</th><th>管理者</th><th>無効</th></tr></thead>
          <tbody>
            <tr v-for="u in s.users" :key="u.id">
              <td>{{ u.name }}</td><td class="small">{{ u.email.getOrElse("") }}</td><td class="small">{{ u.groups.mkString(", ") }}</td>
              <td class="small">{{ u.lastLoginAt.map(Fmt.ago).getOrElse("") }}</td>
              <td><input type="checkbox" :checked="u.isAdmin" :disabled="me(u)" @change="setUser(s, u, Some(Dom.checked(event)), None)"></td>
              <td><input type="checkbox" :checked="u.disabled" :disabled="me(u)" @change="setUser(s, u, None, Some(Dom.checked(event)))"></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if='isTab(s, "audit")' class="table-wrap">
      <table class="list">
        <thead><tr><th>日時</th><th>操作者</th><th>経路</th><th>操作</th><th>対象</th><th>詳細</th></tr></thead>
        <tbody>
          <tr v-for="a in s.audit" :key="a.id">
            <td class="small nowrap">{{ Fmt.dateTime(a.at) }}</td><td class="small">{{ a.actor.map(_.name).getOrElse("") }}</td><td class="small">{{ a.via }}</td>
            <td><code class="small">{{ a.action }}</code></td><td class="small key">{{ a.target.getOrElse("").take(40) }}</td>
            <td class="small muted">{{ a.detail.noSpaces.take(120) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if='isTab(s, "ingest")'>
      <p class="muted small">このノード（この画面を返したノード）で、まだ S3 に書き出していない行の数です。数秒ごとに書き出されます。</p>
      <p v-if="s.pending.mapC(_.isEmpty)" class="muted">ありません</p>
      <div v-for="p in pendingList(s)" class="row"><span class="mono grow">{{ p._1 }}</span><b>{{ Fmt.count(p._2) }}</b></div>
      <p><button class="small" @click="load(s)">読み直す</button></p>
      <h3>動いているノード</h3>
      <p class="muted small">各ノードは NODE_URL を30秒ごとに登録します。検索するノードは、ほかのノードの書き出す前の行もここから集めます。</p>
      <div v-for="n in s.nodes" class="row"><span class="mono grow">{{ n.hcursor.get[String]("id").getOrElse("") }}</span>
        <span class="muted small">{{ n.hcursor.get[String]("url").getOrElse("") }}</span></div>
    </div>
  </div>
</template>
