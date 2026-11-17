<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.Json

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// トレースの詳細: スパンを親子の順に並べたウォーターフォール. スパンを選ぶとすべての属性とイベント.
case class Props(traceId: String)

/** 表示するスパン. depth は親子の深さ、left・width は全体に対する割合（%）. */
case class Span(id: String, parent: String, service: String, name: String, kind: String, start: Long, end: Long, status: String, depth: Int,
                left: Double, width: Double, row: Map[String, Json])

case class State(spans: RV[List[Span]] = RV(Nil), selected: RV[Option[Span]] = RV(None), error: RV[String] = RV(""),
                 total: RV[(Long, Long)] = RV((0L, 0L)))

def initState() = State()

val palette = List("#2563eb", "#16a34a", "#d97706", "#7c3aed", "#0891b2", "#db2777", "#65a30d", "#ea580c", "#4f46e5", "#0d9488")

def mounted(s: State, props: Props): Unit = {
  pageTitle("トレース " + props.traceId.take(8))
  Api.trace(props.traceId).onComplete {
    case Success(t) => build(s, t)
    case Failure(e) => s.error := e.getMessage
  }
}

def str(row: Map[String, Json], k: String): String = row.get(k).map(Fmt.cell).getOrElse("")
def lng(row: Map[String, Json], k: String): Long = row.get(k).flatMap(Rows.long).getOrElse(0L)

def build(s: State, t: TraceDto): Unit = {
  val rows = t.spans.flatMap(_.asObject).map(_.toMap)
  val raw = rows.map(r => Span(str(r, "span_id"), str(r, "parent_span_id"), str(r, "service_name"), str(r, "operation_name"), str(r, "span_kind"),
    lng(r, Columns.Timestamp), lng(r, "end_time"), str(r, "status_code"), 0, 0, 0, r))
  val t0 = raw.map(_.start).minOption.getOrElse(0L)
  val t1 = math.max(t0 + 1, raw.map(_.end).maxOption.getOrElse(t0 + 1))
  val ids = raw.map(_.id).toSet
  val children = raw.groupBy(x => if (ids.contains(x.parent)) x.parent else "")
  def walk(parent: String, depth: Int): List[Span] =
    children.getOrElse(parent, Nil).sortBy(_.start).flatMap { x =>
      val l = (x.start - t0).toDouble / (t1 - t0) * 100
      val w = math.max(0.4, (x.end - x.start).toDouble / (t1 - t0) * 100)
      x.copy(depth = depth, left = l, width = math.min(w, 100 - l)) :: walk(x.id, depth + 1)
    }
  val ordered = walk("", 0)
  s.total := (t0, t1)
  s.spans := ordered
  s.selected := ordered.headOption
}

def services(s: State) = s.spans.mapC(_.map(_.service).distinct)
def color(s: State, svc: String): String = { val i = s.spans.v.map(_.service).distinct.indexOf(svc); palette(math.max(0, i) % palette.size) }
def bar(s: State, x: Span): String =
  s"left:${x.left}%;width:${x.width}%;background:${if (x.status == "ERROR") "#dc2626" else color(s, x.service)}"
def indent(x: Span): String = s"padding-left:${x.depth * 14}px"
def rowCls(s: State, x: Span) = s.selected.mapC(sel => if (sel.exists(_.id == x.id)) "srow sel" else "srow")
def duration(s: State) = s.total.mapC((a, b) => Fmt.duration(b - a))
def startAt(s: State) = s.total.mapC((a, _) => if (a == 0) "" else Fmt.micros(a))
def entries(x: Span): List[(String, String)] =
  x.row.toList.filter((k, v) => !v.isNull && !Set("events", "links").contains(k)).map((k, v) => k -> Fmt.cell(v)).sortBy(_._1)
def events(x: Span): List[(String, String, String)] =
  x.row.get("events").flatMap(j => io.circe.parser.parse(Fmt.cell(j)).toOption).flatMap(_.asArray).toList.flatten.map { e =>
    val c = e.hcursor
    (c.get[Long]("time").toOption.map(t => "+" + Fmt.duration(t - x.start)).getOrElse(""), c.get[String]("name").getOrElse(""),
      c.downField("attributes").focus.map(_.noSpaces).getOrElse(""))
  }
def logsLink(props: Props): String = "/logs?where=" + Api.enc(s"trace_id = '${props.traceId}'")
def svcStyle(s: State, svc: String): String = s"background:${color(s, svc)}"
</script>

<template>
  <div class="page wide">
    <p class="small"><a href="/traces" @click.prevent='Store.go("/traces")'>← トレース</a></p>
    <h1 class="mono tid">{{ props.traceId }}</h1>
    <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
    <div class="row meta">
      <span>開始 <b>{{ startAt(s) }}</b></span><span>所要時間 <b>{{ duration(s) }}</b></span><span>スパン <b>{{ s.spans.mapC(_.size.toString) }}</b></span>
      <span v-for="svc in services(s)" class="svc"><i :style="svcStyle(s, svc)"></i>{{ svc }}</span>
      <a class="btn small right" :href="logsLink(props)" @click.prevent="Store.go(logsLink(props))">📜 このトレースのログ</a>
    </div>
    <div class="wf">
      <div v-for="x in s.spans" :key="x.id" :class="rowCls(s, x)" @click="s.selected := Some(x)">
        <div class="label" :style="indent(x)" :title="x.name"><span class="svcname">{{ x.service }}</span> {{ x.name }}</div>
        <div class="track"><div class="bar" :style="bar(s, x)"></div><span class="dur">{{ Fmt.duration(x.end - x.start) }}</span></div>
      </div>
    </div>
    <div v-for="x in s.selected.mapC(_.toList)" class="panel detail">
      <h3>{{ x.name }} <span class="muted small">{{ x.kind }}・{{ Fmt.duration(x.end - x.start) }}</span>
        <span v-if='x.status == "ERROR"' class="error small">⚠ エラー</span></h3>
      <table class="kv">
        <tr v-for="kv in entries(x)"><th>{{ kv._1 }}</th><td class="mono">{{ kv._2 }}</td></tr>
      </table>
      <template v-if="events(x).nonEmpty">
        <h3>イベント</h3>
        <table class="kv"><tr v-for="e in events(x)"><th>{{ e._1 }} {{ e._2 }}</th><td class="mono">{{ e._3 }}</td></tr></table>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tid { font-size: 1.05rem; word-break: break-all; }
.meta { gap: 1rem; margin-bottom: .8rem; font-size: .88rem; }
.svc { display: inline-flex; align-items: center; gap: .3rem; font-size: .8rem; }
.svc i { width: .7rem; height: .7rem; border-radius: 2px; display: inline-block; }
.wf { border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.srow { display: grid; grid-template-columns: minmax(180px, 34%) 1fr; border-bottom: 1px solid var(--line); cursor: pointer; font-size: .82rem; }
.srow:hover { background: var(--hover); }
.srow.sel { background: var(--accent-weak); }
.label { padding: .25rem .5rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; border-right: 1px solid var(--line); }
.svcname { color: var(--muted); font-size: .75rem; }
.track { position: relative; height: 1.7rem; }
.bar { position: absolute; top: .45rem; height: .8rem; border-radius: 3px; min-width: 2px; }
.dur { position: absolute; right: .4rem; top: .25rem; font-size: .72rem; color: var(--muted); }
.detail { margin-top: 1rem; }
.kv { width: 100%; border-collapse: collapse; }
.kv th { text-align: left; font-size: .78rem; color: var(--muted); padding: .15rem .5rem; width: 240px; vertical-align: top; word-break: break-all; font-weight: 500; }
.kv td { font-size: .8rem; padding: .15rem .5rem; word-break: break-all; white-space: pre-wrap; }
</style>
