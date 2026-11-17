/*!
 * skorimako RUM（フロントエンドの観測）SDK. MIT License.
 * 読み込み方:
 *   <script src="https://skorimako.example.com/rum.js" data-token="rum_…" data-endpoint="https://skorimako.example.com" defer></script>
 * 送るもの: ページの表示（読み込みの時間・Web Vitals: LCP・FCP・CLS・INP・TTFB）、JavaScript のエラー・Promise の拒否、
 * fetch・XMLHttpRequest の失敗（HTTP 400 以上）、window.skorimako.track(name, attrs) で送る独自のイベント。
 * 利用者の識別: window.skorimako.setUser(id)、共通の属性: window.skorimako.setContext({ version: '1.2.3' })。
 */
(function () {
  'use strict'
  var script = document.currentScript
  if (!script || window.skorimako) return
  var token = script.getAttribute('data-token')
  var endpoint = (script.getAttribute('data-endpoint') || new URL(script.src).origin).replace(/\/$/, '')
  var sampleRate = parseFloat(script.getAttribute('data-sample-rate') || '1')
  if (!token) return

  function rid() { return Math.random().toString(36).slice(2, 10) + Date.now().toString(36) }
  function store(k, f) { try { var v = sessionStorage.getItem(k); if (!v) { v = f(); sessionStorage.setItem(k, v) } return v } catch (e) { return f() } }

  // セッションは同じタブの間だけ（30分操作がなければ新しくする）
  var session = (function () {
    try {
      var now = Date.now(), last = +(sessionStorage.getItem('sko.rum.last') || 0)
      if (now - last > 30 * 60 * 1000) sessionStorage.removeItem('sko.rum.session')
      sessionStorage.setItem('sko.rum.last', String(now))
    } catch (e) { }
    return store('sko.rum.session', rid)
  })()
  var sampled = store('sko.rum.sampled', function () { return Math.random() < sampleRate ? '1' : '0' }) === '1'
  var ctx = { session_id: session }
  var view = rid()
  var queue = []
  var timer = null

  function base() {
    return {
      view_id: view, url: location.href.slice(0, 1000), path: location.pathname, referrer: document.referrer.slice(0, 500), title: document.title.slice(0, 200),
      viewport: innerWidth + 'x' + innerHeight, language: navigator.language
    }
  }

  function send(beacon) {
    timer = null
    if (!queue.length || !sampled) { queue = []; return }
    var body = JSON.stringify({ events: queue.splice(0, 100), ctx: ctx })
    var url = endpoint + '/rum/v1/' + encodeURIComponent(token)
    // ページを離れるときは sendBeacon（text/plain なので CORS の事前確認が要らない）
    if (beacon && navigator.sendBeacon && navigator.sendBeacon(url, body)) return
    try { origFetch.call(window, url, { method: 'POST', body: body, keepalive: true, mode: 'cors', credentials: 'omit', headers: { 'Content-Type': 'text/plain' } }).catch(function () { }) } catch (e) { }
    if (queue.length) schedule()
  }

  function schedule() { if (!timer) timer = setTimeout(function () { send(false) }, 5000) }

  function push(type, attrs) {
    var e = base()
    e.type = type
    e.ts = Date.now()
    for (var k in attrs) if (Object.prototype.hasOwnProperty.call(attrs, k)) e[k] = attrs[k]
    queue.push(e)
    if (queue.length >= 50) send(false); else schedule()
  }

  // ---------------------------------------------------------------- 表示と Web Vitals

  var vitals = {}
  function nav() { return (performance.getEntriesByType && performance.getEntriesByType('navigation')[0]) || null }

  function observe(type, cb) {
    try { var po = new PerformanceObserver(function (l) { l.getEntries().forEach(cb) }); po.observe({ type: type, buffered: true }) } catch (e) { }
  }
  observe('paint', function (e) { if (e.name === 'first-contentful-paint') vitals.fcp = Math.round(e.startTime) })
  observe('largest-contentful-paint', function (e) { vitals.lcp = Math.round(e.startTime) })
  var cls = 0
  observe('layout-shift', function (e) { if (!e.hadRecentInput) { cls += e.value; vitals.cls = Math.round(cls * 1000) / 1000 } })
  observe('event', function (e) { if (e.interactionId && (!vitals.inp || e.duration > vitals.inp)) vitals.inp = Math.round(e.duration) })

  var viewSent = false
  function sendView() {
    if (viewSent) return
    viewSent = true
    var n = nav()
    var a = {}
    if (n) {
      a.ttfb = Math.round(n.responseStart)
      a.dom_content_loaded = Math.round(n.domContentLoadedEventEnd)
      a.load_time = Math.round(n.loadEventEnd || n.domComplete)
      a.transfer_size = n.transferSize
      a.navigation_type = n.type
    }
    for (var k in vitals) a[k] = vitals[k]
    push('view', a)
  }

  // 表示の情報はページを離れるとき（または隠れたとき）に送る（LCP・CLS・INP が確定するため）
  addEventListener('visibilitychange', function () { if (document.visibilityState === 'hidden') { sendView(); send(true) } })
  addEventListener('pagehide', function () { sendView(); send(true) })

  // SPA の画面の切り替え（history.pushState）は新しい表示として数える
  function routeChanged() {
    sendView()
    view = rid(); viewSent = false; vitals = {}; cls = 0
    push('route', {})
  }
  ;['pushState', 'replaceState'].forEach(function (m) {
    var orig = history[m]
    history[m] = function () { var before = location.pathname; var r = orig.apply(this, arguments); if (location.pathname !== before && m === 'pushState') routeChanged(); return r }
  })
  addEventListener('popstate', routeChanged)

  // ---------------------------------------------------------------- エラー

  addEventListener('error', function (e) {
    if (e.target && e.target !== window && e.target.tagName) {
      push('error', { error_type: 'resource', message: (e.target.tagName + ' ' + (e.target.src || e.target.href || '')).slice(0, 1000) })
      return
    }
    push('error', { error_type: (e.error && e.error.name) || 'Error', message: String(e.message).slice(0, 2000), source: e.filename, line: e.lineno, column: e.colno,
      stack: e.error && e.error.stack ? String(e.error.stack).slice(0, 8000) : undefined })
  }, true)
  addEventListener('unhandledrejection', function (e) {
    var r = e.reason
    push('error', { error_type: 'unhandledrejection', message: String((r && r.message) || r).slice(0, 2000), stack: r && r.stack ? String(r.stack).slice(0, 8000) : undefined })
  })

  // ---------------------------------------------------------------- 通信の失敗

  var origFetch = window.fetch
  if (origFetch) window.fetch = function (input, init) {
    var start = Date.now()
    var url = typeof input === 'string' ? input : (input && input.url) || ''
    var method = (init && init.method) || (input && input.method) || 'GET'
    return origFetch.apply(this, arguments).then(function (res) {
      if (res.status >= 400 && url.indexOf('/rum/v1/') < 0) push('resource_error', { method: method, request_url: url.slice(0, 1000), status: res.status, duration: Date.now() - start })
      return res
    }, function (err) {
      if (url.indexOf('/rum/v1/') < 0) push('resource_error', { method: method, request_url: url.slice(0, 1000), status: 0, message: String(err).slice(0, 500), duration: Date.now() - start })
      throw err
    })
  }
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send
  XMLHttpRequest.prototype.open = function (m, u) { this.__sko = { method: m, url: String(u) }; return XO.apply(this, arguments) }
  XMLHttpRequest.prototype.send = function () {
    var x = this, start = Date.now()
    x.addEventListener('loadend', function () {
      if (x.__sko && (x.status >= 400 || x.status === 0)) push('resource_error', { method: x.__sko.method, request_url: x.__sko.url.slice(0, 1000), status: x.status, duration: Date.now() - start })
    })
    return XS.apply(this, arguments)
  }

  // ---------------------------------------------------------------- 公開の API

  window.skorimako = {
    track: function (name, attrs) { var a = attrs || {}; a.name = String(name).slice(0, 200); push('custom', a) },
    setUser: function (id) { ctx.user_id = id == null ? undefined : String(id) },
    setContext: function (o) { for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ctx[k] = o[k] },
    flush: function () { send(false) }
  }
})()
