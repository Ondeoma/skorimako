<script lang="scala">
import skorimako.web.*
import skorimako.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ストリームの一覧（種類・行数・保存量・時刻の範囲・保持期間）
case class State(list: RV[List[StreamDto]] = RV(Nil), t: RV[String] = RV(""), q: RV[String] = RV(""))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("ストリーム"); Store.report(Api.streams())(s.list := _) }

def shown(s: State) = computed { val q = s.q.v.trim.toLowerCase; s.list.v.filter(x => (s.t.v.isEmpty || x.streamType == s.t.v) && (q.isEmpty || x.name.contains(q))) }
def total(s: State) = shown(s).mapC(xs => s"${xs.size} 件・${Fmt.count(xs.map(_.docCount).sum)} 行・${Fmt.bytes(xs.map(_.storedBytes).sum)}")
def range(x: StreamDto): String = (x.minTs, x.maxTs) match {
  case (Some(a), Some(b)) => s"${Fmt.micros(a).take(16)} 〜 ${Fmt.micros(b).take(16)}"
  case _ => "-"
}
def chip(s: State, v: String) = s.t.mapC(x => if (x == v) "chip active" else "chip")
</script>

<template>
  <div class="page">
    <h1>ストリーム</h1>
    <div class="toolbar">
      <button :class='chip(s, "")' @click='s.t := ""'>すべて</button>
      <button v-for="t in StreamType.all" :class="chip(s, t)" @click="s.t := t">{{ StreamType.label(t) }}</button>
      <input type="search" v-model="s.q" placeholder="名前で絞る" class="grow">
    </div>
    <p class="muted small">{{ total(s) }}</p>
    <div class="table-wrap">
      <table class="list nowrap">
        <thead><tr><th>種類</th><th>名前</th><th>行数</th><th>ファイル</th><th>保存量</th><th>データの範囲</th><th>保持</th></tr></thead>
        <tbody>
          <tr v-for="x in shown(s)" :key="x.id" class="clickable" @click='Store.go(s"/streams/${x.streamType}/${x.name}")'>
            <td>{{ StreamType.label(x.streamType) }}</td><td class="mono">{{ x.name }}</td><td>{{ Fmt.count(x.docCount) }}</td>
            <td>{{ x.fileCount }}</td><td>{{ Fmt.bytes(x.storedBytes) }}</td><td class="small nowrap">{{ range(x) }}</td><td>{{ x.retentionDays }}日</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
