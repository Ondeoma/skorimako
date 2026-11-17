<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.syntax.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ストリームの詳細: 列と型、保持期間・説明の変更、削除（管理者）
case class Props(streamType: String, name: String)

case class State(d: RV[Option[StreamDetailDto]] = RV(None), retention: RV[String] = RV(""), description: RV[String] = RV(""),
                 groups: RV[String] = RV(""), maxRows: RV[String] = RV(""),
                 rules: RV[List[IngestRuleDto]] = RV(Nil),
                 kind: RV[String] = RV(IngestRuleKind.Mask), field: RV[String] = RV(""), pattern: RV[String] = RV("email"),
                 replacement: RV[String] = RV(""), ratio: RV[String] = RV("0.1"), all: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State, props: Props): Unit = { pageTitle(props.name); load(s, props) }

def load(s: State, props: Props): Unit = Store.report(Api.stream(props.streamType, props.name)) { d =>
  s.d := Some(d); s.retention := d.stream.retentionDays.toString; s.description := d.stream.description
  s.groups := d.stream.allowedGroups.mkString(", "); s.maxRows := d.stream.maxRowsPerMinute.map(_.toString).getOrElse("")
  if (Store.admin) loadRules(s, props)
}

def loadRules(s: State, props: Props): Unit = Api.get[List[IngestRuleDto]]("/api/ingest/rules").foreach(rs =>
  s.rules := rs.filter(r => r.streamType == props.streamType && (r.stream == props.name || r.stream == "*")))

def addRule(s: State, props: Props): Unit = {
  val r = IngestRuleReq(props.streamType, if (s.all.v) "*" else props.name, s.rules.v.size, s.kind.v, s.field.v.trim, s.pattern.v.trim,
    s.replacement.v, s.ratio.v.toDoubleOption.getOrElse(1.0), true)
  Store.report(Api.post[io.circe.Json]("/api/ingest/rules", r.asJson)) { _ => Store.toast("変換を足しました（30秒以内にすべてのノードで効きます）"); loadRules(s, props) }
}

def toggleRule(s: State, props: Props, r: IngestRuleDto): Unit =
  Store.report(Api.putUnit(s"/api/ingest/rules/${r.id}", IngestRuleReq(r.streamType, r.stream, r.position, r.kind, r.field, r.pattern, r.replacement,
    r.ratio, !r.enabled).asJson))(_ => loadRules(s, props))

def removeRule(s: State, props: Props, r: IngestRuleDto): Unit = Dialog.confirm("この変換を削除しますか？", "削除する") {
  Store.report(Api.delete(s"/api/ingest/rules/${r.id}"))(_ => loadRules(s, props))
}

def describe(r: IngestRuleDto): String = r.kind match {
  case IngestRuleKind.Mask => s"${if (r.field.isEmpty) "すべての文字列の列" else r.field} の ${IngestRuleKind.presets.find(_._1 == r.pattern).map(_._2).getOrElse(s"/${r.pattern}/")} を伏せる"
  case IngestRuleKind.DropField => s"列 ${r.field} を落とす"
  case IngestRuleKind.DropRows => s"${r.field} が /${r.pattern}/ に合う行を捨てる"
  case IngestRuleKind.Sample => s"${(r.ratio * 100).round}% だけ残す（トレースごとにそろえる）"
  case IngestRuleKind.Rename => s"列 ${r.field} を ${r.replacement} にする"
  case k => k
}
def isKind(s: State, k: String) = s.kind.mapC(_ == k)

def save(s: State, props: Props): Unit =
  Store.report(Api.patchUnit(s"/api/streams/${props.streamType}/${Api.enc(props.name)}",
    UpdateStreamReq(s.retention.v.toIntOption, Some(s.description.v), Some(s.groups.v.split(",").toList.map(_.trim).filter(_.nonEmpty)),
      Some(s.maxRows.v.trim.toIntOption.getOrElse(0))).asJson)) { _ => Store.toast("保存しました"); load(s, props) }

def remove(props: Props): Unit =
  Dialog.prompt(s"ストリーム ${props.name} とすべてのデータを削除します。元に戻せません。\n確認のため名前を入力してください。", "", "完全に削除する") { n =>
    if (n.trim == props.name) Store.report(Api.delete(s"/api/streams/${props.streamType}/${Api.enc(props.name)}"))(_ => Store.go("/streams"))
    else Store.toast("名前が一致しないため削除しませんでした", error = true)
  }

def open(props: Props): Unit = props.streamType match {
  case StreamType.Logs    => Store.go("/logs?stream=" + Api.enc(props.name))
  case StreamType.Metrics => Store.go("/metrics?metric=" + Api.enc(props.name))
  case StreamType.Traces  => Store.go("/traces")
  case _                  => Store.go("/rum")
}
def fields(s: State) = s.d.mapC(_.map(_.fields).getOrElse(Nil))
def st(s: State) = s.d.mapC(_.map(_.stream).toList)
</script>

<template>
  <div class="page narrow">
    <p class="small"><a href="/streams" @click.prevent='Store.go("/streams")'>← ストリーム</a></p>
    <h1><span class="muted">{{ StreamType.label(props.streamType) }} /</span> <span class="mono">{{ props.name }}</span></h1>
    <div v-for="x in st(s)" class="kpis">
      <div class="kpi"><div class="label">行数</div><div class="value">{{ Fmt.count(x.docCount) }}</div></div>
      <div class="kpi"><div class="label">保存量</div><div class="value">{{ Fmt.bytes(x.storedBytes) }}</div><div class="sub">ファイル {{ x.fileCount }}</div></div>
      <div class="kpi"><div class="label">最新のデータ</div><div class="value small">{{ x.maxTs.map(t => Fmt.agoMs(t / 1000.0)).getOrElse("-") }}</div></div>
    </div>
    <p><button @click="open(props)">データを見る →</button></p>
    <h2>列</h2>
    <div class="table-wrap">
      <table class="list"><thead><tr><th>名前</th><th>型</th></tr></thead>
        <tbody><tr><td class="mono">_timestamp</td><td class="small">int64（マイクロ秒）</td></tr>
          <tr v-for="f in fields(s)"><td class="mono">{{ f.name }}</td><td class="small">{{ f.fieldType }}</td></tr></tbody></table>
    </div>
    <template v-if="Store.isAdmin">
      <h2>設定</h2>
      <label class="field"><span>保持期間（日。0 で既定）</span><input type="number" min="0" v-model="s.retention"></label>
      <label class="field"><span>説明</span><input type="text" v-model="s.description"></label>
      <label class="field"><span>閲覧できるグループ（IdP のグループ。カンマ区切り。空ならログインした全員。管理者はいつでも見られる）</span>
        <input type="text" v-model="s.groups" placeholder="sre, security"></label>
      <label class="field"><span>取り込みの上限（1分あたりの行。ノードごと。空か 0 で上限なし。超えると 429 で断る）</span>
        <input type="number" min="0" v-model="s.maxRows"></label>
      <p class="row"><button class="primary" @click="save(s, props)">保存</button><button class="danger right" @click="remove(props)">ストリームを削除</button></p>

      <h2>取り込みの変換</h2>
      <p class="muted small">取り込むとき（保存の前）に、上から順に当てます。個人情報を伏せる・要らない列や行を落とす・間引くのに使います。
        「すべてのストリーム」の変換もここに出ます。</p>
      <div v-for="r in s.rules" :key="r.id" class="panel row">
        <span class="badge">{{ IngestRuleKind.label(r.kind) }}</span><span class="grow">{{ describe(r) }}<span v-if='r.stream == "*"' class="muted small">（すべてのストリーム）</span></span>
        <button class="small" @click="toggleRule(s, props, r)">{{ if (r.enabled) "止める" else "有効にする" }}</button>
        <button class="small danger" @click="removeRule(s, props, r)">削除</button>
      </div>
      <form class="panel" @submit.prevent="addRule(s, props)">
        <div class="row">
          <label class="field"><span>種類</span><select v-model="s.kind"><option v-for="k in IngestRuleKind.all" :value="k">{{ IngestRuleKind.label(k) }}</option></select></label>
          <label v-if='s.kind.mapC(k => k != "sample")' class="field grow"><span>列（カンマ区切り、末尾の * で前方一致。伏せるは空ですべて）</span><input type="text" v-model="s.field" placeholder="body"></label>
        </div>
        <label v-if='isKind(s, "mask")' class="field"><span>伏せるもの</span>
          <select v-model="s.pattern"><option v-for="p in IngestRuleKind.presets" :value="p._1">{{ p._2 }}</option><option value="">正規表現で指定…</option></select></label>
        <label v-if='s.kind.mapC(k => k == "drop_rows" || k == "mask")' class="field"><span>正規表現（伏せるで既定のものを選んだら空でよい）</span>
          <input type="text" class="mono" v-model="s.pattern" placeholder="^GET /healthz"></label>
        <label v-if='s.kind.mapC(k => k == "mask" || k == "rename")' class="field"><span>{{ s.kind.mapC(k => if (k == "rename") "新しい列の名前" else "置き換える文字（空なら既定）") }}</span>
          <input type="text" v-model="s.replacement"></label>
        <label v-if='isKind(s, "sample")' class="field"><span>残す割合（0〜1）</span><input type="number" step="0.01" min="0" max="1" v-model="s.ratio"></label>
        <label class="check"><input type="checkbox" v-model="s.all"> この種類のすべてのストリームに当てる</label>
        <button type="submit" class="primary">変換を足す</button>
      </form>
    </template>
  </div>
</template>
