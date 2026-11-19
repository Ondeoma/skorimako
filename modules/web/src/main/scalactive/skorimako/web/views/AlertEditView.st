<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.syntax.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// アラートのルールの作成・編集. 「試す」で今の値を確かめられる. 既存のルールは履歴も出す.
case class Props(ruleId: Option[UUID])

case class State(name: RV[String] = RV(""),
                 description: RV[String] = RV(""),
                 enabled: RV[Boolean] = RV(true),
                 streamType: RV[String] = RV(Query.get("type").getOrElse(StreamType.Logs)),
                 stream: RV[String] = RV(Query.get("stream").getOrElse("default")),
                 mode: RV[String] = RV(AlertMode.Threshold),
                 where: RV[String] = RV(Query.get("where").getOrElse("")),
                 agg: RV[String] = RV(Query.get("agg").getOrElse("count")),
                 field: RV[String] = RV(Query.get("field").getOrElse("")),
                 groupBy: RV[String] = RV(""),
                 sql: RV[String] = RV(""),
                 op: RV[String] = RV(">"),
                 threshold: RV[String] = RV("0"),
                 period: RV[String] = RV("5"),
                 every: RV[String] = RV("1"),
                 silence: RV[String] = RV("60"),
                 dests: RV[List[UUID]] = RV(Nil),
                 allDests: RV[List[DestinationDto]] = RV(Nil),
                 streams: RV[List[StreamDto]] = RV(Nil),
                 fields: RV[List[FieldDto]] = RV(Nil),
                 rule: RV[Option[AlertRuleDto]] = RV(None),
                 events: RV[List[AlertEventDto]] = RV(Nil),
                 lookback: RV[String] = RV("1440"),
                 note: RV[String] = RV(""),
                 test: RV[Option[AlertTestDto]] = RV(None),
                 error: RV[String] = RV(""))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  pageTitle(if (props.ruleId.isDefined) "アラートのルール" else "アラートのルールを作る")
  Api.get[List[DestinationDto]]("/api/destinations").foreach { ds =>
    s.allDests := ds
    if (props.ruleId.isEmpty) s.dests := ds.map(_.id)
  }
  props.ruleId match {
    case Some(id) =>
      Store.report(Api.get[AlertRuleDto](s"/api/alerts/$id")) { r => fill(s, r); loadStreams(s) }
      Api.get[List[AlertEventDto]](s"/api/alerts/events?ruleId=$id&limit=100").foreach(s.events := _)
    case None =>
      if (s.sql.v.isEmpty) s.sql := defaultSql(s)
      loadStreams(s)
  }
}

def fill(s: State, r: AlertRuleDto): Unit = {
  s.rule := Some(r)
  s.name := r.name; s.description := r.description; s.enabled := r.enabled; s.streamType := r.streamType; s.stream := r.stream; s.mode := r.mode
  s.where := r.where.getOrElse(""); s.agg := r.agg; s.field := r.field.getOrElse(""); s.groupBy := r.groupBy.getOrElse(""); s.sql := r.sql.getOrElse("")
  s.op := r.op; s.threshold := (if (r.threshold == math.rint(r.threshold)) r.threshold.toLong.toString else r.threshold.toString); s.period := r.periodMinutes.toString; s.every := r.everyMinutes.toString
  s.silence := r.silenceMinutes.toString; s.dests := r.destinationIds; s.lookback := r.lookbackMinutes.toString
  if (s.sql.v.isEmpty) s.sql := defaultSql(s)
}

def defaultSql(s: State): String =
  s"SELECT service_name AS grp, count(*) AS value\nFROM \"${s.stream.v}\"\nWHERE severity_text = 'ERROR'\nGROUP BY 1"

def loadStreams(s: State): Unit = {
  Api.streams(Some(s.streamType.v)).foreach(s.streams := _)
  loadFields(s)
}

def loadFields(s: State): Unit =
  if (s.stream.v.nonEmpty) Api.stream(s.streamType.v, s.stream.v).onComplete {
    case Success(d) => s.fields := d.fields
    case Failure(_) => s.fields := Nil
  }

def changeType(s: State, t: String): Unit = { s.streamType := t; s.stream := ""; s.fields := Nil; if (t == StreamType.Metrics) { s.agg := "avg"; s.field := "value" }; loadStreams(s) }
def changeStream(s: State, v: String): Unit = { s.stream := v; loadFields(s) }

def req(s: State): AlertRuleReq = AlertRuleReq(s.name.v, s.description.v, s.enabled.v, s.streamType.v, s.stream.v, s.mode.v,
  Option(s.where.v).filter(_.trim.nonEmpty), s.agg.v, Option(s.field.v).filter(_.nonEmpty), Option(s.groupBy.v).filter(_.nonEmpty),
  Option(s.sql.v).filter(_.trim.nonEmpty), s.op.v, s.threshold.v.trim.toDoubleOption.getOrElse(0), s.period.v.toIntOption.getOrElse(5),
  s.every.v.toIntOption.getOrElse(1), s.silence.v.toIntOption.getOrElse(0), s.dests.v, s.lookback.v.toIntOption)

def runTest(s: State): Unit = {
  s.error := ""
  Api.post[AlertTestDto]("/api/alerts/test", req(s).asJson).onComplete {
    case Success(t) => s.test := Some(t)
    case Failure(e) => s.error := e.getMessage
  }
}

def save(s: State, props: Props): Unit = {
  s.error := ""
  val f = props.ruleId match {
    case Some(id) => Api.put[AlertRuleDto](s"/api/alerts/$id", req(s).asJson)
    case None     => Api.post[AlertRuleDto]("/api/alerts", req(s).asJson)
  }
  f.onComplete {
    case Success(r) => Store.toast("保存しました"); if (props.ruleId.isEmpty) Store.go(s"/alerts/${r.id}") else fill(s, r)
    case Failure(e) => s.error := e.getMessage
  }
}

def remove(props: Props): Unit = props.ruleId.foreach { id =>
  Dialog.confirm("このルールを削除しますか？（履歴も消えます）", "削除する") { Store.report(Api.delete(s"/api/alerts/$id"))(_ => Store.go("/alerts")) }
}

def reload(s: State, props: Props): Unit = props.ruleId.foreach { id =>
  Api.get[AlertRuleDto](s"/api/alerts/$id").foreach(r => s.rule := Some(r))
  Api.get[List[AlertEventDto]](s"/api/alerts/events?ruleId=$id&limit=100").foreach(s.events := _)
}

def ack(s: State, props: Props): Unit = props.ruleId.foreach { id =>
  Store.report(Api.post[AlertRuleDto](s"/api/alerts/$id/ack", AckReq(s.note.v.trim).asJson)) { _ => s.note := ""; Store.toast("対応中にしました"); reload(s, props) }
}

def unack(s: State, props: Props): Unit = props.ruleId.foreach { id => Store.report(Api.delete(s"/api/alerts/$id/ack"))(_ => reload(s, props)) }

def mute(s: State, props: Props, minutes: Int): Unit = props.ruleId.foreach { id =>
  Store.report(Api.post[io.circe.Json]("/api/silences", SilenceReq(Some(id), "", None, None, Some(minutes)).asJson)) { _ =>
    Store.toast(s"${minutes / 60}時間ミュートしました（評価と履歴は続けます）"); reload(s, props)
  }
}

def unmute(s: State, props: Props): Unit = props.ruleId.foreach { id =>
  Api.get[List[SilenceDto]]("/api/silences").foreach(_.filter(_.ruleId.contains(id)).foreach(x =>
    Store.report(Api.delete(s"/api/silences/${x.id}"))(_ => reload(s, props))))
}

def acked(s: State) = s.rule.mapC(_.flatMap(_.ack).toList)
def isFiring(s: State) = s.rule.mapC(_.exists(r => r.state == AlertState.Firing && r.ack.isEmpty))
def muted(s: State) = s.rule.mapC(_.flatMap(_.silencedUntil).toList)
def notMuted(s: State) = s.rule.mapC(r => r.isDefined && r.exists(_.silencedUntil.isEmpty))
def evLabel(e: AlertEventDto): String = (if (e.silenced) "（ミュート中・通知なし）" else "")

def toggleDest(s: State, id: UUID): Unit = s.dests := (if (s.dests.v.contains(id)) s.dests.v.filterNot(_ == id) else s.dests.v :+ id)
def destOn(s: State, id: UUID) = s.dests.mapC(_.contains(id))
def isMode(s: State, m: String) = s.mode.mapC(_ == m)
def needsField(s: State) = s.agg.mapC(_ != "count")
def numFields(s: State) = s.fields.mapC(_.filter(f => f.fieldType == FieldType.Int64 || f.fieldType == FieldType.Float64).map(_.name))
def allFields(s: State) = s.fields.mapC(_.map(_.name).filterNot(_ == Columns.Timestamp))
def evIcon(e: AlertEventDto): String = e.state match { case "firing" => "🔴"; case "resolved" => "✅"; case "acknowledged" => "👀"; case _ => "⚠️" }
def testText(t: AlertTestDto): String =
  t.error.getOrElse(if (t.firing) s"条件に当てはまります（${t.hits.size} 件）" else "今は条件に当てはまりません")
def hitText(h: AlertHitDto): String = h.group.map(g => s"$g: ").getOrElse("") + Fmt.num(h.value)
def stateLine(s: State) = s.rule.mapC(_.map(r => s"${AlertState.label(r.state)}・値 ${r.lastValue.map(v => Fmt.num(v)).getOrElse("-")}・" +
  s"最後の評価 ${r.lastEvalAt.map(Fmt.ago).getOrElse("まだ")}" + r.lastError.map(e => s"・$e").getOrElse("")).getOrElse(""))
</script>

<template>
  <div class="page narrow">
    <p class="small"><a href="/alerts" @click.prevent='Store.go("/alerts")'>← アラート</a></p>
    <h1>{{ if (props.ruleId.isDefined) "アラートのルール" else "アラートのルールを作る" }}</h1>
    <p v-if="s.rule.mapC(_.isDefined)" class="muted small">{{ stateLine(s) }}</p>
    <div v-for="a in acked(s)" class="panel ackp">👀 <b>{{ a.by.map(_.name).getOrElse("") }}</b> が対応中です（{{ Fmt.ago(a.at) }}）
      <span v-if="a.note.nonEmpty">: {{ a.note }}</span>。解消するまで再通知しません。
      <button class="small" @click="unack(s, props)">対応中を外す</button></div>
    <form v-if="isFiring(s)" class="panel row" @submit.prevent="ack(s, props)">
      <span class="error">🔴 発生中です。</span><input type="text" v-model="s.note" placeholder="メモ（任意）" class="grow">
      <button type="submit" class="primary small">対応中にする</button></form>
    <div v-for="m in muted(s)" class="panel row">🔕 {{ Fmt.dateTime(m) }} までミュート中です（評価と履歴は続け、通知だけ止めます）。
      <button class="small" @click="unmute(s, props)">ミュートを終える</button></div>
    <p v-if="notMuted(s)" class="row small"><span class="muted">ミュート:</span>
      <button class="small ghost" @click="mute(s, props, 60)">1時間</button><button class="small ghost" @click="mute(s, props, 240)">4時間</button>
      <button class="small ghost" @click="mute(s, props, 1440)">1日</button></p>

    <label class="field"><span>名前</span><input type="text" v-model="s.name" placeholder="API のエラーが増えた"></label>
    <label class="field"><span>説明（通知には出ません）</span><input type="text" v-model="s.description"></label>
    <label class="check"><input type="checkbox" v-model="s.enabled"> 有効</label>

    <h2>何を見るか</h2>
    <div class="row">
      <label class="field"><span>データの種類</span>
        <select :value="s.streamType" @change="changeType(s, Dom.value(event))"><option v-for="t in StreamType.all" :value="t" :selected="s.streamType.mapC(_ == t)">{{ StreamType.label(t) }}</option></select></label>
      <label class="field grow"><span>ストリーム</span>
        <input type="text" list="alert-streams" :value="s.stream" @change="changeStream(s, Dom.value(event))">
        <datalist id="alert-streams"><option v-for="st in s.streams" :value="st.name"></option></datalist></label>
    </div>
    <div class="row">
      <label class="check"><input type="radio" name="mode" :checked='isMode(s, "threshold")' @change='s.mode := "threshold"'> 集計して閾値と比べる</label>
      <label class="check"><input type="radio" name="mode" :checked='isMode(s, "sql")' @change='s.mode := "sql"'> SQL で書く</label>
      <label class="check"><input type="radio" name="mode" :checked='isMode(s, "absence")' @change='s.mode := "absence"'> データが来なくなったら（途絶）</label>
    </div>

    <template v-if='isMode(s, "threshold")'>
      <label class="field"><span>条件（SQL の WHERE。空ならすべて）</span><input type="text" class="mono" v-model="s.where" placeholder="severity_text = 'ERROR'"></label>
      <div class="row">
        <label class="field"><span>集計</span><select v-model="s.agg"><option v-for="a in AlertAgg.all" :value="a">{{ MetricAgg.label(a) }}</option></select></label>
        <label v-if="needsField(s)" class="field"><span>列</span>
          <select v-model="s.field"><option value="">選ぶ…</option><option v-for="f in numFields(s)" :value="f" :selected="s.field.mapC(_ == f)">{{ f }}</option></select></label>
        <label class="field"><span>分けて見る（任意）</span>
          <select v-model="s.groupBy"><option value="">分けない</option><option v-for="f in allFields(s)" :value="f" :selected="s.groupBy.mapC(_ == f)">{{ f }}</option></select></label>
      </div>
    </template>
    <template v-if='isMode(s, "absence")'>
      <p class="hint">「見る範囲」の間に来ていたデータが、直近の「期間」の間に来ていなければ発生します。「分けて見る」に列（service_name など）を選ぶと、
        値ごと（サービスごと）に見ます。送り手が止まった・落ちたことに気づけます。</p>
      <label class="field"><span>条件（SQL の WHERE。空ならすべて）</span><input type="text" class="mono" v-model="s.where"></label>
      <div class="row">
        <label class="field"><span>分けて見る（任意）</span>
          <select v-model="s.groupBy"><option value="">分けない</option><option v-for="f in allFields(s)" :value="f" :selected="s.groupBy.mapC(_ == f)">{{ f }}</option></select></label>
        <label class="field"><span>見る範囲（分。以前は来ていたかを見る）</span><input type="number" min="10" v-model="s.lookback"></label>
      </div>
    </template>
    <template v-if='isMode(s, "sql")'>
      <p class="hint">結果の <code>value</code> の列（なければ最初の数値の列）を閾値と比べ、当てはまる行があれば発生します。<code>grp</code> の列（なければ最初の文字列の列）は通知に出す分け方です。
        対象は直近の「期間」だけです。</p>
      <textarea class="code" v-model="s.sql"></textarea>
    </template>

    <h2>いつ知らせるか</h2>
    <div class="row">
      <label v-if='s.mode.mapC(_ != "absence")' class="field"><span>値が</span><select v-model="s.op"><option v-for="o in AlertOp.all" :value="o">{{ o }}</option></select></label>
      <label v-if='s.mode.mapC(_ != "absence")' class="field"><span>閾値</span><input type="number" step="any" v-model="s.threshold"></label>
      <label class="field"><span>期間（分）</span><input type="number" min="1" v-model="s.period"></label>
      <label class="field"><span>評価の間隔（分）</span><input type="number" min="1" v-model="s.every"></label>
      <label class="field"><span>発生中の再通知（分。0 はしない）</span><input type="number" min="0" v-model="s.silence"></label>
    </div>

    <h2>通知先</h2>
    <p v-if="s.allDests.mapC(_.isEmpty)" class="muted small">通知先がありません（<a href="/alerts?tab=dests" @click.prevent='Store.go("/alerts?tab=dests")'>通知先</a>で管理者が追加します）。
      通知先がなくても、状態と履歴は画面で見られます。</p>
    <label v-for="d in s.allDests" class="check"><input type="checkbox" :checked="destOn(s, d.id)" @change="toggleDest(s, d.id)"> {{ d.name }}
      <span class="muted small">{{ DestinationKind.label(d.kind, AppNames.nameOf("stui")) }}</span></label>

    <p v-if="s.error.mapC(_.nonEmpty)" class="error mono">{{ s.error }}</p>
    <div v-for="t in s.test.mapC(_.toList)" class="panel">
      <b :class='if (t.firing) "error" else if (t.error.isDefined) "error" else "ok"'>{{ testText(t) }}</b>
      <p class="small"><span v-for="h in t.values" class="badge val">{{ hitText(h) }}</span></p>
      <pre v-if="t.sql.nonEmpty" class="code">{{ t.sql }}</pre>
    </div>
    <p class="row">
      <button @click="runTest(s)">試す（今の値）</button>
      <button class="primary" @click="save(s, props)">保存</button>
      <button v-if="props.ruleId.isDefined" class="danger right" @click="remove(props)">削除</button>
    </p>

    <template v-if="props.ruleId.isDefined">
      <h2>履歴</h2>
      <p v-if="s.events.mapC(_.isEmpty)" class="muted">ありません</p>
      <div v-for="e in s.events" :key="e.id" class="row ev"><span>{{ evIcon(e) }}</span><span class="grow">{{ e.message }}<span class="muted small">{{ evLabel(e) }}</span></span>
        <span class="muted small">{{ Fmt.dateTime(e.at) }}</span></div>
    </template>
  </div>
</template>

<style scoped>
.row .field { min-width: 120px; }
.val { margin: .1rem .2rem .1rem 0; }
.ackp { border-color: var(--warn); }
.ev { padding: .3rem 0; border-bottom: 1px solid var(--line); font-size: .88rem; }
</style>
