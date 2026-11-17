<script lang="scala">
import skorimako.web.*

import scala.scalajs.js

// 時間の範囲の選択（直近◯分・日時の指定）と、読み直しのボタン. 範囲は Store.range（画面をまたいで同じ）.
// 各画面は Store.range と Store.tick（読み直しの合図）を見張って検索し直す.

case class State(open: RV[Boolean] = RV(false), from: RV[String] = RV(""), to: RV[String] = RV(""))

def initState() = State()

/** datetime-local の値（ローカルの時刻）. */
def local(ms: Double): String = Fmt.ms(ms).take(16).replace(' ', 'T')

def openCustom(s: State): Unit = {
  val (a, b) = Store.range.v.micros
  s.from := local(a / 1000.0); s.to := local(b / 1000.0); s.open := !s.open.v
}

def applyCustom(s: State): Unit = {
  val a = new js.Date(s.from.v).getTime(); val b = new js.Date(s.to.v).getTime()
  if (a.isNaN || b.isNaN || a >= b) Store.toast("始まりと終わりを正しく入れてください", error = true)
  else { Store.setRange(TimeSel.custom(a, b)); s.open := false }
}

def pick(v: String): Unit = if (v != "custom") Store.setRange(TimeSel(v))

def preset = Store.range.mapC(_.preset)
def customLabel = Store.range.mapC(r => if (r.preset == "custom") r.label else "日時を指定…")
</script>

<template>
  <div class="tr">
    <select :value="preset" @change="pick(Dom.value(event))">
      <option v-for="p in TimeSel.presets" :value="p._1">{{ p._2 }}</option>
      <option value="custom">{{ customLabel }}</option>
    </select>
    <button type="button" class="ghost small" title="日時を指定" @click="openCustom(s)">📅</button>
    <button type="button" title="読み直す" @click="Store.refresh()">↻</button>
    <div v-if="s.open" class="pop">
      <label class="field"><span>始まり</span><input type="datetime-local" v-model="s.from"></label>
      <label class="field"><span>終わり</span><input type="datetime-local" v-model="s.to"></label>
      <div class="row"><button class="primary small" @click="applyCustom(s)">この範囲にする</button>
        <button class="small" @click="s.open := false">閉じる</button></div>
    </div>
  </div>
</template>

<style scoped>
.tr { display: inline-flex; gap: .3rem; align-items: center; position: relative; }
.pop { position: absolute; right: 0; top: 2.4rem; z-index: 300; background: var(--surface); border: 1px solid var(--line); border-radius: 10px;
  box-shadow: var(--shadow); padding: .8rem; width: 280px; }
</style>
