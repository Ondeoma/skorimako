<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*
import io.circe.Json
import io.circe.syntax.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// アラート: ルールの一覧と状態、履歴、通知先（stui・Slack 互換・Webhook）、送信の記録
case class State(tab: RV[String] = RV(Query.get("tab").getOrElse("rules")),
                 rules: RV[List[AlertRuleDto]] = RV(Nil),
                 events: RV[List[AlertEventDto]] = RV(Nil),
                 dests: RV[List[DestinationDto]] = RV(Nil),
                 deliveries: RV[List[DeliveryDto]] = RV(Nil),
                 silences: RV[List[SilenceDto]] = RV(Nil),
                 integrations: RV[List[IntegrationDto]] = RV(Nil),
                 mReason: RV[String] = RV(""), mFrom: RV[String] = RV(""), mTo: RV[String] = RV(""),
                 editing: RV[Option[Option[UUID]]] = RV(None),
                 dName: RV[String] = RV(""), dKind: RV[String] = RV(DestinationKind.Stui), dUrl: RV[String] = RV(""), dSecret: RV[String] = RV(""))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("アラート"); load(s) }

def load(s: State): Unit = {
  Store.report(Api.get[List[AlertRuleDto]]("/api/alerts"))(s.rules := _)
  Api.get[List[AlertEventDto]]("/api/alerts/events?limit=200").foreach(s.events := _)
  Api.get[List[DestinationDto]]("/api/destinations").foreach(s.dests := _)
  if (Store.admin) Api.get[List[DeliveryDto]]("/api/deliveries?limit=200").foreach(s.deliveries := _)
  Api.get[List[SilenceDto]]("/api/silences?all=true").foreach(s.silences := _)
  Api.get[List[IntegrationDto]]("/api/integrations").foreach(s.integrations := _)
}

def cond(r: AlertRuleDto): String =
  if (r.mode == AlertMode.Absence) s"直近${r.periodMinutes}分にデータが来ない" + r.groupBy.map(g => s"（$g ごと）").getOrElse("")
  else if (r.mode == AlertMode.Sql) s"SQL の値 ${r.op} ${Fmt.num(r.threshold)}"
  else {
    val what = if (r.agg == "count") "件数" else s"${r.agg}(${r.field.getOrElse("")})"
    val by = r.groupBy.map(g => s"（$g ごと）").getOrElse("")
    s"直近${r.periodMinutes}分の$what$by ${r.op} ${Fmt.num(r.threshold)}"
  }

def stateCls(r: AlertRuleDto): String = "state " + (if (!r.enabled) "off" else r.state)
def stateLabel(r: AlertRuleDto): String = if (!r.enabled) "停止中" else AlertState.label(r.state)
def flags(r: AlertRuleDto): String = List(r.ack.map(a => "👀 " + a.by.map(_.name).getOrElse("")), r.silencedUntil.map(_ => "🔕")).flatten.mkString(" ")

def stuiDm(s: State): Unit = Store.me.v.foreach { me =>
  Store.report(Api.post[DestinationDto]("/api/destinations", DestinationReq(s"${me.name} の stui の DM", DestinationKind.StuiDm, "", None, Some(me.id)).asJson)) { _ =>
    Store.toast("自分の stui の DM を通知先に足しました"); load(s)
  }
}
def stuiReady(s: State) = s.integrations.mapC(_.exists(i => i.provider == "stui" && i.connected))
def stuiConfigured(s: State) = s.integrations.mapC(_.exists(_.provider == "stui"))
def mine(d: DestinationDto): Boolean = d.targetUser.exists(u => Store.me.v.exists(_.id == u.id))
def canEdit(d: DestinationDto): Boolean = Store.admin || mine(d)

def addSilence(s: State): Unit = {
  val a = new scala.scalajs.js.Date(s.mFrom.v).getTime(); val b = new scala.scalajs.js.Date(s.mTo.v).getTime()
  if (a.isNaN || b.isNaN || a >= b) Store.toast("始まりと終わりを正しく入れてください", error = true)
  else Store.report(Api.post[Json]("/api/silences", SilenceReq(None, s.mReason.v.trim, Some(Timestamp(new scala.scalajs.js.Date(a).toISOString())),
    Some(Timestamp(new scala.scalajs.js.Date(b).toISOString())), None).asJson)) { _ => s.mReason := ""; load(s) }
}
def endSilence(s: State, x: SilenceDto): Unit = Store.report(Api.delete(s"/api/silences/${x.id}"))(_ => load(s))
def active(x: SilenceDto): Boolean = x.endsAt.toEpochMilli > scala.scalajs.js.Date.now() && x.startsAt.toEpochMilli <= scala.scalajs.js.Date.now()
def upcoming(x: SilenceDto): Boolean = x.startsAt.toEpochMilli > scala.scalajs.js.Date.now()

def toggle(s: State, r: AlertRuleDto): Unit =
  Store.report(Api.put[AlertRuleDto](s"/api/alerts/${r.id}", r.toReq.copy(enabled = !r.enabled).asJson))(_ => load(s))

def editDest(s: State, d: Option[DestinationDto]): Unit = {
  s.dName := d.map(_.name).getOrElse(""); s.dKind := d.map(_.kind).getOrElse(DestinationKind.Stui); s.dUrl := d.map(_.url).getOrElse(""); s.dSecret := ""
  s.editing := Some(d.map(_.id))
}

def saveDest(s: State): Unit = s.editing.v.foreach { id =>
  val secret = if (s.dKind.v == DestinationKind.Webhook && s.dSecret.v.nonEmpty) Some(s.dSecret.v) else if (id.isEmpty) None else if (s.dKind.v != DestinationKind.Webhook) Some("") else None
  val req = DestinationReq(s.dName.v, s.dKind.v, s.dUrl.v, secret).asJson
  val f = id match {
    case Some(x) => Api.put[DestinationDto](s"/api/destinations/$x", req)
    case None    => Api.post[DestinationDto]("/api/destinations", req)
  }
  Store.report(f) { _ => s.editing := None; load(s) }
}

def test(d: DestinationDto): Unit = Store.report(Api.post[Json](s"/api/destinations/${d.id}/test"))(_ => Store.toast(s"「${d.name}」に試しに送りました"))

def removeDest(s: State, d: DestinationDto): Unit = Dialog.confirm(s"通知先「${d.name}」を削除しますか？（ルールからも外れます）", "削除する") {
  Store.report(Api.delete(s"/api/destinations/${d.id}"))(_ => load(s))
}

def evIcon(e: AlertEventDto): String = e.state match { case "firing" => "🔴"; case "resolved" => "✅"; case "acknowledged" => "👀"; case _ => "⚠️" }
def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "active" else "")
def isTab(s: State, t: String) = s.tab.mapC(_ == t)
def setTab(s: State, t: String): Unit = { s.tab := t; Query.replace("tab" -> Some(t)) }
def isWebhook(s: State) = s.dKind.mapC(_ == DestinationKind.Webhook)
def isStui(s: State) = s.dKind.mapC(_ == DestinationKind.Stui)
def status(d: DeliveryDto): String = d.status match { case "sent" => "送信済み"; case "pending" => "送信待ち"; case "failed" => "失敗"; case "skipped" => "送らなかった"; case x => x }
</script>

<template>
  <div class="page">
    <div class="toolbar"><h1 class="grow">アラート</h1><button class="primary" @click='Store.go("/alerts/new")'>＋ ルールを作る</button></div>
    <nav class="tabs">
      <button :class='tabCls(s, "rules")' @click='setTab(s, "rules")'>ルール</button>
      <button :class='tabCls(s, "events")' @click='setTab(s, "events")'>履歴</button>
      <button :class='tabCls(s, "dests")' @click='setTab(s, "dests")'>通知先</button>
      <button :class='tabCls(s, "silences")' @click='setTab(s, "silences")'>ミュート</button>
      <button v-if="Store.isAdmin" :class='tabCls(s, "deliveries")' @click='setTab(s, "deliveries")'>送信の記録</button>
    </nav>

    <div v-if='isTab(s, "rules")'>
      <p v-if="s.rules.mapC(_.isEmpty)" class="empty">ルールはまだありません。ログの件数・メトリクスの値などに閾値を決めて、stui などに知らせられます。</p>
      <div v-else class="table-wrap">
        <table class="list">
          <thead><tr><th>状態</th><th>名前</th><th>対象</th><th>条件</th><th>値</th><th>評価</th><th></th></tr></thead>
          <tbody>
            <tr v-for="r in s.rules" :key="r.id" class="clickable" @click='Store.go("/alerts/" + r.id)'>
              <td class="nowrap"><span :class="stateCls(r)">{{ stateLabel(r) }}</span></td>
              <td><b>{{ r.name }}</b> <span class="small">{{ flags(r) }}</span></td>
              <td class="small">{{ StreamType.label(r.streamType) }} / {{ r.stream }}</td>
              <td class="small">{{ cond(r) }}</td>
              <td>{{ r.lastValue.map(v => Fmt.num(v)).getOrElse("-") }}</td>
              <td class="small muted nowrap">{{ r.lastEvalAt.map(Fmt.ago).getOrElse("まだ") }}・{{ r.everyMinutes }}分ごと</td>
              <td @click.stop="()"><button class="small" @click="toggle(s, r)">{{ if (r.enabled) "止める" else "動かす" }}</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if='isTab(s, "events")'>
      <p v-if="s.events.mapC(_.isEmpty)" class="empty">履歴はまだありません</p>
      <div v-for="e in s.events" :key="e.id" class="ev row" @click='Store.go("/alerts/" + e.ruleId)'>
        <span>{{ evIcon(e) }}</span><span class="grow">{{ e.message }}</span><span class="muted small nowrap">{{ Fmt.dateTime(e.at) }}</span>
      </div>
    </div>

    <div v-if='isTab(s, "dests")'>
      <div class="hint">
        <b>stui に知らせる</b>: stui のチャンネルの ⚙ →「連携・定期メッセージ」で受信 Webhook を作り、表示された URL を貼ります。発生・解消をそのチャンネルに投稿します。<br>
        <b>Slack・Mattermost 互換</b>: Incoming Webhook の URL。<b>Webhook</b>: JSON を POST します（署名の鍵を決めると <code>X-Skorimako-Signature: sha256=…</code> を付けます）。
      </div>
      <div v-for="d in s.dests" :key="d.id" class="panel row">
        <b>{{ d.name }}</b><span class="badge">{{ DestinationKind.label(d.kind) }}</span><span class="muted small grow url">{{ d.url }}</span>
        <span v-for="u in d.targetUser.toList" class="small">→ {{ u.name }}</span>
        <template v-if="canEdit(d)">
          <button class="small" @click="test(d)">試しに送る</button>
          <button class="small" @click="editDest(s, Some(d))">編集</button>
          <button class="small danger" @click="removeDest(s, d)">削除</button>
        </template>
      </div>
      <p class="row">
        <button v-if="Store.isAdmin" @click="editDest(s, None)">＋ 通知先を追加</button>
        <button v-if="stuiReady(s)" @click="stuiDm(s)">＋ 自分の stui の DM を通知先にする</button>
      </p>
      <p v-if="stuiConfigured(s).mapC(c => c && !stuiReady(s).v)" class="muted small">自分の stui の DM に知らせるには、
        <a href="/settings" @click.prevent='Store.go("/settings")'>設定</a>で stui と連携してください。</p>
      <p v-if="Store.isAdmin.mapC(!_)" class="muted small">通知先の追加・変更は管理者が行います（自分宛ての stui の DM は誰でも追加できます）。</p>
    </div>

    <div v-if='isTab(s, "silences")'>
      <p class="muted small">ミュートの間も評価と履歴は続け、通知だけ止めます。ルールごとのミュートはルールの画面で、すべてのルールのミュート（メンテナンスの時間帯）は管理者がここで決めます。</p>
      <div v-for="x in s.silences" :key="x.id" class="panel row">
        <span>{{ if (active(x)) "🔕 ミュート中" else if (upcoming(x)) "⏳ 予定" else "終わり" }}</span>
        <b>{{ x.ruleName.getOrElse("すべてのルール") }}</b>
        <span class="small">{{ Fmt.dateTime(x.startsAt) }} 〜 {{ Fmt.dateTime(x.endsAt) }}</span>
        <span class="muted small grow">{{ x.reason }}{{ x.createdBy.map(u => "（" + u.name + "）").getOrElse("") }}</span>
        <button v-if="active(x) || upcoming(x)" class="small" @click="endSilence(s, x)">終える</button>
      </div>
      <form v-if="Store.isAdmin" class="panel" @submit.prevent="addSilence(s)">
        <h3>メンテナンスの時間帯（すべてのルール）</h3>
        <div class="row">
          <label class="field"><span>始まり</span><input type="datetime-local" v-model="s.mFrom"></label>
          <label class="field"><span>終わり</span><input type="datetime-local" v-model="s.mTo"></label>
          <label class="field grow"><span>理由</span><input type="text" v-model="s.mReason" placeholder="DB の更新作業"></label>
        </div>
        <button type="submit" class="primary">ミュートを決める</button>
      </form>
    </div>

    <div v-if='isTab(s, "deliveries")' class="table-wrap">
      <table class="list">
        <thead><tr><th>日時</th><th>ルール</th><th>種類</th><th>通知先</th><th>結果</th><th>試行</th><th>エラー</th></tr></thead>
        <tbody>
          <tr v-for="d in s.deliveries" :key="d.id">
            <td class="small nowrap">{{ Fmt.dateTime(d.createdAt) }}</td><td>{{ d.ruleName }}</td><td class="small">{{ d.state }}</td>
            <td>{{ d.destination }}</td><td class="small">{{ status(d) }}</td><td>{{ d.attempts }}</td><td class="small error">{{ d.lastError.getOrElse("") }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <Modal v-for="e in s.editing.mapC(_.toList)" :onClose="_ => s.editing := None">
      <h2>{{ if (e.isDefined) "通知先を変える" else "通知先を追加" }}</h2>
      <label class="field"><span>名前</span><input type="text" v-model="s.dName" placeholder="stui #運用"></label>
      <label class="field"><span>種類</span><select v-model="s.dKind"><option v-for="k in DestinationKind.all.filterNot(_ == DestinationKind.StuiDm)" :value="k">{{ DestinationKind.label(k) }}</option></select></label>
      <label class="field"><span>URL</span><input type="url" v-model="s.dUrl" placeholder="https://stui.example.com/hooks/…"></label>
      <p v-if="isStui(s)" class="muted small">stui の受信 Webhook の URL（https://stui の URL/hooks/…）を貼ります。</p>
      <label v-if="isWebhook(s)" class="field"><span>署名の鍵（任意。変えるときだけ入力）</span><input type="text" v-model="s.dSecret"></label>
      <div class="actions"><button @click="s.editing := None">キャンセル</button><button class="primary" @click="saveDest(s)">保存</button></div>
    </Modal>
  </div>
</template>

<style scoped>
.ev { padding: .4rem .2rem; border-bottom: 1px solid var(--line); font-size: .88rem; cursor: pointer; }
.ev:hover { background: var(--hover); }
.url { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
