<script lang="scala">
import skorimako.web.*

import org.scalajs.dom

// 入力欄と、そのすぐ下に出す候補の一覧（ブラウザの datalist はモーダルの中で位置がずれることがあるため、自前で描く）.
// 入れた文字を含む候補に絞る. 候補を押すか Enter で onPick を呼ぶ. options は（値, 添える説明）.
case class Props(value: String, options: CRV[List[(String, String)]], placeholder: String = "", onPick: String => Unit)

case class State(q: RV[String] = RV(""), open: RV[Boolean] = RV(false), active: RV[Int] = RV(0))

def initState() = State()

def mounted(s: State, props: Props): Unit = s.q := props.value

def matches(s: State, props: Props) = computed {
  val q = s.q.v.trim.toLowerCase
  val all = props.options.v
  // 選んだ値と同じ文字のままなら、ほかの候補も見られるようにすべて出す
  (if (q.isEmpty || q == props.value.toLowerCase) all else all.filter((v, _) => v.toLowerCase.contains(q))).take(200)
}

def pick(s: State, props: Props, v: String): Unit = { s.q := v; s.open := false; props.onPick(v) }

def key(s: State, props: Props, ev: dom.Event): Unit = { val e = ev.asInstanceOf[dom.KeyboardEvent]; e.key match {
  case "ArrowDown" => e.preventDefault(); s.open := true; s.active := math.min(s.active.v + 1, matches(s, props).v.size - 1)
  case "ArrowUp"   => e.preventDefault(); s.active := math.max(s.active.v - 1, 0)
  case "Enter"     =>
    e.preventDefault()
    val ms = matches(s, props).v
    pick(s, props, if (s.open.v && ms.nonEmpty) ms(math.min(s.active.v, ms.size - 1))._1 else s.q.v.trim)
  case "Escape"    => if (s.open.v) { e.preventDefault(); s.open := false }
  case "Tab"       => s.open := false
  case _ => ()
} }

def typed(s: State, v: String): Unit = { s.q := v; s.open := true; s.active := 0 }
def itemCls(s: State, i: Int) = s.active.mapC(a => if (a == i) "cb-item on" else "cb-item")
def indexed(s: State, props: Props) = computed(matches(s, props).v.zipWithIndex)
def showList(s: State, props: Props) = computed(s.open.v && matches(s, props).v.nonEmpty)
</script>

<template>
  <div class="cb" v-click-outside="s.open := false">
    <input type="search" :value="s.q" :placeholder="props.placeholder" autocomplete="off"
      @input="typed(s, Dom.value(event))" @focus="s.open := true" @keydown="key(s, props, event)">
    <div v-if="showList(s, props)" class="cb-list">
      <div v-for="oi in indexed(s, props)" :class="itemCls(s, oi._2)" @mousedown.prevent="pick(s, props, oi._1._1)">
        <span class="cb-v">{{ oi._1._1 }}</span><span v-if="oi._1._2.nonEmpty" class="cb-l">{{ oi._1._2 }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cb { position: relative; min-width: 12rem; }
.cb input { width: 100%; }
.cb-list { position: absolute; left: 0; right: 0; top: calc(100% + 2px); z-index: 1500; max-height: 16rem; overflow: auto;
  background: var(--surface); border: 1px solid var(--line); border-radius: 8px; box-shadow: var(--shadow); padding: .2rem; }
.cb-item { display: flex; justify-content: space-between; gap: .6rem; padding: .3rem .5rem; border-radius: 5px; cursor: pointer; font-size: .85rem; }
.cb-item:hover, .cb-item.on { background: var(--hover); }
.cb-v { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cb-l { color: var(--muted); font-size: .75rem; white-space: nowrap; }
</style>
