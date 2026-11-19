package skorimako.core

import skorimako.shared.*

/** かんたん作成のパネルの SQL. */
class PanelSqlSpec extends munit.FunSuite {

  private def spec(
    st: String,
    stream: String,
    measure: String,
    field: Option[String] = None,
    group: Option[String] = None,
    filters: List[PanelFilter] = Nil,
    service: Option[String] = None
  ) =
    PanelSpec(st, stream, measure, field, group, filters, service, Some(5))

  test("ログの件数の折れ線（区切りは $__interval のまま）") {
    val Right((sql, unit)) =
      QueryBuilder.panel(spec(StreamType.Logs, "default", "count"), PanelKind.Line, Nil, Set.empty): @unchecked
    assertEquals(
      sql,
      """SELECT ("_timestamp" // $__interval) * $__interval AS t, count(*) AS v FROM "default" GROUP BY ALL ORDER BY t"""
    )
    assertEquals(unit, "")
  }

  test("トレースの所要時間の p95 をサービスで絞り、ルートで分ける（上位だけ・ms）") {
    val Right((sql, unit)) = QueryBuilder.panel(
      spec(
        StreamType.Traces,
        "default",
        "p95",
        Some("duration"),
        Some("http_route"),
        List(PanelFilter("http_response_status_code", ">=", "500")),
        Some("stui")
      ),
      PanelKind.Line,
      Nil,
      Set("http_response_status_code")
    ): @unchecked
    assert(sql.contains("quantile_cont(\"duration\", 0.95) / 1000.0 AS v"), sql)
    assert(sql.contains("\"http_response_status_code\" >= 500"), sql)
    assert(sql.contains("\"service_name\" = 'stui'"), sql)
    assert(sql.contains("\"http_route\" IN (SELECT \"http_route\" FROM \"default\""), sql)
    assert(sql.contains("LIMIT 5"), sql)
    assertEquals(unit, "ms")
  }

  test("メトリクスの rate（カウンター）はラベルごとの差分") {
    val Right((sql, unit)) = QueryBuilder.panel(
      spec(StreamType.Metrics, "stui_messages_posted", "rate", group = Some("kind")),
      PanelKind.Line,
      List("kind", "service_name"),
      Set.empty
    ): @unchecked
    assert(sql.contains("PARTITION BY \"kind\", \"service_name\""), sql)
    assert(sql.contains("/ ($__interval / 1e6) AS v"), sql)
    assertEquals(unit, "/s")
  }

  test("数値・表") {
    val Right((stat, _)) = QueryBuilder.panel(
      spec(StreamType.Logs, "default", "count", filters = List(PanelFilter("severity_text", "=", "ERROR"))),
      PanelKind.Stat,
      Nil,
      Set.empty
    ): @unchecked
    assertEquals(stat, """SELECT count(*) AS v FROM "default" WHERE "severity_text" = 'ERROR'""")
    val Right((table, _)) = QueryBuilder.panel(
      spec(StreamType.Logs, "default", "count", group = Some("user_name")),
      PanelKind.Table,
      Nil,
      Set.empty
    ): @unchecked
    assert(table.endsWith("GROUP BY ALL ORDER BY v DESC LIMIT 5"), table)
  }

  test("値の引用符はエスケープする・集計する列がなければエラー") {
    val Right((sql, _)) = QueryBuilder.panel(
      spec(StreamType.Logs, "default", "count", filters = List(PanelFilter("body", "contains", "it's"))),
      PanelKind.Stat,
      Nil,
      Set.empty
    ): @unchecked
    assert(sql.contains("'it''s'"), sql)
    assert(QueryBuilder.panel(spec(StreamType.Logs, "default", "avg"), PanelKind.Line, Nil, Set.empty).isLeft)
    assert(QueryBuilder.panel(spec(StreamType.Logs, "", "count"), PanelKind.Line, Nil, Set.empty).isLeft)
  }
}
