package skorimako.core

import skorimako.shared.{AlertHitDto, AlertState}

import java.time.Instant

class CoreSpec extends munit.FunSuite:

  test("列名とストリーム名の規則") {
    assertEquals(Names.column("service.name"), Some("service_name"))
    assertEquals(Names.column("HTTP-Status"), Some("http_status"))
    assertEquals(Names.column("..."), None)
    assertEquals(Names.stream("My.App"), Right("my_app"))
    assert(Names.stream("a b").isLeft)
    assert(Names.stream("../x").isLeft)
  }

  test("型を広げる・値を直す") {
    assertEquals(FieldTypes.widen("int64", "float64"), "float64")
    assertEquals(FieldTypes.widen("int64", "utf8"), "utf8")
    assertEquals(Value.Str("12").coerce("int64"), Some(Value.Int(12)))
    assertEquals(Value.Str("x").coerce("int64"), None)
    assertEquals(Value.Int(3).coerce("utf8"), Some(Value.Str("3")))
  }

  test("整数と小数は JSON を通しても区別できる（WAL）") {
    val r = Row(10L, Map("a" -> Value.Int(1), "b" -> Value.Dbl(1.0), "c" -> Value.Str("x"), "d" -> Value.Bool(true)))
    assertEquals(Row.fromJson(r.toJson), Some(r))
  }

  test("区切りの長さはきりのよい値") {
    assertEquals(QueryBuilder.interval(0L, 3600L * 1000000, 60), 60L * 1000000)
    assertEquals(QueryBuilder.interval(0L, 24L * 3600 * 1000000, 60), 1800L * 1000000)
  }

  test("ログの検索の SQL: キーワードは小文字で部分一致、利用者の条件は括弧で囲む") {
    val cs = QueryBuilder.conditions(Some("a = 1 OR b = 2"), Some("Timeout"), List("body")).toOption.get
    assertEquals(cs, List("(a = 1 OR b = 2)", "(contains(lower(CAST(\"body\" AS VARCHAR)), 'timeout'))"))
    val q = QueryBuilder.logSearch("default", cs, "desc", 10, 0, 1000000L)
    assert(q.list.startsWith("SELECT * FROM \"default\" WHERE (a = 1 OR b = 2) AND"))
    assert(QueryBuilder.conditions(Some("1=1); DROP TABLE x; --"), None, Nil).isLeft)
  }

  test("メトリクスの SQL: rate はラベルごとに差を取る") {
    val sql = QueryBuilder.metric("m", "rate", List("route"), Nil, 60000000L, List("route", "service_name")).toOption.get
    assert(sql.contains("PARTITION BY \"route\", \"service_name\""))
    assert(sql.contains("/ 60.0"))
    assert(QueryBuilder.metric("m", "bogus", Nil, Nil, 1L, Nil).isLeft)
  }

  test("アラートの状態の移り変わり") {
    val now = Instant.parse("2026-11-17T00:00:00Z")
    assertEquals(AlertLogic.next(AlertState.Ok, true, None, 0, now), (AlertState.Firing, AlertLogic.Action.Fire))
    assertEquals(AlertLogic.next(AlertState.Firing, true, Some(now.minusSeconds(60)), 0, now), (AlertState.Firing, AlertLogic.Action.None))
    assertEquals(AlertLogic.next(AlertState.Firing, true, Some(now.minusSeconds(3600)), 30, now), (AlertState.Firing, AlertLogic.Action.Fire))
    assertEquals(AlertLogic.next(AlertState.Firing, false, None, 0, now), (AlertState.Ok, AlertLogic.Action.Resolve))
    assertEquals(AlertLogic.next(AlertState.Ok, false, None, 0, now), (AlertState.Ok, AlertLogic.Action.None))
    assertEquals(AlertLogic.describe(List(AlertHitDto(Some("api"), 12.0)), ">", 5), "api: 12（条件 > 5）")
  }

class PruneSpec extends munit.FunSuite:

  test("一番外側が AND の 列 = 値・列 IN (…) だけを取り出す") {
    assertEquals(Prune.constraints(Some("service_name = 'api' AND severity_text IN ('ERROR', 'WARN') AND duration > 5")),
      Map("service_name" -> Set("api"), "severity_text" -> Set("ERROR", "WARN")))
    assertEquals(Prune.constraints(Some("a = 'x' OR b = 'y'")), Map.empty)
    assertEquals(Prune.constraints(Some("(a = 'x') AND (b = 'y' OR c = 'z')")), Map("a" -> Set("x")))
    assertEquals(Prune.constraints(Some("NOT a = 'x'")), Map.empty)
  }

  test("ファイルの値の一覧と照らす") {
    val tags = Map("service_name" -> List("api", "web"))
    assert(Prune.keep(tags, Map("service_name" -> Set("api"))))
    assert(!Prune.keep(tags, Map("service_name" -> Set("db"))))
    assert(Prune.keep(tags, Map("other" -> Set("x")))) // 一覧にない列は読む
  }

  test("値の一覧は種類の少ない文字列の列だけ。まとめると和になる") {
    val rows = (1 to 50).map(i => Row(i, Map("svc" -> Value.Str(if i % 2 == 0 then "a" else "b"), "id" -> Value.Str(s"id$i"), "n" -> Value.Int(i))))
    val t = Prune.tags(rows)
    assertEquals(t, Map("svc" -> List("a", "b")))
    assertEquals(Prune.mergeTags(List(t, Map("svc" -> List("c")))), Map("svc" -> List("a", "b", "c")))
    assertEquals(Prune.mergeTags(List(t, Map.empty)), Map.empty)
  }
