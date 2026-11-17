<script lang="scala">
import skorimako.web.*
import skorimako.shared.*
import io.circe.Json

// 検索の結果の表. _timestamp・時刻らしい列は日時にする. 長い値は切る（クリックで全体）.
case class Props(result: QueryResultDto, maxCell: Int = 200)

def isTime(name: String): Boolean = name == Columns.Timestamp || name == "end_time"

def show(c: ColumnDto, j: Json, props: Props): String =
  if (isTime(c.name)) Rows.long(j).map(Fmt.micros).getOrElse(Fmt.cell(j))
  else { val t = Fmt.cell(j); if (t.length > props.maxCell) t.take(props.maxCell) + "…" else t }

def cells(props: Props, row: List[Json]): List[(ColumnDto, Json)] = props.result.columns.zip(row)
def numeric(c: ColumnDto): Boolean = Set("BIGINT", "INTEGER", "DOUBLE", "FLOAT", "HUGEINT", "DECIMAL", "SMALLINT").exists(c.columnType.toUpperCase.startsWith)
def cls(c: ColumnDto): String = if (numeric(c) && !isTime(c.name)) "num" else if (isTime(c.name)) "nowrap" else ""
</script>

<template>
  <div class="table-wrap">
    <table class="list">
      <thead><tr><th v-for="c in props.result.columns">{{ c.name }}</th></tr></thead>
      <tbody>
        <tr v-for="row in props.result.rows">
          <td v-for="cj in cells(props, row)" :class="cls(cj._1)" :title="Fmt.cell(cj._2).take(2000)">{{ show(cj._1, cj._2, props) }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="props.result.rows.isEmpty" class="empty">結果はありません</p>
  </div>
</template>

<style scoped>
td { font-size: .82rem; max-width: 520px; overflow: hidden; text-overflow: ellipsis; }
td.num { text-align: right; font-variant-numeric: tabular-nums; }
</style>
