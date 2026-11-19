package skorimako.api

/** REST API の一覧（MCP の list_api_endpoints と GET /api/endpoints）。 */
object ApiCatalog {
  final case class Endpoint(method: String, path: String, description: String)
  val all: List[Endpoint] = List(
    Endpoint("GET", "/api/me", "自分の情報"),
    Endpoint("GET", "/api/overview", "ホーム（ストリーム・発生中のアラート・最近のアラートの履歴・今日の取り込みの行数）"),
    Endpoint("GET", "/api/streams?type=logs|metrics|traces|rum&q=", "ストリームの一覧（行数・大きさ・時刻の範囲）"),
    Endpoint("GET", "/api/streams/{type}/{name}", "ストリームの詳細と列の一覧"),
    Endpoint("PATCH", "/api/streams/{type}/{name}", "ストリームの設定（管理者） {retentionDays?: 0 は既定, description?}"),
    Endpoint("DELETE", "/api/streams/{type}/{name}", "ストリームとデータを削除（管理者）"),
    Endpoint(
      "POST",
      "/api/query/sql",
      "SQL（DuckDB）で検索 {streamType, sql, start, end, limit?}。FROM にストリームの名前。時刻はマイクロ秒。$__interval は区切りの長さ"
    ),
    Endpoint(
      "POST",
      "/api/query/logs",
      "ログの検索 {stream, start, end, where?: SQL の条件, keyword?, limit?, offset?, order?: desc|asc, streamType?}"
    ),
    Endpoint(
      "POST",
      "/api/query/metrics",
      "メトリクスの時系列 {metric, start, end, agg: avg|sum|min|max|count|last|rate|p50|p90|p95|p99, groupBy: [ラベル], where?, stepSeconds?}"
    ),
    Endpoint(
      "POST",
      "/api/traces/search",
      "トレースの検索 {start, end, service?, name?, minDurationMs?, errorsOnly?, stream?, limit?}"
    ),
    Endpoint("GET", "/api/traces/services?start=&end=", "サービスの一覧（トレースの数・エラーの数）"),
    Endpoint("GET", "/api/traces/{traceId}", "トレースのスパンをすべて"),
    Endpoint("GET", "/api/services/map?stream=default&start=&end=", "サービスごとの要求・エラー・所要時間と、サービス間の呼び出し（トレースから）"),
    Endpoint("GET", "/api/services/series?service=&start=&end=", "サービスの時系列（要求・エラー・p95）"),
    Endpoint(
      "GET|POST",
      "/api/prom/api/v1/query_range?query=&start=&end=&step=",
      "PromQL（Prometheus の HTTP API と同じ。start・end は UNIX 秒）"
    ),
    Endpoint("GET", "/api/alerts", "アラートのルール（状態つき）"),
    Endpoint("POST", "/api/alerts/{ruleId}/ack", "確認済み（対応中）にする {note}（DELETE で外す）"),
    Endpoint("GET", "/api/silences?all=true", "ミュート（メンテナンスの時間帯）"),
    Endpoint("POST", "/api/silences", "ミュートする {ruleId?: なければすべて（管理者）, reason, minutes? または startsAt・endsAt}"),
    Endpoint("DELETE", "/api/silences/{silenceId}", "ミュートを終える"),
    Endpoint("GET", "/api/ingest/rules", "取り込みの変換（管理者）"),
    Endpoint(
      "POST",
      "/api/ingest/rules",
      "取り込みの変換を作る（管理者） {streamType, stream, position, kind: mask|drop_field|drop_rows|sample|rename, field, pattern, replacement, ratio, enabled}"
    ),
    Endpoint("POST", "/api/links/lookup", "リンクの要約 {urls}（トレース・アラート・ダッシュボード・ログ・メトリクスの URL）"),
    Endpoint("POST", "/api/links/titles", "リンクの名前（stui の連携の形） {ids: [traces/…, alerts/…, dashboards/…]} → {links}"),
    Endpoint(
      "POST",
      "/api/alerts",
      "ルールを作る {name, description, enabled, streamType, stream, mode: threshold|sql, where?, agg, field?, groupBy?, sql?, op, threshold, periodMinutes, everyMinutes, silenceMinutes, destinationIds}"
    ),
    Endpoint("POST", "/api/alerts/test", "ルールを試しに評価する（通知しない。本文は作るときと同じ）"),
    Endpoint("GET", "/api/alerts/{ruleId}", "ルール"),
    Endpoint("PUT", "/api/alerts/{ruleId}", "ルールを変える"),
    Endpoint("DELETE", "/api/alerts/{ruleId}", "ルールを削除"),
    Endpoint("GET", "/api/alerts/events?ruleId=&limit=", "アラートの履歴（発生・解消）"),
    Endpoint("GET", "/api/destinations", "通知先の一覧"),
    Endpoint("POST", "/api/destinations", "通知先を作る（管理者） {name, kind: stui|slack|webhook, url, secret?}"),
    Endpoint("POST", "/api/destinations/{destinationId}/test", "通知先に試しに送る（管理者）"),
    Endpoint("GET", "/api/deliveries", "通知の送信の記録（管理者）"),
    Endpoint("GET", "/api/dashboards", "ダッシュボードの一覧"),
    Endpoint(
      "POST",
      "/api/dashboards",
      "ダッシュボードを作る {name, description, panels: [{id, title, kind: line|bar|stat|table, streamType, sql, width: 1-4, unit}]}"
    ),
    Endpoint("GET", "/api/dashboards/{dashboardId}", "ダッシュボード"),
    Endpoint("PUT", "/api/dashboards/{dashboardId}", "ダッシュボードを変える"),
    Endpoint("DELETE", "/api/dashboards/{dashboardId}", "ダッシュボードを削除"),
    Endpoint("GET", "/api/rum/apps", "フロントエンドの観測のアプリ"),
    Endpoint("POST", "/api/rum/apps", "アプリを作る（管理者） {name, origins, stream?}"),
    Endpoint("GET", "/api/admin/users", "ユーザーの一覧（管理者）"),
    Endpoint("PATCH", "/api/admin/users/{userId}", "ユーザーを変える（管理者） {isAdmin?, disabled?}"),
    Endpoint("GET", "/api/admin/audit", "監査ログ（管理者）"),
    Endpoint("POST", "/v1/logs・/v1/metrics・/v1/traces", "OTLP/HTTP の取り込み（ingest スコープ。stream-name ヘッダーでストリーム）"),
    Endpoint("POST", "/api/ingest/{stream}/_json", "JSON のログの取り込み（オブジェクトかその配列。_multi は NDJSON）")
  )
}
