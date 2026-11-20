package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.{ACursor, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import skorimako.core.*
import skorimako.shared.*
import sbirds.http.Errors
import sbirds.mcp.{McpCaller, McpServer, McpTool}
import sbirds.mcp.McpSchema.*

/**
 * MCP（Model Context Protocol）のサーバー。POST /mcp に JSON-RPC を送る（Streamable HTTP。応答は JSON、SSE は使わない）。
 * 認証は APIキー（Bearer）だけ。各ツールは REST API をそのキーで内部から呼ぶので、権限・スコープ・監査は REST API と同じになる。
 */
final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO] {

  private val base = publicUrl.stripSuffix("/")

  // JSON-RPC の枠（認証・振り分け・GET・DELETE /mcp の 405）は sbirds.mcp.McpServer。POST /mcp は全体の時間の上限を掛けてから枠へ渡す
  lazy val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" => postMcp(req)
    case GET -> Root / "api" / "endpoints" => getEndpoints()
  } <+> frame

  private lazy val frame: HttpRoutes[IO] = McpServer.routes(host)

  /**
   * 枠に渡す前に、本文を 1 度だけ読んで何度でも読み直せる形にする（tools/call の数を枠より先に数えるため。読むのは認証のあと）。
   * 要求の全体に時間の上限を掛ける。
   */
  private def postMcp(req: Request[IO]): IO[Response[IO]] =
    req.body.compile.to(Chunk).memoize.flatMap { body =>
      frame.run(req.withBodyStream(Stream.eval(body).flatMap(Stream.chunk))).getOrElseF(NotFound()).timeoutTo(
        McpRoutes.Timeout,
        ServiceUnavailable(McpServer.rpcFail(
          Json.Null,
          -32000,
          s"時間内（${McpRoutes.Timeout.toSeconds}秒）に終わりませんでした"
        ))
      )
    }

  private def getEndpoints(): IO[Response[IO]] =
    Ok(ApiCatalog.all.map(e =>
      Json.obj("method" -> e.method.asJson, "path" -> e.path.asJson, "description" -> e.description.asJson)
    ))

  // ------------------------------------------------------------------ ツール

  private type Result = McpTool.Result
  private type Caller = McpCaller
  private type Tool = McpTool[Caller]
  private def Tool(name: String, description: String, schema: Json, readOnly: Boolean, destructive: Boolean = false)(
    run: (Caller, ACursor) => Result): Tool = McpTool(name, description, schema, readOnly, destructive)(run)
  private def path(segments: String*): Uri = McpCaller.path(segments*)

  private def num(d: String): Json = Json.obj("type" -> "number".asJson, "description" -> d.asJson)
  private def opt(a: ACursor, k: String): Option[String] = a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  private val timeProps: List[(String, Json)] = List(
    "minutes" -> int("直近の何分を対象にするか（既定60）"),
    "start" -> str("始まり（ISO 8601。minutes より優先）"),
    "end" -> str("終わり（ISO 8601。省略時は今）")
  )

  private def instant(s: String): Option[java.time.Instant] =
    scala.util.Try(
      java.time.OffsetDateTime.parse(s).toInstant
    ).orElse(scala.util.Try(java.time.Instant.parse(s))).toOption

  /** 時間の範囲（マイクロ秒）。 */
  private def range(a: ACursor): Either[String, (Long, Long)] = {
    def us(i: java.time.Instant) = i.getEpochSecond * 1000000L + i.getNano / 1000
    val now = java.time.Instant.now()
    for {
      end <-
        opt(a, "end").fold(Right(now))(s => instant(s).toRight("end は ISO 8601（2026-11-20T09:00:00+09:00）で指定してください"))
      start <- opt(a, "start") match {
        case Some(s) => instant(s).toRight("start は ISO 8601 で指定してください")
        case None => Right(end.minusSeconds(a.get[Int]("minutes").getOrElse(60).toLong * 60))
      }
    } yield (us(start), us(end))
  }

  private def time(us: Long): String =
    java.time.Instant.ofEpochMilli(us / 1000).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime.toString.replace(
      'T',
      ' '
    ).take(23)

  /** 検索の結果を表（タブ区切り）にする。長い値は切る。 */
  private def table(j: Json, maxCell: Int = 300): String = {
    val c = j.hcursor
    val cols = c.downField("columns").as[List[Json]].getOrElse(Nil).map(_.hcursor.get[String]("name").getOrElse(""))
    val rows = c.downField("rows").as[List[List[Json]]].getOrElse(Nil)
    val tsIx = cols.indexOf(Columns.Timestamp)
    val body = rows.map(_.zipWithIndex.map {
      (v, i) =>
        if i == tsIx then v.asNumber.flatMap(_.toLong).map(time).getOrElse("")
        else
          (v.asString.getOrElse(if v.isNull then "" else v.noSpaces)).replace(
            "\t",
            " "
          ).replace("\n", "⏎").take(maxCell)
    }.mkString("\t"))
    val more = if c.get[Boolean]("truncated").contains(true) then "\n（上限の行数で切りました）" else ""
    ((cols.mkString("\t") :: body).mkString("\n")) + more
  }

  private val tools: List[Tool] = List(
    Tool("get_me", "自分（この APIキーの持ち主）の情報。", schema(Nil), readOnly = true)((call, _) =>
      call(Method.GET, path("api", "me")).asText
    ),
    Tool(
      "list_streams",
      "ストリーム（ログ・メトリクス・トレース・フロントエンドのデータの入れ物）の一覧。メトリクスはメトリクスの名前ごとに1つ。",
      schema(Nil, "type" -> str("logs・metrics・traces・rum"), "q" -> str("名前の一部")),
      readOnly = true
    ) {
      (call, a) =>
        val u = List(
          "type" -> opt(a, "type"),
          "q" -> opt(a, "q")
        ).collect { case (k, Some(v)) => k -> v }.foldLeft(path("api", "streams"))((u, kv) =>
          u.withQueryParam(kv._1, kv._2)
        )
        call(Method.GET, u).map(_.map(j =>
          j.asArray.toList.flatten.map { s =>
            val c = s.hcursor
            val range = (c.get[Long]("minTs").toOption, c.get[Long]("maxTs").toOption).mapN((a, b) =>
              s"${time(a)}〜${time(b)}"
            ).getOrElse("データなし")
            s"- ${c.get[String]("streamType").getOrElse("")}/${c.get[String]("name").getOrElse("")} 行数 ${c.get[Long]("docCount").getOrElse(0L)} $range" +
              c.get[String]("description").toOption.filter(_.nonEmpty).fold("")(d => s" — $d") +
              c.get[String]("unit").toOption.filter(_.nonEmpty).fold("")(u => s"（単位 $u）")
          }.mkString("\n")
        ))
    },
    Tool(
      "get_stream_schema",
      "ストリームの列と型（SQL を書く前に確かめる）。",
      schema(List("type", "name"), "type" -> str("logs・metrics・traces・rum"), "name" -> str("ストリーム名")),
      readOnly = true
    ) {
      (call, a) =>
        (need(a, "type"), need(a, "name")).tupled.withArgs((t, n) =>
          call(Method.GET, path("api", "streams", t, n)).map(_.map { j =>
            j.hcursor.downField("fields").as[List[Json]].getOrElse(Nil).map(f =>
              s"${f.hcursor.get[String]("name").getOrElse("")}\t${f.hcursor.get[String]("fieldType").getOrElse("")}"
            )
              .mkString(s"${Columns.Timestamp}\tint64（マイクロ秒）\n", "\n", "")
          })
        )
    },
    Tool(
      "search_logs",
      "ログを探す（新しい順）。where は SQL の条件（例: severity_text = 'ERROR' AND service_name = 'api'）、keyword は本文の部分一致。",
      schema(
        List("stream"),
        (List(
          "stream" -> str("ストリーム名（既定 default）"),
          "where" -> str("SQL の条件"),
          "keyword" -> str("本文に含む語"),
          "limit" -> int("件数（既定50、最大1000）"),
          "streamType" -> str("logs（既定）または rum")
        ) ++ timeProps)*
      ),
      readOnly = true
    ) {
      (call, a) =>
        (need(a, "stream"), range(a)).tupled.withArgs { case (s, (st, en)) =>
          call(
            Method.POST,
            path("api", "query", "logs"),
            Some(LogSearchReq(
              s,
              st,
              en,
              opt(a, "where"),
              opt(a, "keyword"),
              Some(a.get[Int]("limit").getOrElse(50)),
              None,
              None,
              opt(a, "streamType")
            ).asJson)
          ).map(_.map(j =>
            s"${j.hcursor.get[Long]("total").getOrElse(0L)} 件\n" +
              table(j.hcursor.downField("result").focus.getOrElse(Json.Null))
          ))
        }
    },
    Tool(
      "query_sql",
      "SQL（DuckDB の方言）で検索する。FROM にストリームの名前を書く（例: SELECT service_name, count(*) FROM \"default\" GROUP BY 1）。" +
        "_timestamp はマイクロ秒の整数。to_timestamp(_timestamp / 1e6) で日時になる。対象の時間の範囲は自動で絞られる。",
      schema(
        List("streamType", "sql"),
        (List("streamType" -> str("logs・metrics・traces・rum"), "sql" -> str("SELECT 文"), "limit" -> int("行数（既定200）")) ++
          timeProps)*
      ),
      readOnly = true
    ) {
      (call, a) =>
        (need(a, "streamType"), need(a, "sql"), range(a)).tupled.withArgs { case (t, sql, (st, en)) =>
          call(
            Method.POST,
            path("api", "query", "sql"),
            Some(SqlQueryReq(t, sql, st, en, Some(a.get[Int]("limit").getOrElse(200))).asJson)
          ).map(_.map(table(_)))
        }
    },
    Tool(
      "query_metrics",
      "メトリクスの時系列（区切りごとの集計）。agg: avg・sum・min・max・count・last・rate（カウンターの毎秒の増加）・p50・p90・p95・p99。",
      schema(
        List("metric"),
        (List(
          "metric" -> str("メトリクス名（例: http_server_duration_count）"),
          "agg" -> str("集計（既定 avg）"),
          "groupBy" -> strs("分けるラベル"),
          "where" -> str("SQL の条件"),
          "stepSeconds" -> int("区切りの秒数")
        ) ++ timeProps)*
      ),
      readOnly = true
    ) {
      (call, a) =>
        (need(a, "metric"), range(a)).tupled.withArgs { case (m, (st, en)) =>
          val r = MetricQueryReq(
            m,
            st,
            en,
            opt(a, "agg").getOrElse("avg"),
            a.get[List[String]]("groupBy").getOrElse(Nil),
            opt(a, "where"),
            a.get[Int]("stepSeconds").toOption
          )
          call(Method.POST, path("api", "query", "metrics"), Some(r.asJson)).map(_.map { j =>
            j.hcursor.downField("series").as[List[SeriesDto]].getOrElse(Nil).map { s =>
              val label = if s.labels.isEmpty then "（全体）"
              else
                s.labels.map((k, v) => s"$k=$v").mkString(", ")
              s"■ $label\n" +
                s.points.map(p => s"${time(p.t)}\t${p.v.map(AlertLogic.fmt).getOrElse("")}").mkString("\n")
            }.mkString("\n")
          })
        }
    },
    Tool(
      "search_traces",
      "トレースを探す（新しい順）。",
      schema(
        Nil,
        (List(
          "service" -> str("サービス名"),
          "name" -> str("ルートのスパンの名前の一部"),
          "minDurationMs" -> num("この時間（ミリ秒）以上"),
          "errorsOnly" -> bool("エラーを含むものだけ"),
          "limit" -> int("件数（既定30）")
        ) ++ timeProps)*
      ),
      readOnly = true
    ) {
      (call, a) =>
        range(a).withArgs {
          (st, en) =>
            val r = TraceSearchReq(
              st,
              en,
              opt(a, "service"),
              opt(a, "name"),
              a.get[Double]("minDurationMs").toOption,
              a.get[Boolean]("errorsOnly").toOption,
              None,
              Some(a.get[Int]("limit").getOrElse(30))
            )
            call(Method.POST, path("api", "traces", "search"), Some(r.asJson)).map(_.map(j =>
              j.as[List[TraceSummaryDto]].getOrElse(Nil).map { t =>
                s"${time(t.start)}\t${t.service}\t${t.rootName}\t${t.durationMicros / 1000.0}ms\tspans=${t.spanCount}\terrors=${t.errorCount}\ttraceId=${t.traceId}"
              }.mkString("\n")
            ))
        }
    },
    Tool(
      "get_trace",
      "トレースのスパンをすべて（開始の順。親子は parent_span_id）。",
      schema(List("traceId"), "traceId" -> str("トレース ID（16進数）")),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "traceId").withArgs(id =>
          call(Method.GET, path("api", "traces", id)).map(_.map { j =>
            j.hcursor.downField("spans").as[List[Json]].getOrElse(Nil).map { s =>
              val c = s.hcursor
              List(
                time(c.get[Long](Columns.Timestamp).getOrElse(0L)),
                c.get[String]("service_name").getOrElse(""),
                c.get[String]("operation_name").getOrElse(""),
                s"${c.get[Long]("duration").getOrElse(0L) / 1000.0}ms",
                c.get[String]("status_code").getOrElse(""),
                s"span=${c.get[String]("span_id").getOrElse("")}",
                s"parent=${c.get[String]("parent_span_id").getOrElse("")}",
                c.get[String]("status_message").getOrElse("")
              ).mkString("\t")
            }.mkString("\n") + s"\n画面: $base/traces/$id"
          })
        )
    },
    Tool(
      "query_promql",
      "PromQL（Prometheus の問い合わせ言語）で時系列を求める。例: sum by (service_name) (rate(http_server_requests[5m]))。" +
        "メトリクスの名前は skorimako のストリームの名前（. は _）。ラベルの名前は英小文字・数字・_。",
      schema(List("query"), (List("query" -> str("PromQL"), "stepSeconds" -> int("区切りの秒（既定は範囲の1/60）")) ++ timeProps)*),
      readOnly = true
    ) {
      (call, a) =>
        (need(a, "query"), range(a)).tupled.withArgs { case (q, (st, en)) =>
          val step = a.get[Int]("stepSeconds").toOption.map(_.toLong).getOrElse(math.max(15L, (en - st) / 1000000 / 60))
          val u = path("api", "prom", "api", "v1", "query_range").withQueryParams(Map(
            "query" -> q,
            "start" -> (st / 1000000).toString,
            "end" -> (en / 1000000).toString,
            "step" -> step.toString
          ))
          call(Method.GET, u).map(_.map { j =>
            j.hcursor.downField("data").downField("result").as[List[Json]].getOrElse(Nil).take(50).map { r =>
              val m = r.hcursor.downField("metric").as[Map[String, String]].getOrElse(Map.empty)
              val vs = r.hcursor.downField("values").as[List[(Double, String)]].getOrElse(Nil)
              s"■ ${
                  if m.isEmpty then "（全体）"
                  else
                    m.toList.sorted.map((k, v) => s"$k=$v").mkString(", ")
                }\n" +
                vs.map((t, v) => s"${time((t * 1000000).toLong)}\t$v").mkString("\n")
            }.mkString("\n")
          })
        }
    },
    Tool(
      "service_map",
      "サービスごとの要求の数・エラー・所要時間（p50・p95・p99）と、サービス間の呼び出し（トレースから）。",
      schema(Nil, (List("stream" -> str("トレースのストリーム（既定 default）")) ++ timeProps)*),
      readOnly = true
    ) {
      (call, a) =>
        range(a).withArgs {
          (st, en) =>
            val u = path("api", "services", "map").withQueryParams(Map(
              "stream" -> opt(a, "stream").getOrElse("default"),
              "start" -> st.toString,
              "end" -> en.toString
            ))
            call(Method.GET, u).map(_.map { j =>
              val m = j.as[ServiceMapDto].getOrElse(ServiceMapDto(Nil, Nil))
              ("サービス\t要求\tエラー\t毎秒\tp50(ms)\tp95(ms)\tp99(ms)" :: m.services.map(x =>
                s"${x.name}\t${x.requests}\t${x.errors}\t${AlertLogic.fmt(x.rps)}\t${AlertLogic.fmt(x.p50Ms)}\t${AlertLogic.fmt(x.p95Ms)}\t${AlertLogic.fmt(x.p99Ms)}"
              ) ++
                ("" :: "呼び出し元 → 先\t回数\tエラー\tp95(ms)" :: m.edges.map(e =>
                  s"${e.from} → ${e.to}\t${e.calls}\t${e.errors}\t${AlertLogic.fmt(e.p95Ms)}"
                ))).mkString("\n")
            })
        }
    },
    Tool("list_alerts", "アラートのルールと今の状態（正常・発生中・評価できない）。", schema(Nil), readOnly = true) {
      (call, _) =>
        call(Method.GET, path("api", "alerts")).map(_.map(j =>
          j.as[List[AlertRuleDto]].getOrElse(Nil).map { r =>
            val extra = r.ack.map(a => s"・対応中（${a.by.map(_.name).getOrElse("")}）").getOrElse("") +
              r.silencedUntil.map(_ => "・ミュート中").getOrElse("")
            s"- ${r.name} [${AlertState.label(r.state)}${if r.enabled then "" else "・停止中"}$extra] ${r.streamType}/${r.stream} 値 ${r.lastValue.map(AlertLogic.fmt).getOrElse("-")} 条件 ${r.op} ${AlertLogic.fmt(r.threshold)} id=${r.id}"
          }.mkString("\n")
        ))
    },
    Tool(
      "list_alert_events",
      "アラートの履歴（発生・解消。新しい順）。",
      schema(Nil, "ruleId" -> str("ルールの ID"), "limit" -> int("件数（既定30）")),
      readOnly = true
    ) {
      (call, a) =>
        val u = path("api", "alerts", "events").withQueryParam("limit", a.get[Int]("limit").getOrElse(30))
        call(Method.GET, opt(a, "ruleId").fold(u)(r => u.withQueryParam("ruleId", r))).map(_.map(j =>
          j.as[List[AlertEventDto]].getOrElse(Nil).map { e =>
            s"${e.at}\t${e.state}\t${e.message}"
          }.mkString("\n")
        ))
    },
    Tool(
      "list_api_endpoints",
      "REST API の一覧。専用のツールがない操作（アラート・ダッシュボードの作成など）は call_api で行える。",
      schema(Nil),
      readOnly = true
    ) {
      (_, _) =>
        IO.pure(Right(ApiCatalog.all.map(e => s"${e.method} ${e.path} — ${e.description}").mkString("\n")))
    },
    Tool(
      "call_api",
      "任意の REST API を呼ぶ（画面でできることはすべてできる。list_api_endpoints を参照）。権限はこのキーの持ち主と同じ。",
      schema(
        List("method", "path"),
        "method" -> str("GET / POST / PUT / PATCH / DELETE"),
        "path" -> str("/api/ で始まるパス（クエリを含めてよい）"),
        "body" -> obj("JSON の本文")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "method").map(_.toUpperCase), need(a, "path")).tupled.withArgs {
          (m, p) =>
            (
              Method.fromString(m).toOption.filter(x => Set("GET", "POST", "PUT", "PATCH", "DELETE")(x.name)),
              Uri.fromString(p).toOption.filter(_.path.renderString.startsWith("/api/"))
            ) match {
              case (Some(method), Some(uri)) => call(method, uri, a.downField("body").focus.filterNot(_.isNull)).asText
              case (None, _) => IO.pure(Left("method は GET / POST / PUT / PATCH / DELETE です"))
              case _ => IO.pure(Left("path は /api/ で始めてください"))
            }
        }
    }
  )

  // ------------------------------------------------------------------ JSON-RPC の枠に渡すもの

  /** キーの持ち主と、この要求（バッチ）の tools/call が多すぎるか。 */
  private final case class Who(session: AuthSession, tooManyCalls: Boolean)

  private def isToolCall(msg: Json): Boolean = msg.hcursor.get[String]("method").contains("tools/call")

  /** ツールの実行（検索など重い処理）は、1 回の要求で MaxBatchCalls 件まで。バッチ全体の上限（50 件）と読めない本文は枠が先に断る。 */
  private def tooManyCalls(req: Request[IO]): IO[Boolean] =
    req.as[Json].attempt.map(_.toOption.flatMap(_.asArray).exists(batch =>
      batch.size <= MaxBatch && batch.count(isToolCall) > McpRoutes.MaxBatchCalls
    ))

  private val MaxBatch = 50

  private lazy val host = new McpServer.Host[Who, Caller] {
    val settings = McpServer.Settings(
      "skorimako",
      toolFailed = ErrorCode.McpToolFailed,
      maxBatch = MaxBatch,
      batchTooLarge = n => s"バッチは${n}件までです",
      readOnlyDenied = "参照専用の APIキーでは変更できません",
      hideDenied = false
    )
    val tools = McpRoutes.this.tools
    def session(req: Request[IO], auth: Authorization) =
      sessions.fromRequest(Request[IO](Method.GET, Uri.unsafeFromString("/mcp")).putHeaders(auth)).flatMap {
        case Some(s) if s.canRead => tooManyCalls(req).map(many => Some(Who(s, many)))
        case other => IO.pure(other.map(Who(_, tooManyCalls = false)))
      }
    def unauthorized = Errors.respond(ErrorCode.McpApiKeyRequired)
    // 取り込み専用のキーでは使えない
    override def refuse(w: Who) =
      if !w.session.canRead then Some(Errors.respond(ErrorCode.ApiKeyIngestOnly))
      else if w.tooManyCalls then
        Some(BadRequest(McpServer.rpcFail(
          Json.Null,
          McpServer.InvalidRequest,
          s"バッチの tools/call は${McpRoutes.MaxBatchCalls}件までです"
        )))
      else None
    // MCP 経由の印は要求の属性で渡す（ヘッダーだと外からも付けられる）
    def context(req: Request[IO], auth: Authorization, w: Who) =
      new McpCaller(
        api,
        auth,
        build = (m, u, b) => McpCaller.jsonRequest(m, u, b).withAttribute(SessionAuth.McpCall, ())
      )
    def canWrite(w: Who) = w.session.canWrite
    def instructions(w: Who) =
      "skorimako（ログ・メトリクス・トレース・フロントエンドの観測データの基盤）を調べるツールです。" +
        "list_streams でデータの入れ物を、get_stream_schema で列を確かめ、search_logs・query_metrics・search_traces・get_trace で調べます。" +
        "集計は query_sql（DuckDB の SQL）で自由に書けます。アラートの状態は list_alerts・list_alert_events。" +
        "専用のツールがない操作は list_api_endpoints で REST API を調べ、call_api で行えます。権限はこの APIキーの持ち主と同じです。"
  }
}

object McpRoutes {

  /** 1 回の要求（バッチ）で実行できる tools/call の数。 */
  val MaxBatchCalls = 10

  /** 1 回の要求の全体の上限。 */
  val Timeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(120).seconds
}
