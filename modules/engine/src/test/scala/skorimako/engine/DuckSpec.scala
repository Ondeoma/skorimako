package skorimako.engine

import skorimako.core.*
import skorimako.shared.*

import java.nio.file.{Files, Path}

class DuckSpec extends munit.FunSuite:

  private def tmp(): Path = Files.createTempDirectory("skorimako-test")

  test("Parquet に書いて、ビューとして検索できる（型の食い違い・欠けた列も扱える）") {
    val dir  = tmp()
    val cfg  = DuckConfig("256MB", 2, dir.resolve("tmp"))
    Files.createDirectories(cfg.tempDir)
    val f1 = dir.resolve("cache/a.parquet"); Files.createDirectories(f1.getParent)
    val f2 = dir.resolve("cache/b.parquet")
    Duck.writeParquet(cfg, Seq(
      Row(2000000L, Map("body" -> Value.Str("hello world"), "status" -> Value.Int(200))),
      Row(1000000L, Map("body" -> Value.Str("error: boom"), "status" -> Value.Int(500), "user" -> Value.Str("u1")))
    ), Map("body" -> FieldType.Utf8, "status" -> FieldType.Int64, "user" -> FieldType.Utf8), f1)
    Duck.writeParquet(cfg, Seq(Row(3000000L, Map("body" -> Value.Str("later"), "status" -> Value.Dbl(1.5)))),
      Map("body" -> FieldType.Utf8, "status" -> FieldType.Float64), f2)
    val fields = List(FieldDto("body", FieldType.Utf8), FieldDto("status", FieldType.Float64), FieldDto("user", FieldType.Utf8),
      FieldDto("extra", FieldType.Utf8))
    val s = DuckSession.open(cfg, List(DuckSession.View("default", List(f1, f2), fields)), 0L, 10000000L, List(dir.resolve("cache")))
    try
      val r = s.query("""SELECT body, status, "user", extra FROM "default" ORDER BY _timestamp""", 100)
      assertEquals(r.rows.size, 3)
      assertEquals(r.rows.head.head.asString, Some("error: boom"))
      val c = s.query("""SELECT count(*) FROM "default" WHERE status >= 200 AND _timestamp <= 2000000""", 10)
      assertEquals(c.rows.head.head.asNumber.flatMap(_.toLong), Some(2L))
      // 手元のほかのファイルは読めない
      intercept[java.sql.SQLException](s.query("SELECT * FROM read_csv('/etc/passwd')", 10))
      // 設定は変えられない
      intercept[java.sql.SQLException](s.query("SET enable_external_access = true", 10))
    finally s.close()
  }

  test("ストリームのファイルがなくても、登録済みの列で空のビューができる") {
    val dir = tmp()
    val cfg = DuckConfig("256MB", 1, dir.resolve("tmp"))
    val s = DuckSession.open(cfg, List(DuckSession.View("empty", Nil, List(FieldDto("body", FieldType.Utf8)))), 0L, 1L, List(dir))
    try assertEquals(s.query("SELECT count(*), max(body) FROM empty", 10).rows.head.head.asNumber.flatMap(_.toLong), Some(0L))
    finally s.close()
  }

  test("圧縮: ファイルをまとめる") {
    val dir = tmp()
    val cfg = DuckConfig("256MB", 1, dir.resolve("tmp"))
    Files.createDirectories(cfg.tempDir)
    val fs = (1 to 3).map { i =>
      val p = dir.resolve(s"$i.parquet")
      Duck.writeParquet(cfg, Seq(Row(i * 10L, Map("n" -> Value.Int(i.toLong)))), Map("n" -> FieldType.Int64), p)
      p
    }
    assertEquals(Duck.mergeParquet(cfg, fs, dir.resolve("out.parquet")), (3L, 10L, 30L))
  }
