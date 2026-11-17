import './style.css'
import 'uplot/dist/uPlot.min.css'
import { renderChart, formatValue } from './src/chart.js'

// Scala.js の画面から使うグラフ（uPlot）
window.skoChart = { render: renderChart }
window.skoChartFormat = formatValue

import 'scalajs:main.js'
