<script lang="scala">
import skorimako.web.*
import skorimako.web.parts.*
import skorimako.shared.*
import io.circe.syntax.*

import java.util.UUID
import scala.scalajs.js

// ダッシュボード: パネル（SQL → 折れ線・棒・数値・表）を並べる. 編集でパネルの追加・変更・並べ替え・削除.
case class Props(dashboardId: UUID)

case class State(d: RV[Option[DashboardDto]] = RV(None),
                 version: RV[Int] = RV(0),
                 editing: RV[Option[PanelDto]] = RV(None),
                 title: RV[String] = RV(""), kind: RV[String] = RV("line"), streamType: RV[String] = RV("logs"), sql: RV[String] = RV(""),
                 width: RV[String] = RV("2"), unit: RV[String] = RV(""),
                 // かんたん作成: mode（builder・sql）、組み立ての指定、組み立てた SQL（手で直したかを見分ける）、題名・単位を自動で入れるか
                 mode: RV[String] = RV("builder"), spec: RV[Option[PanelSpec]] = RV(None), generated: RV[String] = RV(""),
                 autoTitle: RV[Boolean] = RV(false), autoUnit: RV[Boolean] = RV(false),
                 suggestedTitle: RV[String] = RV(""), suggestedUnit: RV[String] = RV(""),
                 builderKey: RV[Int] = RV(0), previewKey: RV[Int] = RV(0))

def initState() = State()

def mounted(s: State, props: Props): Unit = load(s, props)

def load(s: State, props: Props): Unit = Toasts.report(Api.get[DashboardDto](s"/api/dashboards/${props.dashboardId}")) { d =>
  s.d := Some(d); s.version := s.version.v + 1; pageTitle(d.name)
}

def save(s: State, props: Props, panels: List[PanelDto], name: Option[String] = None, desc: Option[String] = None): Unit = s.d.v.foreach { d =>
  Toasts.report(Api.put[DashboardDto](s"/api/dashboards/${props.dashboardId}",
    DashboardReq(name.getOrElse(d.name), desc.getOrElse(d.description), panels).asJson)) { n => s.d := Some(n); s.version := s.version.v + 1 }
}

def panels(s: State) = s.d.mapC(_.map(_.panels).getOrElse(Nil))
def keyed(s: State) = computed { val v = s.version.v; panels(s).v.map(p => (s"$v-${p.id}", p)) }

val defaultSpec = PanelSpec(StreamType.Logs, "default", "count", None, None, Nil, None, Some(10))

def edit(s: State, p: PanelDto): Unit = {
  s.title := p.title; s.kind := p.kind; s.streamType := p.streamType; s.sql := p.sql; s.width := p.width.toString; s.unit := p.unit
  // かんたん作成で作ったパネルはかんたん作成で開く. SQL だけのものは SQL で開く
  s.spec := p.spec; s.generated := (if (p.spec.isDefined) p.sql else "")
  s.mode := (if (p.spec.isDefined) "builder" else "sql")
  s.autoTitle := false; s.autoUnit := p.unit.isEmpty; s.suggestedTitle := ""; s.suggestedUnit := ""
  s.builderKey := s.builderKey.v + 1; s.previewKey := s.previewKey.v + 1
  s.editing := Some(p)
}

def add(s: State): Unit = {
  edit(s, PanelDto("p" + js.Date.now().toLong.toString, "新しいパネル", PanelKind.Line, StreamType.Logs, "", 2, "", Some(defaultSpec)))
  s.autoTitle := true
}

/** かんたん作成で組み立てた（選ぶたび）. 題名・単位は、自分で変えていなければ案を入れる. */
def built(s: State, sp: PanelSpec, sql: String, unit: String, title: String): Unit = {
  s.spec := Some(sp); s.streamType := sp.streamType; s.sql := sql; s.generated := sql
  // 前に入れた案のままなら（自分で書き換えていなければ）、新しい案に替える
  if (s.autoTitle.v || s.title.v == s.suggestedTitle.v) s.title := title
  if (s.autoUnit.v || s.unit.v == s.suggestedUnit.v) s.unit := unit
  s.suggestedTitle := title; s.suggestedUnit := unit
  s.previewKey := s.previewKey.v + 1
}

def setKind(s: State, k: String): Unit = { s.kind := k; s.builderKey := s.builderKey.v + 1; if (s.mode.v == "sql") s.previewKey := s.previewKey.v + 1 }
def setMode(s: State, m: String): Unit = s.mode := m
def modeCls(s: State, m: String) = s.mode.mapC(x => if (x == m) "active" else "")
def isMode(s: State, m: String) = s.mode.mapC(_ == m)

/** 編集中のパネル（プレビュー用. 変わるたびに描き直す）. */
def draft(s: State): PanelDto = s.editing.v.map(_.id).fold(PanelDto("", "", s.kind.v, s.streamType.v, s.sql.v, 4, s.unit.v))(id =>
  PanelDto(id, s.title.v, s.kind.v, s.streamType.v, s.sql.v, 4, s.unit.v))
def preview(s: State) = computed { val k = s.previewKey.v; if (s.sql.v.trim.isEmpty) Nil else List((s"pv-$k", draft(s))) }
def builderSpec(s: State) = computed { val k = s.builderKey.v; List((s"pb-$k", s.spec.v.getOrElse(defaultSpec), s.kind.v)) }

def commit(s: State, props: Props): Unit = s.editing.v.foreach { p =>
  // SQL を手で直したら、かんたん作成の指定は外す（作った SQL と合わなくなるため）
  val spec = if (s.mode.v == "builder" || s.sql.v == s.generated.v) s.spec.v else None
  val n = p.copy(title = s.title.v, kind = s.kind.v, streamType = s.streamType.v, sql = s.sql.v, width = s.width.v.toIntOption.getOrElse(2), unit = s.unit.v,
    spec = spec)
  val ps = panels(s).v
  save(s, props, if (ps.exists(_.id == p.id)) ps.map(x => if (x.id == p.id) n else x) else ps :+ n)
  s.editing := None
}

def remove(s: State, props: Props, p: PanelDto): Unit = Dialog.confirm(s"パネル「${p.title}」を外しますか？", "外す") {
  save(s, props, panels(s).v.filterNot(_.id == p.id))
}

def move(s: State, props: Props, p: PanelDto, d: Int): Unit = {
  val ps = panels(s).v
  val i = ps.indexWhere(_.id == p.id)
  val j = i + d
  if (i >= 0 && j >= 0 && j < ps.size) save(s, props, ps.updated(i, ps(j)).updated(j, p))
}

def rename(s: State, props: Props): Unit = s.d.v.foreach { d => Dialog.prompt("名前", d.name)(n => if (n.trim.nonEmpty) save(s, props, d.panels, Some(n.trim))) }

def removeDash(s: State, props: Props): Unit = Dialog.confirm("このダッシュボードを削除しますか？", "削除する") {
  Toasts.report(Api.delete(s"/api/dashboards/${props.dashboardId}"))(_ => Store.go("/dashboards"))
}

def span(p: PanelDto): String = s"grid-column: span ${math.max(1, math.min(p.width, 4))}"
def name(s: State) = s.d.mapC(_.map(_.name).getOrElse(""))
</script>

<template>
  <div class="page wide">
    <p class="small"><a href="/dashboards" @click.prevent='Store.go("/dashboards")'>← ダッシュボード</a></p>
    <div class="toolbar">
      <h1 class="grow">{{ name(s) }}</h1>
      <TimeRange />
      <button @click="add(s)">＋ パネル</button>
      <button class="ghost" @click="rename(s, props)">名前を変える</button>
      <button class="ghost danger" @click="removeDash(s, props)">削除</button>
    </div>
    <div class="grid-panels">
      <div v-for="kp in keyed(s)" :key="kp._1" class="card panel-card" :style="span(kp._2)">
        <div class="ph"><b class="grow">{{ kp._2.title }}</b>
          <button class="ghost small" title="前へ" @click="move(s, props, kp._2, -1)">←</button>
          <button class="ghost small" title="後ろへ" @click="move(s, props, kp._2, 1)">→</button>
          <button class="ghost small" @click="edit(s, kp._2)">編集</button>
          <button class="ghost small" @click="remove(s, props, kp._2)">×</button></div>
        <Panel :panel="kp._2" />
      </div>
    </div>
    <Modal v-for="p in s.editing.mapC(_.toList)" :onClose="_ => s.editing := None" :wide="true">
      <h2>パネル</h2>
      <label class="field"><span>題名</span><input type="text" v-model="s.title" @input="s.autoTitle := false"></label>
      <div class="row">
        <label class="field"><span>表示</span><select @change="setKind(s, Dom.value(event))">
          <option v-for="k in PanelKind.all" :value="k" :selected="s.kind.mapC(_ == k)">{{ PanelKind.label(k) }}</option></select></label>
        <label class="field"><span>幅（4分の）</span><select v-model="s.width"><option value="1">1</option><option value="2">2</option><option value="3">3</option><option value="4">4</option></select></label>
        <label class="field"><span>単位（ms・By・%・/s）</span><input type="text" v-model="s.unit" size="6" @input="s.autoUnit := false"></label>
      </div>
      <nav class="tabs">
        <button type="button" :class='modeCls(s, "builder")' @click='setMode(s, "builder")'>かんたん作成</button>
        <button type="button" :class='modeCls(s, "sql")' @click='setMode(s, "sql")'>SQL</button>
      </nav>
      <div v-if='isMode(s, "builder")'>
        <PanelBuilder v-for="b in builderSpec(s)" :key="b._1" :spec="b._2" :kind="b._3" :onChange="x => built(s, x._1, x._2, x._3, x._4)" />
        <details class="small"><summary class="muted">組み立てた SQL</summary><pre class="code">{{ s.sql }}</pre></details>
      </div>
      <div v-if='isMode(s, "sql")'>
        <label class="field"><span>データの種類</span><select v-model="s.streamType"><option v-for="t in StreamType.all" :value="t">{{ StreamType.label(t) }}</option></select></label>
        <label class="field"><span>SQL（折れ線・棒は1列目が時刻（マイクロ秒）。<code>$__interval</code> は区切りの長さ）。かんたん作成で作った SQL をここで直すこともできます</span>
          <textarea class="code" v-model="s.sql"></textarea></label>
        <button type="button" class="small" @click="s.previewKey := s.previewKey.v + 1">プレビューを更新</button>
      </div>
      <div class="pv"><span class="muted small">プレビュー（{{ s.title }}）</span>
        <div v-for="pp in preview(s)" :key="pp._1"><Panel :panel="pp._2" /></div>
        <p v-if="s.sql.mapC(_.trim.isEmpty)" class="muted small">ストリーム（メトリクス）を選ぶと、ここにグラフが出ます。</p>
      </div>
      <div class="actions"><button @click="s.editing := None">キャンセル</button><button class="primary" @click="commit(s, props)">保存</button></div>
    </Modal>
  </div>
</template>

<style scoped>
.panel-card { padding: .6rem .8rem; min-width: 0; }
.ph { display: flex; align-items: center; gap: .2rem; margin-bottom: .4rem; }
.ph button { padding: 0 .35rem; }
.pv { margin-top: .8rem; border: 1px dashed var(--line); border-radius: 10px; padding: .6rem .8rem; }
</style>
