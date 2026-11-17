<script lang="scala">
import skorimako.web.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// サービスの絞り込み（service_name）. 選んだサービスは Store.service（画面をまたいで同じ. この端末に記憶する）.
// 各画面は Store.onReload で検索し直し、Store.serviceWhere で条件に足す.

case class State()

def initState() = State()

def mounted(s: State): Unit =
  if (Store.serviceNames.v.isEmpty) Api.serviceNames.foreach(ns => Store.serviceNames := (ns ++ Option(Store.service.v).filter(_.nonEmpty)).distinct.sorted)

def cls = Store.service.mapC(v => if (v.nonEmpty) "sp on" else "sp")
</script>

<template>
  <select :class="cls" title="サービスで絞り込む" @change="Store.setService(Dom.value(event))">
    <option value="" :selected='Store.service.mapC(_.isEmpty)'>すべてのサービス</option>
    <option v-for="n in Store.serviceNames" :value="n" :selected="Store.service.mapC(_ == n)">{{ n }}</option>
  </select>
</template>

<style scoped>
.sp { max-width: 14rem; }
.sp.on { border-color: var(--accent); color: var(--accent); font-weight: 600; }
</style>
