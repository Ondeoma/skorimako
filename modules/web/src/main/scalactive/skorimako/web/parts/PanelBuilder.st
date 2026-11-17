<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.syntax.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ダッシュボードのパネルのかんたん作成: データの種類・ストリーム（メトリクス）・集計・分け方・条件を選ぶと SQL を組み立てる.
// 列の名前・値（種類の少ない文字列の列）・メトリクスの名前は候補から選べる. 組み立てるたびに onChange（指定・SQL・単位の目安・題名の案）を呼ぶ.
case class Props(spec: PanelSpec, kind: String, onChange: ((PanelSpec, String, String, String)) => Unit)

case class State(st: RV[String] = RV(StreamType.Logs), stream: RV[String] = RV(""), measure: RV[String] = RV("count"),
                 field: RV[String] = RV(""), group: RV[String] = RV(""), topN: RV[String] = RV("10"), service: RV[String] = RV(""),
                 filters: RV[List[PanelFilter]] = RV(Nil),
                 streams: RV[List[StreamDto]] = RV(Nil), fields: RV[List[FieldDto]] = RV(Nil), values: RV[Map[String, List[String]]] = RV(Map.empty),
                 metricServices: RV[Map[String, List[String]]] = RV(Map.empty),
                 sql: RV[String] = RV(""), error: RV[String] = RV(""), kind: RV[String] = RV(PanelKind.Line), seq: RV[Int] = RV(0))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  val p = props.spec
  s.st := p.streamType; s.stream := p.stream; s.measure := p.measure; s.field := p.field.getOrElse(""); s.group := p.groupBy.getOrElse("")
  s.topN := p.topN.getOrElse(10).toString; s.service := p.service.getOrElse(""); s.filters := p.filters; s.kind := props.kind
  if (Store.serviceNames.v.isEmpty) Api.serviceNames.foreach(Store.serviceNames := _)
  Api.metricServices.foreach(s.metricServices := _)
  loadStreams(s, props, keep = true)
}

def loadStreams(s: State, props: Props, keep: Boolean): Unit = Api.streams(Some(s.st.v)).foreach { ss =>
  s.streams := ss
  if (!keep || s.stream.v.isEmpty || !ss.exists(_.name == s.stream.v))
    s.stream := (if (s.st.v == StreamType.Metrics) "" else ss.find(_.name == "default").orElse(ss.headOption).map(_.name).getOrElse(""))
  loadFields(s, props)
}

def loadFields(s: State, props: Props): Unit =
  if (s.stream.v.isEmpty) { s.fields := Nil; build(s, props) }
  else Api.stream(s.st.v, s.stream.v).onComplete {
    case Success(d) =>
      s.fields := d.fields.filterNot(f => f.name == Columns.Timestamp || f.name == "__name__" || f.name == "__type__")
      s.values := Map.empty
      // メトリクスの種類から既定の集計（カウンターは毎秒の増加）
      if (s.st.v == StreamType.Metrics && s.measure.v == "count")
        s.measure := (if (d.stream.metricType == "counter" || d.stream.name.endsWith("_total")) "rate" else "avg")
      build(s, props)
    case Failure(_) => s.fields := Nil; build(s, props)
  }

/** 列の値の候補（種類の少ない文字列の列だけ. 一度読んだら覚える）. */
def loadValues(s: State, field: String): Unit =
  if (field.nonEmpty && s.stream.v.nonEmpty && !s.values.v.contains(field))
    Api.get[List[String]](s"/api/streams/${s.st.v}/${Api.enc(s.stream.v)}/values?field=${Api.enc(field)}").foreach(vs => s.values := s.values.v.updated(field, vs))

def spec(s: State): PanelSpec = PanelSpec(s.st.v, s.stream.v.trim, s.measure.v, Option(s.field.v).filter(_.nonEmpty), Option(s.group.v).filter(_.nonEmpty),
  s.filters.v.filter(_.field.nonEmpty), Option(s.service.v).filter(_.nonEmpty), s.topN.v.toIntOption)

/** 題名の案（例: 「default のログの件数（service_name ごと）」）. */
def titleOf(s: State): String = {
  if (s.stream.v.trim.isEmpty) return "新しいパネル"
  val what = s.st.v match {
    case StreamType.Metrics => s"${s.stream.v} "
    case t => s"${s.stream.v} の${StreamType.label(t)}"
  }
  val m = if (s.measure.v == "count") "の件数" else s"の${MetricAgg.label(s.measure.v)}" + (if (s.st.v != StreamType.Metrics && s.field.v.nonEmpty) s"（${s.field.v}）" else "")
  val svc = if (s.service.v.nonEmpty) s" [${s.service.v}]" else ""
  val g = if (s.group.v.nonEmpty) s"、${s.group.v} ごと" else ""
  s"$what$m$g$svc"
}

/** SQL を組み立て直す（選ぶたび）. 古い応答で上書きしないよう連番で見る. */
def build(s: State, props: Props): Unit = if (s.stream.v.trim.isEmpty) {
  // まだ選んでいない: プレビューを消す
  s.seq := s.seq.v + 1; s.sql := ""; s.error := ""; props.onChange((spec(s), "", "", titleOf(s)))
} else {
  val n = s.seq.v + 1
  s.seq := n
  val sp = spec(s)
  Api.post[PanelSqlDto]("/api/panels/sql", PanelSqlReq(sp, s.kind.v).asJson).onComplete {
    case Success(r) if s.seq.v == n => s.sql := r.sql; s.error := ""; props.onChange((sp, r.sql, r.unit, titleOf(s)))
    case Failure(e) if s.seq.v == n => s.error := e.getMessage
    case _ => ()
  }
}

def setType(s: State, props: Props, t: String): Unit = {
  s.st := t; s.group := ""; s.filters := Nil; s.field := ""
  s.measure := (if (t == StreamType.Traces) "p95" else "count")
  if (t == StreamType.Traces) s.field := "duration"
  loadStreams(s, props, keep = false)
}

def setStream(s: State, props: Props, v: String): Unit = {
  s.stream := v.trim; s.group := ""; s.field := (if (s.st.v == StreamType.Traces) "duration" else "")
  if (s.st.v == StreamType.Metrics) s.measure := "count"
  loadFields(s, props)
}

def set(s: State, props: Props)(f: => Unit): Unit = { f; build(s, props) }

def addFilter(s: State, props: Props): Unit = s.filters := s.filters.v :+ PanelFilter("", "=", "")
def setFilter(s: State, props: Props, i: Int, f: PanelFilter): Unit = {
  s.filters := s.filters.v.updated(i, f)
  loadValues(s, f.field)
  if (f.field.nonEmpty && (f.value.nonEmpty || f.op == "exists")) build(s, props)
}
def removeFilter(s: State, props: Props, i: Int): Unit = set(s, props)(s.filters := s.filters.v.patch(i, Nil, 1))

def numeric(f: FieldDto): Boolean = f.fieldType == "int64" || f.fieldType == "float64"
def numFields(s: State) = s.fields.mapC(_.filter(numeric).filterNot(_.name == "value").map(_.name))
def textFields(s: State) = s.fields.mapC(_.filterNot(numeric).map(_.name))
def allFields(s: State) = s.fields.mapC(_.map(_.name))

/** 集計の選び方（データの種類ごと）. */
def measures(s: State) = s.st.mapC {
  case StreamType.Metrics => List("avg", "sum", "max", "min", "last", "rate", "p95", "p99")
  case _ => List("count", "avg", "sum", "max", "min", "p50", "p90", "p95", "p99")
}
def needField(s: State) = computed(s.st.v != StreamType.Metrics && s.measure.v != "count")
def isMetrics(s: State) = s.st.mapC(_ == StreamType.Metrics)
def notMetrics(s: State) = s.st.mapC(_ != StreamType.Metrics)
def showTop(s: State) = computed(s.group.v.nonEmpty)
/** メトリクスの候補: サービスを選んでいれば、そのサービスが送っているものだけ（添えるのは送り元のサービス）. */
def metricOptions(s: State) = computed {
  val svc = s.service.v
  val ms = s.metricServices.v
  s.streams.v.map(_.name).filter(n => svc.isEmpty || ms.getOrElse(n, Nil).contains(svc)).map(n => (n, ms.getOrElse(n, Nil).mkString(" · ")))
}
def metricHint(s: State) = computed {
  val n = metricOptions(s).v.size
  if (s.service.v.isEmpty) s"メトリクス（$n 件. 先にサービスを選ぶと絞り込めます）" else s"メトリクス（${s.service.v} の $n 件）"
}
// サービスを変えたら入力欄を作り直す（選んでいた値を消すため）
def metricKey(s: State) = computed(List(s"m-${s.service.v}"))
def valueKey(i: Int, field: String) = List(s"v-$i-$field")
def valueOptions(s: State, field: String) = s.values.mapC(_.getOrElse(field, Nil).map(v => (v, "")))

/** サービスを選ぶ. メトリクスなら、そのサービスにないメトリクスを選んでいたら外す. */
def setService(s: State, props: Props, v: String): Unit = {
  s.service := v
  if (s.st.v == StreamType.Metrics && v.nonEmpty && s.stream.v.nonEmpty && !s.metricServices.v.getOrElse(s.stream.v, Nil).contains(v)) {
    s.stream := ""; s.fields := Nil
  }
  build(s, props)
}
def indexed(s: State) = s.filters.mapC(_.zipWithIndex)
def types = List(StreamType.Logs, StreamType.Traces, StreamType.Metrics)
def sel(cur: RV[String], v: String) = cur.mapC(_ == v)
</script>

<template>
  <div class="pb">
    <div class="pb-grid">
      <label class="field"><span>データ</span>
        <select @change="setType(s, props, Dom.value(event))">
          <option v-for="t in types" :value="t" :selected="sel(s.st, t)">{{ StreamType.label(t) }}</option>
        </select></label>
      <label class="field"><span>サービス</span>
        <select @change="setService(s, props, Dom.value(event))">
          <option value="" :selected='sel(s.service, "")'>すべて</option>
          <option v-for="n in Store.serviceNames" :value="n" :selected="sel(s.service, n)">{{ n }}</option>
        </select></label>
      <label v-if="notMetrics(s)" class="field"><span>ストリーム</span>
        <select @change="setStream(s, props, Dom.value(event))">
          <option v-for="x in s.streams" :value="x.name" :selected="sel(s.stream, x.name)">{{ x.name }}</option>
        </select></label>
      <div v-if="isMetrics(s)" class="field grow"><span>{{ metricHint(s) }}</span>
        <Combo v-for="k in metricKey(s)" :key="k" :value="s.stream.v" :options="metricOptions(s)" placeholder="名前の一部を入れて絞り込めます"
          :onPick="v => setStream(s, props, v)" /></div>
    </div>

    <div class="pb-grid">
      <label class="field"><span>集計</span>
        <select @change="set(s, props)(s.measure := Dom.value(event))">
          <option v-for="m in measures(s)" :value="m" :selected="sel(s.measure, m)">{{ MetricAgg.label(m) }}</option>
        </select></label>
      <label v-if="needField(s)" class="field"><span>集計する列</span>
        <select @change="set(s, props)(s.field := Dom.value(event))">
          <option value="" :selected='sel(s.field, "")'>選んでください</option>
          <option v-for="f in numFields(s)" :value="f" :selected="sel(s.field, f)">{{ f }}</option>
        </select></label>
      <label class="field"><span>分ける（系列・行）</span>
        <select @change="set(s, props)(s.group := Dom.value(event))">
          <option value="" :selected='sel(s.group, "")'>分けない</option>
          <option v-for="f in textFields(s)" :value="f" :selected="sel(s.group, f)">{{ f }}</option>
        </select></label>
      <label v-if="showTop(s)" class="field"><span>上位</span>
        <select @change="set(s, props)(s.topN := Dom.value(event))">
          <option v-for='n in List("5", "10", "20", "50")' :value="n" :selected="sel(s.topN, n)">{{ n }}</option>
        </select></label>
    </div>

    <div class="field"><span>条件</span>
      <div v-for="fi in indexed(s)" class="pb-filter">
        <select @change="setFilter(s, props, fi._2, fi._1.copy(field = Dom.value(event)))">
          <option value="" :selected='fi._1.field.isEmpty'>列を選ぶ</option>
          <option v-for="f in allFields(s)" :value="f" :selected="fi._1.field == f">{{ f }}</option>
        </select>
        <select @change="setFilter(s, props, fi._2, fi._1.copy(op = Dom.value(event)))">
          <option v-for="o in PanelOp.all" :value="o" :selected="fi._1.op == o">{{ PanelOp.label(o) }}</option>
        </select>
        <div v-if='fi._1.op != "exists"' class="grow">
          <Combo v-for="k in valueKey(fi._2, fi._1.field)" :key="k" :value="fi._1.value" :options="valueOptions(s, fi._1.field)" placeholder="値（候補から選べます）"
            :onPick="v => setFilter(s, props, fi._2, fi._1.copy(value = v))" /></div>
        <button type="button" class="ghost small" title="この条件を外す" @click="removeFilter(s, props, fi._2)">×</button>
      </div>
      <button type="button" class="small" @click="addFilter(s, props)">＋ 条件</button>
    </div>

    <p v-if="s.error.mapC(_.nonEmpty)" class="error small">{{ s.error }}</p>
  </div>
</template>

<style scoped>
.pb { display: flex; flex-direction: column; gap: .4rem; }
.pb-grid { display: flex; flex-wrap: wrap; gap: .6rem; align-items: flex-end; }
.pb-grid .field { margin: 0; }
.pb-filter { display: flex; gap: .35rem; align-items: center; margin-bottom: .3rem; }
.pb-filter select { max-width: 14rem; }
</style>
