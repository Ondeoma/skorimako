import uPlot from 'uplot'

// 時系列のグラフ（uPlot）。Scala.js の画面（parts/Chart.st）から呼ぶ。
// spec: { kind: 'line' | 'bar', series: [{ label, points: [[ミリ秒, 値 | null], ...] }], unit, height, onSelect(startMs, endMs) }
// ドラッグで範囲を選ぶと onSelect を呼ぶ（時間の範囲を狭める）。幅は親の要素に合わせて変える。

const palette = ['#2563eb', '#16a34a', '#d97706', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#65a30d', '#ea580c', '#4f46e5',
  '#0d9488', '#b45309', '#9333ea', '#e11d48', '#0284c7']

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 数値の表示（大きい数は k・M・G、単位が By ならバイト）。 */
export function formatValue(v, unit) {
  if (v == null || Number.isNaN(v)) return '-'
  const a = Math.abs(v)
  if (unit === 'By' || unit === 'bytes') {
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    let i = 0; let x = v
    while (Math.abs(x) >= 1024 && i < units.length - 1) { x /= 1024; i++ }
    return `${x.toFixed(x >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
  }
  const suffix = unit && unit !== '1' ? ` ${unit}` : ''
  if (a >= 1e9) return (v / 1e9).toFixed(1) + 'G' + suffix
  if (a >= 1e6) return (v / 1e6).toFixed(1) + 'M' + suffix
  if (a >= 1e4) return (v / 1e3).toFixed(1) + 'k' + suffix
  if (a >= 100 || Number.isInteger(v)) return Math.round(v).toLocaleString() + suffix
  return Number(v.toPrecision(3)).toString() + suffix
}

/** 系列ごとの点を、共通の時刻の並びにそろえる（uPlot のデータの形）。 */
function align(series) {
  const ts = new Set()
  series.forEach(s => s.points.forEach(p => ts.add(p[0])))
  const xs = [...ts].sort((a, b) => a - b)
  const index = new Map(xs.map((t, i) => [t, i]))
  const ys = series.map(s => {
    const arr = new Array(xs.length).fill(null)
    s.points.forEach(p => { arr[index.get(p[0])] = p[1] })
    return arr
  })
  return [xs.map(t => t / 1000), ...ys]
}

function options(el, spec) {
  const fg = cssVar('--muted', '#64748b')
  const grid = cssVar('--line', '#e2e8f0')
  const bars = spec.kind === 'bar'
  const series = spec.series.map((s, i) => {
    const color = s.color || palette[i % palette.length]
    return {
      label: s.label || '値',
      stroke: color,
      width: bars ? 0 : 1.6,
      fill: bars ? color + 'cc' : (spec.series.length === 1 ? color + '22' : undefined),
      paths: bars ? uPlot.paths.bars({ size: [0.8, 40] }) : undefined,
      points: { show: false },
      spanGaps: false,
      value: (u, v) => formatValue(v, spec.unit),
    }
  })
  return {
    width: Math.max(200, el.clientWidth),
    height: spec.height || 220,
    legend: { show: spec.legend !== false && spec.series.length > 1 },
    cursor: { drag: { x: !!spec.onSelect, y: false, setScale: false } },
    scales: { x: { time: true } },
    axes: [
      { stroke: fg, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid } },
      { stroke: fg, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid }, size: 60, values: (u, vals) => vals.map(v => formatValue(v, spec.unit)) },
    ],
    series: [{}, ...series],
    hooks: {
      setSelect: [u => {
        if (!spec.onSelect || u.select.width < 4) return
        const a = u.posToVal(u.select.left, 'x') * 1000
        const b = u.posToVal(u.select.left + u.select.width, 'x') * 1000
        u.setSelect({ left: 0, width: 0 }, false)
        spec.onSelect(a, b)
      }],
    },
  }
}

export function renderChart(el, spec) {
  let plot = null
  let current = spec
  const draw = () => {
    if (plot) { plot.destroy(); plot = null }
    el.innerHTML = ''
    if (!current.series.length || current.series.every(s => !s.points.length)) {
      const p = document.createElement('div')
      p.className = 'chart-empty'
      p.textContent = current.emptyText || 'データがありません'
      p.style.height = (current.height || 220) + 'px'
      el.appendChild(p)
      return
    }
    plot = new uPlot(options(el, current), align(current.series), el)
  }
  draw()
  const ro = new ResizeObserver(() => { if (plot) plot.setSize({ width: Math.max(200, el.clientWidth), height: current.height || 220 }) })
  ro.observe(el)
  // ダークモードの切り替えで色を取り直す
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  const onTheme = () => draw()
  mq.addEventListener('change', onTheme)
  return {
    update(s) { current = s; draw() },
    destroy() { ro.disconnect(); mq.removeEventListener('change', onTheme); if (plot) plot.destroy(); el.innerHTML = '' },
  }
}
