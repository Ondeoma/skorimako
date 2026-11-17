package skorimako.engine

import io.circe.Json
import skorimako.core.*
import skorimako.core.SqlText.{ident, lit}
import skorimako.shared.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, ResultSet, Statement}
import java.util.Properties

/** DuckDB の設定（検索ごとのメモリ・スレッド・一時ファイル）。 */
final case class DuckConfig(memoryLimit: String = "1GB", threads: Int = 4, tempDir: Path)

/** DuckDB（組み込み）の使い方をまとめる。接続はメモリ上の DB で、使うたびに作って捨てる。 */
object Duck:

  Class.forName("org.duckdb.DuckDBDriver")

  def open(cfg: DuckConfig, stream: Boolean = false): Connection =
    val p = new Properties()
    // 結果を少しずつ受け取る（上限の行数で読むのをやめたとき、残りを作らない）
    if stream then p.setProperty("jdbc_stream_results", "true")
    val c = DriverManager.getConnection("jdbc:duckdb:", p)
    val s = c.createStatement()
    try
      Files.createDirectories(cfg.tempDir)
      s.execute(s"SET memory_limit = ${lit(cfg.memoryLimit)}")
      s.execute(s"SET threads = ${math.max(1, cfg.threads)}")
      s.execute(s"SET temp_directory = ${lit(cfg.tempDir.toString)}")
      s.execute("SET autoinstall_known_extensions = false")
      s.execute("SET autoload_known_extensions = false")
    finally s.close()
    c

  def using[A](c: Connection)(f: Connection => A): A =
    try f(c) finally c.close()

  /** パスの一覧を DuckDB のリストの書き方にする。 */
  def pathList(ps: Seq[Path]): String = ps.map(p => lit(p.toString)).mkString("[", ", ", "]")

  // ---------------------------------------------------------------- 書き出し

  /**
   * 行を Parquet に書く。types は列ごとの型（値はこの型に直す。直せない値は null）。
   * いったん NDJSON に書き、DuckDB の read_json で型を決めて読み、時刻の順に並べて書き出す。
   */
  def writeParquet(cfg: DuckConfig, rows: Seq[Row], types: Map[String, String], out: Path): Unit =
    Files.createDirectories(cfg.tempDir)
    val tmp = Files.createTempFile(cfg.tempDir, "rows-", ".ndjson")
    try
      val w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)
      try rows.foreach { r =>
        val fs = r.fields.iterator.flatMap((k, v) => types.get(k).flatMap(v.coerce).map(x => k -> x.toJson)).toList
        w.write(Json.fromFields((Columns.Timestamp -> Json.fromLong(r.ts)) :: fs).noSpaces)
        w.write('\n')
      } finally w.close()
      val cols = ((Columns.Timestamp -> "BIGINT") :: types.toList.filter(_._1 != Columns.Timestamp).sortBy(_._1).map((k, t) => k -> FieldTypes.sqlType(t)))
        .map((k, t) => s"${lit(k)}: ${lit(t)}").mkString("{", ", ", "}")
      using(open(cfg)) { c =>
        val s = c.createStatement()
        try s.execute(
          s"""COPY (SELECT * FROM read_json(${lit(tmp.toString)}, format = 'newline_delimited', columns = $cols)
             |ORDER BY ${ident(Columns.Timestamp)}) TO ${lit(out.toString)} (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE 122880)""".stripMargin)
        finally s.close()
      }
    finally Files.deleteIfExists(tmp)

  /** いくつかの Parquet を1つにまとめる（圧縮）。(行数, 最小の時刻, 最大の時刻)。 */
  def mergeParquet(cfg: DuckConfig, files: Seq[Path], out: Path): (Long, Long, Long) =
    using(open(cfg)) { c =>
      val s = c.createStatement()
      try
        s.execute(s"""COPY (SELECT * FROM read_parquet(${pathList(files)}, union_by_name = true) ORDER BY ${ident(Columns.Timestamp)})
                     |TO ${lit(out.toString)} (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE 122880)""".stripMargin)
        val rs = s.executeQuery(s"SELECT count(*), min(${ident(Columns.Timestamp)}), max(${ident(Columns.Timestamp)}) FROM read_parquet(${lit(out.toString)})")
        rs.next()
        (rs.getLong(1), rs.getLong(2), rs.getLong(3))
      finally s.close()
    }

  // ---------------------------------------------------------------- 検索

  /** 結果の値を JSON に。 */
  def cell(rs: ResultSet, i: Int): Json =
    rs.getObject(i) match
      case null                     => Json.Null
      case v: java.lang.Long        => Json.fromLong(v)
      case v: java.lang.Integer     => Json.fromInt(v)
      case v: java.lang.Short       => Json.fromInt(v.toInt)
      case v: java.lang.Byte        => Json.fromInt(v.toInt)
      case v: java.lang.Double      => Json.fromDoubleOrNull(v)
      case v: java.lang.Float       => Json.fromDoubleOrNull(v.toDouble)
      case v: java.math.BigDecimal  => Json.fromBigDecimal(BigDecimal(v))
      case v: java.math.BigInteger  => Json.fromBigInt(BigInt(v))
      case v: java.lang.Boolean     => Json.fromBoolean(v)
      case v: String                => Json.fromString(v)
      case v: java.sql.Timestamp    => Json.fromString(v.toInstant.toString)
      case v: java.time.OffsetDateTime => Json.fromString(v.toInstant.toString)
      case v: java.time.LocalDateTime  => Json.fromString(v.toString)
      case v: java.sql.Array =>
        val arr = v.getArray.asInstanceOf[Array[AnyRef]]
        Json.fromString(arr.map(x => String.valueOf(x)).mkString("[", ", ", "]"))
      case v => Json.fromString(v.toString)

/**
 * 1回の検索のための DuckDB の接続。ストリームを同じ名前のビュー（時間の範囲で絞ったもの）として作ってから、
 * 外部のファイルへのアクセスを閉じ（キャッシュのディレクトリだけ許す）、設定を固定する。
 */
final class DuckSession private (val conn: Connection):
  @volatile private var current: Option[Statement] = None
  @volatile private var cancelled = false

  /** 実行中の文を止める（時間切れ）。 */
  def cancel(): Unit =
    cancelled = true
    current.foreach(s => scala.util.Try(s.cancel()))

  def isCancelled: Boolean = cancelled

  /** SQL を実行し、limit 行まで読む。 */
  def query(sql: String, limit: Int): QueryResultDto =
    val t0 = System.nanoTime()
    val s  = conn.createStatement()
    current = Some(s)
    try
      val rs = s.executeQuery(sql)
      val md = rs.getMetaData
      val n  = md.getColumnCount
      val cols = (1 to n).map(i => ColumnDto(md.getColumnLabel(i), md.getColumnTypeName(i))).toList
      val rows = List.newBuilder[List[Json]]
      var count = 0
      var more  = false
      while !more && rs.next() do
        if count >= limit then more = true
        else
          rows += (1 to n).map(i => Duck.cell(rs, i)).toList
          count += 1
      rs.close()
      QueryResultDto(cols, rows.result(), (System.nanoTime() - t0) / 1000000, 0, 0, more)
    finally
      current = None
      s.close()

  /** SQL を実行し、1行ずつ f に渡す（大きな結果を JSON にせずに読む）。 */
  def scan(sql: String)(f: ResultSet => Unit): Unit =
    val s = conn.createStatement()
    current = Some(s)
    try
      val rs = s.executeQuery(sql)
      while rs.next() do f(rs)
      rs.close()
    finally
      current = None
      s.close()

  def close(): Unit = conn.close()

object DuckSession:
  /** 1つのストリームのビュー。files は手元のキャッシュのパス、fields は登録済みの列（ファイルがない列も null で見えるように）。 */
  final case class View(name: String, files: Seq[Path], fields: List[FieldDto])

  def open(cfg: DuckConfig, views: List[View], start: Long, end: Long, allowedDirs: List[Path]): DuckSession =
    val c = Duck.open(cfg, stream = true)
    try
      val s = c.createStatement()
      try
        views.foreach { v =>
          val typed = (FieldDto(Columns.Timestamp, FieldType.Int64) :: v.fields.filter(_.name != Columns.Timestamp))
            .map(f => s"CAST(NULL AS ${FieldTypes.sqlType(f.fieldType)}) AS ${ident(f.name)}").mkString(", ")
          val empty = s"SELECT $typed WHERE false"
          val ts    = ident(Columns.Timestamp)
          val body =
            if v.files.isEmpty then empty
            else s"""SELECT * FROM (SELECT * FROM read_parquet(${Duck.pathList(v.files)}, union_by_name = true) UNION ALL BY NAME $empty)
                    |WHERE $ts >= $start AND $ts <= $end""".stripMargin
          s.execute(s"CREATE VIEW ${ident(v.name)} AS $body")
        }
        // ここから先は利用者の SQL。キャッシュの外のファイル・ネットワークには触れさせない
        s.execute(s"SET allowed_directories = ${allowedDirs.map(d => lit(d.toString + "/")).mkString("[", ", ", "]")}")
        s.execute("SET enable_external_access = false")
        s.execute("SET lock_configuration = true")
      finally s.close()
      new DuckSession(c)
    catch
      case e: Throwable =>
        c.close()
        throw e
