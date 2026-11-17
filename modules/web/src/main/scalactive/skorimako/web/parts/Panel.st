<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.Json

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ダッシュボードのパネル. SQL を実行して、折れ線・棒（1列目が時刻、残りの数値の列が系列. 文字列の列があれば値ごとに系列を分ける）・数値・表で描く.
case class Props(panel: PanelDto)

case class State(chart: RV[ChartSpec] = RV(ChartSpec("line", Nil, height = 200)), result: RV[Option[QueryResultDto]] = RV(None),
                 error: RV[String] = RV(""), loading: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State, props: Props): Unit = { run(s, props); Store.onReload(run(s, props)) }

def numeric(c: ColumnDto): Boolean = Set("BIGINT", "INTEGER", "DOUBLE", "FLOAT", "HUGEINT", "DECIMAL", "SMALLINT", "UBIGINT").exists(c.columnType.toUpperCase.startsWith)

def run(s: State, props: Props): Unit = {
  val (start, end) = Store.range.v.micros
  s.loading := true
  Api.sql(SqlQueryReq(props.panel.streamType, props.panel.sql, start, end, Some(5000))).onComplete {
    case Success(r) => s.loading := false; s.error := ""; s.result := Some(r); if (isChart(props)) s.chart := toChart(r, props)
    case Failure(e) => s.loading := false; s.error := e.getMessage
  }
}

def isChart(props: Props): Boolean = props.panel.kind == PanelKind.Line || props.panel.kind == PanelKind.Bar

def toChart(r: QueryResultDto, props: Props): ChartSpec = {
  val cols = r.columns.zipWithIndex
  val rest = cols.drop(1)
  val nums = rest.filter(c => numeric(c._1))
  val labels = rest.filterNot(c => numeric(c._1))
  val groups = r.rows.groupBy(row => labels.map(l => Fmt.cell(row(l._2))).mkString(" / ")).toList.sortBy(_._1)
  val series = groups.flatMap { (g, rows) =>
    nums.map { (c, i) =>
      val name = if (g.isEmpty) c.name else if (nums.size == 1) g else s"$g / ${c.name}"
      ChartSeries(name, rows.flatMap(row => Rows.long(row.head).map(t => (t / 1000.0, Rows.num(row(i))))).sortBy(_._1))
    }
  }.take(50)
  ChartSpec(props.panel.kind, series, unit = props.panel.unit, height = 200, onSelect = Some((a, b) => Store.setRange(TimeSel.custom(a, b))))
}

def stat(s: State, props: Props) = s.result.mapC(_.flatMap(r => r.rows.headOption.flatMap(_.flatMap(Rows.num).headOption))
  .map(v => Fmt.num(v, props.panel.unit)).getOrElse("-"))
def is(props: Props, k: String): Boolean = props.panel.kind == k
</script>

<template>
  <div class="pbody">
    <p v-if="s.error.mapC(_.nonEmpty)" class="error mono small">{{ s.error }}</p>
    <Chart v-if="isChart(props)" :spec="s.chart" />
    <div v-if='is(props, "stat")' class="stat">{{ stat(s, props) }}</div>
    <div v-if='is(props, "table")' class="tbl">
      <ResultTable v-for="r in s.result.mapC(_.toList)" :result="r" :maxCell="120" />
    </div>
  </div>
</template>

<style scoped>
.pbody { min-height: 120px; }
.stat { font-size: 2.6rem; font-weight: 700; text-align: center; padding: 1.4rem 0; }
.tbl { max-height: 320px; overflow: auto; }
</style>
