import './style.css'
import 'uplot/dist/uPlot.min.css'
import uPlot from 'uplot'

// Scala.js の画面（ChartFacade）から使うグラフのライブラリ
window.uPlot = uPlot

import 'scalajs:main.js'
