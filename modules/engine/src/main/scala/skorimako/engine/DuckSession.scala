package skorimako.engine

import io.circe.Json
import skorimako.core.*
import skorimako.core.SqlText.ident
import skorimako.shared.*

import java.nio.file.Path
import java.sql.{Connection, ResultSet, Statement}

/**
 * 1回の検索のための DuckDB の接続。ストリームを同じ名前のビュー（時間の範囲で絞ったもの）として作ってから、
 * 外部のファイルへのアクセスを閉じ（ビューが読むファイルだけ許す）、設定を固定する。
 */
final class DuckSession private (val conn: Connection) {
  @volatile private var current: Option[Statement] = None
  @volatile private var cancelled = false

  /** 実行中の文を止める（時間切れ）。 */
  def cancel(): Unit = {
    cancelled = true
    current.foreach(s => scala.util.Try(s.cancel()))
  }

  def isCancelled: Boolean = cancelled

  /** SQL を実行し、limit 行まで読む。 */
  def query(sql: String, limit: Int): QueryResultDto = {
    val t0 = System.nanoTime()
    val s = conn.createStatement()
    current = Some(s)
    try {
      val rs = s.executeQuery(sql)
      val md = rs.getMetaData
      val n = md.getColumnCount
      val cols = (1 to n).map(i => ColumnDto(md.getColumnLabel(i), md.getColumnTypeName(i))).toList
      val rows = List.newBuilder[List[Json]]
      var count = 0
      var more = false
      while !more && rs.next() do
        if count >= limit then more = true
        else {
          rows += (1 to n).map(i => Duck.cell(rs, i)).toList
          count += 1
        }
      rs.close()
      QueryResultDto(cols, rows.result(), (System.nanoTime() - t0) / 1000000, 0, 0, more)
    } finally {
      current = None
      s.close()
    }
  }

  /** SQL を実行し、1行ずつ f に渡す（大きな結果を JSON にせずに読む）。 */
  def scan(sql: String)(f: ResultSet => Unit): Unit = {
    val s = conn.createStatement()
    current = Some(s)
    try {
      val rs = s.executeQuery(sql)
      while rs.next() do f(rs)
      rs.close()
    } finally {
      current = None
      s.close()
    }
  }

  def close(): Unit = conn.close()
}

object DuckSession {

  /** 1つのストリームのビュー。files は手元のキャッシュのパス、fields は登録済みの列（ファイルがない列も null で見えるように）。 */
  final case class View(name: String, files: Seq[Path], fields: List[FieldDto])

  def open(cfg: DuckConfig, views: List[View], start: Long, end: Long): DuckSession = {
    val c = Duck.open(cfg, stream = true)
    try {
      val s = c.createStatement()
      try {
        views.foreach { v =>
          val typed = (FieldDto(Columns.Timestamp, FieldType.Int64) :: v.fields.filter(_.name != Columns.Timestamp))
            .map(f => s"CAST(NULL AS ${FieldTypes.sqlType(f.fieldType)}) AS ${ident(f.name)}").mkString(", ")
          val empty = s"SELECT $typed WHERE false"
          val ts = ident(Columns.Timestamp)
          val body =
            if v.files.isEmpty then empty
            else
              s"""SELECT * FROM (SELECT * FROM read_parquet(${Duck.pathList(
                  v.files
                )}, union_by_name = true) UNION ALL BY NAME $empty)
                 |WHERE $ts >= $start AND $ts <= $end""".stripMargin
          s.execute(s"CREATE VIEW ${ident(v.name)} AS $body")
        }
        // ここから先は利用者の SQL。ビューが読むファイルのほかには触れさせない
        // （キャッシュのディレクトリごと許すと、read_parquet で見る権限のないストリームのファイルまで読めてしまう）
        s.execute(s"SET allowed_paths = ${Duck.pathList(views.flatMap(_.files).distinct)}")
        s.execute("SET enable_external_access = false")
        s.execute("SET lock_configuration = true")
      } finally s.close()
      new DuckSession(c)
    } catch {
      case e: Throwable =>
        c.close()
        throw e
    }
  }
}
