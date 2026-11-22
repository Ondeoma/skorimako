package skorimako.engine

import skorimako.core.*
import skorimako.core.PromQL.Expr
import skorimako.shared.*

class PromSpec extends munit.FunSuite {

  private def parse(q: String): Expr = PromQL.parse(q).fold(e => fail(e), identity)

  /** 15秒ごと・20点のカウンター（1秒あたり rate ずつ増える）。 */
  private def counter(labels: Map[String, String], rate: Double, end: Long): Prom.Series = {
    val ts = (0 until 20).map(i => end - (19 - i) * 15000L).toArray
    Prom.Series(labels, ts, ts.indices.map(i => 100 + i * 15 * rate).toArray)
  }

  private val end = 1700000000000L
  private val data = (q: String) =>
    PromQL.selectors(parse(q)).map(PromQL.bare).map(s =>
      s -> List(
        counter(Map("__name__" -> "jobs_total", "job" -> "w", "instance" -> "a"), 1, end),
        counter(Map("__name__" -> "jobs_total", "job" -> "w", "instance" -> "b"), 2, end)
      )
    ).toMap

  private def vec(q: String): List[(Prom.Labels, Double)] =
    Prom.instant(parse(q), data(q), end) match {
      case Prom.Result.Vec(_, xs) => xs.sortBy(_._1.toString)
      case other => fail(s"vector ではありません: $other")
    }

  test("構文: セレクター・範囲・集計・二項演算・関数") {
    assert(PromQL.parse("""sum by (job) (rate(http_requests_total{code=~"5..", method!="GET"}[5m])) / 2""").isRight)
    assert(PromQL.parse("histogram_quantile(0.95, sum by (le) (rate(x_bucket[5m])))").isRight)
    assert(PromQL.parse("a + on(job) group_left(instance) b").isRight)
    assert(PromQL.parse("max_over_time(rate(x[1m])[10m:1m])").isRight)
    assert(PromQL.parse("rate(x[5m]").isLeft)
    assert(PromQL.parse("unknown_fn(x)").isLeft)
    assertEquals(PromQL.duration("1h30m"), Right(5400000L))
  }

  test("rate は1秒あたりの増え方、sum by でまとめる") {
    val r = vec("rate(jobs_total[2m])")
    assertEqualsDouble(r.find(_._1("instance") == "a").get._2, 1.0, 0.01)
    assertEqualsDouble(r.find(_._1("instance") == "b").get._2, 2.0, 0.01)
    assert(!r.head._1.contains("__name__"))
    val s = vec("sum by (job) (rate(jobs_total[2m]))")
    assertEquals(s.map(_._1), List(Map("job" -> "w")))
    assertEqualsDouble(s.head._2, 3.0, 0.01)
  }

  test("比較はフィルター（bool で 0・1）、数値との演算") {
    assertEquals(vec("jobs_total > 500").map(_._1("instance")), List("b"))
    assertEquals(vec("jobs_total > bool 1000").map(_._2), List(0.0, 0.0))
    assertEqualsDouble(vec("""jobs_total{instance="a"} * 2""").head._2, (100 + 19 * 15) * 2.0, 0.001)
  }

  test("histogram_quantile は Prometheus と同じ線形補間") {
    val buckets = List("10" -> 10.0, "100" -> 30.0, "+Inf" -> 40.0)
    val q = "histogram_quantile(0.5, h_bucket)"
    val sel = PromQL.selectors(parse(q)).head
    val d = Map(sel -> buckets.map((le, c) => Prom.Series(Map("le" -> le), Array(end), Array(c))))
    Prom.instant(parse(q), d, end) match {
      case Prom.Result.Vec(_, List((_, v))) =>
        assertEqualsDouble(v, 10 + 90 * (20 - 10) / 20.0, 0.001) // 順位 20 は 2つ目の区間の真ん中
      case other => fail(other.toString)
    }
  }

  test("範囲の問い合わせは step ごとの点") {
    val q = "sum(rate(jobs_total[1m]))"
    Prom.range(parse(q), data(q), end - 120000, end, 60000) match {
      case Prom.Result.Matrix(List((_, pts))) => assertEquals(pts.size, 3); assertEqualsDouble(pts.last._2, 3.0, 0.01)
      case other => fail(other.toString)
    }
  }
}

class TransformSpec extends munit.FunSuite {

  private def rule(kind: String,
                   field: String = "",
                   pattern: String = "",
                   replacement: String = "",
                   ratio: Double = 1) =
    Transform.compile(IngestRuleDto(
      java.util.UUID.randomUUID(),
      StreamType.Logs,
      "x",
      0,
      kind,
      field,
      pattern,
      replacement,
      ratio,
      true
    )).get

  private val row = Row(
    1L,
    Map(
      "body" -> Value.Str("mail taro@example.com card 4111 1111 1111 1111 token=abc123"),
      "k8s_pod" -> Value.Str("p"),
      "k8s_ns" -> Value.Str("n"),
      "level" -> Value.Str("debug")
    )
  )

  test("既定の型で伏せる") {
    val r = Seq("email", "card", "token").foldLeft(Option(row))((acc, p) =>
      acc.flatMap(rule(IngestRuleKind.Mask, "body", p).run)
    )
    assertEquals(r.flatMap(_.str("body")), Some("mail ***@*** card ****-****-****-**** token=***"))
  }

  test("列を落とす（前方一致）・行を捨てる・名前を変える") {
    assertEquals(rule(IngestRuleKind.DropField, "k8s_*").run(row).map(_.fields.keySet), Some(Set("body", "level")))
    assertEquals(rule(IngestRuleKind.DropRows, "level", "^debug$").run(row), None)
    assertEquals(
      rule(IngestRuleKind.Rename, "level", replacement = "severity").run(row).flatMap(_.str("severity")),
      Some("debug")
    )
  }

  test("間引きはトレースごとにそろう") {
    val s = rule(IngestRuleKind.Sample, ratio = 0.5)
    val t = Row(1L, Map("trace_id" -> Value.Str("abc")))
    assertEquals((1 to 10).map(_ => s.run(t).isDefined).distinct.size, 1)
  }

  test("ルールの検査") {
    def req(kind: String, field: String, pattern: String) =
      IngestRuleReq(StreamType.Logs, "x", 0, kind, field, pattern, "", 1, true)
    assert(Transform.validate(req(IngestRuleKind.DropRows, "level", "(")).isLeft)
    assert(Transform.validate(req(IngestRuleKind.DropField, "", "")).isLeft)
    assert(Transform.validate(req(IngestRuleKind.Mask, "", "email")).isRight)
  }
}
