package skorimako.web

final case class ChartSeries(label: String, points: List[(Double, Option[Double])], color: String = "")

/** グラフの内容. kind は line か bar. points の時刻はミリ秒. onSelect はドラッグで選んだ範囲（ミリ秒）. */
final case class ChartSpec(kind: String,
                           series: List[ChartSeries],
                           unit: String = "",
                           height: Int = 220,
                           onSelect: Option[(Double, Double) => Unit] = None,
                           emptyText: String = "データがありません",
                           legend: Boolean = true)
