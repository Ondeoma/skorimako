package skorimako.engine

import io.circe.Json
import skorimako.core.*
import skorimako.core.SqlText.{ident, lit}
import skorimako.shared.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, ResultSet}
import java.util.Properties

/** DuckDB の設定（検索ごとのメモリ・スレッド・一時ファイル）。 */
final case class DuckConfig(
  memoryLimit: String = "1GB",
  threads: Int = 4,
  tempDir: Path)

/** DuckDB（組み込み）の使い方をまとめる。接続はメモリ上の DB で、使うたびに作って捨てる。 */
object Duck {

  Class.forName("org.duckdb.DuckDBDriver")

  def open(
    cfg: DuckConfig,
    stream: Boolean = false
  ): Connection = {
    val p = new Properties()
    // 結果を少しずつ受け取る（上限の行数で読むのをやめたとき、残りを作らない）
    if stream then p.setProperty("jdbc_stream_results", "true")
    val c = DriverManager.getConnection("jdbc:duckdb:", p)
    val s = c.createStatement()
    try {
      Files.createDirectories(cfg.tempDir)
      s.execute(s"SET memory_limit = ${lit(cfg.memoryLimit)}")
      s.execute(s"SET threads = ${math.max(1, cfg.threads)}")
      s.execute(s"SET temp_directory = ${lit(cfg.tempDir.toString)}")
      s.execute("SET autoinstall_known_extensions = false")
      s.execute("SET autoload_known_extensions = false")
    } finally s.close()
    c
  }

  def using[A](
    c: Connection
  )(
    f: Connection => A
  ): A =
    try f(c)
    finally c.close()

  /** パスの一覧を DuckDB のリストの書き方にする。 */
  def pathList(
    ps: Seq[Path]
  ): String = ps.map(p => lit(p.toString)).mkString("[", ", ", "]")

  // ---------------------------------------------------------------- 書き出し

  /**
   * 行を Parquet に書く。types は列ごとの型（値はこの型に直す。直せない値は null）。
   * いったん NDJSON に書き、DuckDB の read_json で型を決めて読み、時刻の順に並べて書き出す。
   */
  def writeParquet(
    cfg: DuckConfig,
    rows: Seq[Row],
    types: Map[String, String],
    out: Path
  ): Unit = {
    Files.createDirectories(cfg.tempDir)
    val tmp = Files.createTempFile(cfg.tempDir, "rows-", ".ndjson")
    try {
      val w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)
      try rows.foreach { r =>
          val fs = r.fields.iterator.flatMap(
            (
              k,
              v
            ) => types.get(k).flatMap(v.coerce).map(x => k -> x.toJson)
          ).toList
          w.write(Json.fromFields((Columns.Timestamp -> Json.fromLong(r.ts)) :: fs).noSpaces)
          w.write('\n')
        }
      finally w.close()
      val cols =
        ((Columns.Timestamp -> "BIGINT") :: types.toList.filter(_._1 != Columns.Timestamp).sortBy(_._1).map(
          (
            k,
            t
          ) => k -> FieldTypes.sqlType(t)
        ))
          .map(
            (
              k,
              t
            ) => s"${lit(k)}: ${lit(t)}"
          ).mkString("{", ", ", "}")
      using(open(cfg)) { c =>
        val s = c.createStatement()
        try s.execute(
            s"""COPY (SELECT * FROM read_json(${lit(tmp.toString)}, format = 'newline_delimited', columns = $cols)
               |ORDER BY ${ident(Columns.Timestamp)}) TO ${lit(
                out.toString
              )} (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE 122880)""".stripMargin
          )
        finally s.close()
      }
    } finally Files.deleteIfExists(tmp)
  }

  /** いくつかの Parquet を1つにまとめる（圧縮）。(行数, 最小の時刻, 最大の時刻)。 */
  def mergeParquet(
    cfg: DuckConfig,
    files: Seq[Path],
    out: Path
  ): (Long, Long, Long) =
    using(open(cfg)) { c =>
      val s = c.createStatement()
      try {
        s.execute(s"""COPY (SELECT * FROM read_parquet(${pathList(files)}, union_by_name = true) ORDER BY ${ident(
                      Columns.Timestamp
                    )})
                     |TO ${lit(out.toString)} (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE 122880)""".stripMargin)
        val rs = s.executeQuery(
          s"SELECT count(*), min(${ident(Columns.Timestamp)}), max(${ident(Columns.Timestamp)}) FROM read_parquet(${lit(out.toString)})"
        )
        rs.next()
        (rs.getLong(1), rs.getLong(2), rs.getLong(3))
      } finally s.close()
    }

  // ---------------------------------------------------------------- 検索

  /** 結果の値を JSON に。 */
  def cell(
    rs: ResultSet,
    i: Int
  ): Json =
    rs.getObject(i) match {
      case null => Json.Null
      case v: java.lang.Long => Json.fromLong(v)
      case v: java.lang.Integer => Json.fromInt(v)
      case v: java.lang.Short => Json.fromInt(v.toInt)
      case v: java.lang.Byte => Json.fromInt(v.toInt)
      case v: java.lang.Double => Json.fromDoubleOrNull(v)
      case v: java.lang.Float => Json.fromDoubleOrNull(v.toDouble)
      case v: java.math.BigDecimal => Json.fromBigDecimal(BigDecimal(v))
      case v: java.math.BigInteger => Json.fromBigInt(BigInt(v))
      case v: java.lang.Boolean => Json.fromBoolean(v)
      case v: String => Json.fromString(v)
      case v: java.sql.Timestamp => Json.fromString(v.toInstant.toString)
      case v: java.time.OffsetDateTime => Json.fromString(v.toInstant.toString)
      case v: java.time.LocalDateTime => Json.fromString(v.toString)
      case v: java.sql.Array =>
        val arr = v.getArray.asInstanceOf[Array[AnyRef]]
        Json.fromString(arr.map(x => String.valueOf(x)).mkString("[", ", ", "]"))
      case v => Json.fromString(v.toString)
    }
}
