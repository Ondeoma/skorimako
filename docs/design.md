# skorimako の設計

skorimako はチームのためのオブザーバビリティ基盤です（ログ・メトリクス・トレース・フロントエンドの観測）。
取り込んだデータを列指向のファイル（Parquet）にしてオブジェクトストレージに置き、メタデータを RDB に置く形にし、
兄弟プロジェクト（stui・skea・skiwi・skawau）と同じ技術・同じ運用方針で作ります。

## 1. 要件サマリ

| 区分 | 内容 |
|---|---|
| テナント | シングルテナント（1つの組織で1つの skorimako。ログインした人はすべてのデータを見られる） |
| 認証 | OIDC（Authorization Code + PKCE）。Cookie のセッション、ユーザー単位のスコープ付き APIキー（read・write・admin・ingest） |
| 取り込み | OTLP/HTTP（protobuf・JSON、gzip・deflate）、OTLP/gRPC、Prometheus の remote write、JSON・NDJSON のログ、ブラウザの SDK（RUM）。取り込みの変換（伏せる・落とす・間引く）と上限 |
| 保存 | 観測データは S3 互換ストレージの Parquet（zstd）。ストリーム・列・ファイルの一覧・トレースの索引・設定は PostgreSQL |
| 検索 | DuckDB（組み込み）の SQL。画面のログ・メトリクス・トレースの検索も SQL に組み立てて実行する。PromQL と Prometheus の HTTP API。書き出す前の行も出す |
| アラート | 閾値・SQL・途絶。発生・解消・再通知・対応中・ミュート。通知先は stui（チャンネル・各自の DM・受信 Webhook）・メール・Slack 互換・Webhook |
| 権限 | ストリームごとに閲覧できる IdP のグループを決められる（決めなければログインした全員） |
| 自己監視 | skorimako 自身の動きと JVM をメトリクスとして、警告・エラーをログとして自分に取り込む |
| 画面 | ホーム・ログ・メトリクス・トレース・フロントエンド・ダッシュボード・アラート・ストリーム・設定・管理 |
| AI | MCP（Model Context Protocol）。ログ・メトリクス・トレースの検索、SQL、アラート、任意の REST API |
| 基盤 | PostgreSQL（Patroni）、S3 互換ストレージ、アプリは複数ノード |
| 実装 | Scala 3.9 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway、DuckDB、opentelemetry-proto。画面は Scalactive（Scala.js）と uPlot |

## 2. 全体アーキテクチャ

```
  アプリ・OTel Collector・ブラウザ（rum.js）        画面・REST API・MCP
           │ OTLP/HTTP・JSON・RUM                       │
   ┌───────▼────────────────────────────────────────────▼───┐   VIP(keepalived) + HAProxy
   │ node1 node2 node3（どのノードでも受ける・検索する）          │
   │   取り込み: メモリのバッファ + ローカルの WAL                 │
   │       └─ 数秒ごとに Parquet にして S3 へ、PostgreSQL に登録   │
   │   検索: 対象のファイルをキャッシュに取り、DuckDB で SQL        │
   │   アラートの評価・通知、圧縮・保持期間（ノードをまたいで1回）   │
   └──────┬──────────────────────────────┬──────────────────┘
          │                              │
   PostgreSQL（Patroni）            S3 互換ストレージ
   ストリーム・列・ファイルの一覧       {種類}/{ストリーム}/{年}/{月}/{日}/{時}/*.parquet
   トレースの索引・アラート・設定
```

- 取り込みはどのノードで受けてもよい（ロードバランサーは単純なラウンドロビン）。各ノードは受けた行をメモリにため、同時にローカルの WAL に追記してから応答する。
  `FLUSH_SECONDS`（既定10秒）ごと、またはストリームに `FLUSH_ROWS` 行たまったら Parquet にして S3 に置き、PostgreSQL のファイルの一覧に登録してから WAL を消す。
  書き出す前の行はそのノードだけにあるが、検索するノードは自分のバッファと、ほかのノード（`node` の表に各ノードが30秒ごとに登録する `NODE_URL`）の
  バッファにも問い合わせ（`GET /internal/buffer`。APP_SECRET から作る署名と期限付き）、一時の Parquet にしてビューに加える。
  返事のないノードは飛ばす（3秒で諦める）。取り込んだ時点でストリームを登録するので、まだ一度も書き出していないストリームも名前で検索できる。
- ノードが落ちても、WAL に書いた行は再起動時に読み直して書き出す（`WAL_SYNC=true` で受けるたびに fsync。OS ごと落ちても失わないが遅くなる）。
  書き出しに失敗したストリームの行はバッファと新しい WAL に戻し、次の回に再試行する。
- たまりすぎたら（`MAX_BUFFERED_ROWS`）503 で断る。OTLP の送り手（Collector）は 503 を再試行する。
- 検索は、SQL が参照するストリームの「時間の範囲に重なるファイル」を PostgreSQL で引き、S3 からノードのキャッシュ（`DATA_DIR/cache`）に取ってきて DuckDB で読む。
  キャッシュは `CACHE_MAX_GB` を超えたら使ったのが古いものから消す。
- バックグラウンドの処理: アラートの評価と通知は全ノードで動かし、行の取り合い（次の評価の時刻を先に進める・`FOR UPDATE SKIP LOCKED`）で二重にしない。
  圧縮・保持期間・掃除は `job_lock`（期限付きのリース）で1ノードだけが行う。

## 3. データのモデル

### 3.1 ストリームと列

- ストリームは「種類（logs・metrics・traces・rum）＋名前」。ログ・トレースは送り手が `stream-name` ヘッダーで選ぶ（既定 `default`）。
  メトリクスはメトリクスの名前ごとに1つ（`http.server.duration` → `http_server_duration`）。RUM はアプリごとに設定したストリーム。
- 列はデータに合わせて自動で増える。属性の名前は英小文字・数字・`_` に直す（`service.name` → `service_name`）。入れ子の属性は `_` でつなぐ。
- 列の型は utf8・int64・float64・boolean。同じ列に違う型の値が来たら広げる（int64 と float64 は float64、それ以外は utf8）。型は `stream_field` に記録し、
  ファイルごとに型が違っても DuckDB の `union_by_name` で合わせて読む。
- どの種類にも `_timestamp`（UNIX エポックからのマイクロ秒、int64）がある。

| 種類 | 主な列 |
|---|---|
| logs | `body`（文字列。構造化された本文は JSON の文字列にし、`body_*` にも展開）、`severity_text`・`severity_number`、`trace_id`・`span_id`、`service_name`、`scope_name`、リソースとレコードの属性 |
| traces | `trace_id`・`span_id`・`parent_span_id`、`operation_name`、`span_kind`、`end_time`・`duration`（マイクロ秒）、`status_code`（UNSET・OK・ERROR）・`status_message`、`events`・`links`（JSON）、`service_name`、属性 |
| metrics | `__name__`、`__type__`（gauge・counter・delta_counter・updowncounter・histogram・summary）、`value`（double）、ラベル（リソースと点の属性）。ヒストグラムは `{名前}_bucket`（`le`。le 以下の累積の件数）・`_sum`・`_count`、指数ヒストグラムは `_sum`・`_count`・`_min`・`_max`、サマリーは `quantile` のラベル |
| rum | `event_type`（view・route・error・resource_error・custom）、`app`、`session_id`・`view_id`、`url`・`path`・`referrer`・`title`、`lcp`・`fcp`・`inp`・`cls`・`ttfb`・`load_time`、`message`・`error_type`・`stack`、`request_url`・`status`・`duration`、`browser`・`os`・`device`、独自の属性 |

### 3.2 PostgreSQL のテーブル（詳細は `modules/db/src/main/resources/db/migration`）

- `stream(id, stream_type, name, retention_days, description, unit, metric_type, doc_count, stored_bytes, file_count, min_ts, max_ts)`
- `stream_field(stream_id, name, field_type)`
- `data_file(id, stream_id, s3_key, min_ts, max_ts, row_count, size_bytes, level, node, deleted_at)` … level 0 は取り込み、1 以上は圧縮でまとめたもの
- `trace_index(stream_id, trace_id, min_ts, max_ts, root_service, root_name, services, span_count, error_count)` … トレースごとに1行（書き出しのたびに足し合わせる）
- `ingest_stat(day, stream_id, rows, bytes)`
- `rum_app(id, name, token, origins, stream)`
- `alert_rule`・`alert_event`・`alert_destination`・`alert_delivery`（送信待ちの表）
- `dashboard(id, name, description, panels jsonb)`
- `app_user`・`user_group`・`group_member`・`session`・`login_state`・`api_key`・`job_lock`・`audit_log`

### 3.3 S3 のキー

`{種類}/{ストリーム}/{年}/{月}/{日}/{時}/{ノード}-{UUID}.parquet`（時刻はファイルの最も古い行の UTC）。圧縮したものは `c-` を付ける。
ファイルの中は `_timestamp` の順に並べ、zstd で圧縮する（行グループ 122,880 行）。

## 4. 検索

- 利用者の SQL は「SELECT・WITH・FROM で始まる1つの文」だけを受け付ける（字句に分けて、文の区切り `;` と括弧の対応を確かめる）。
- SQL の中の名前のうち、その種類のストリームの名前と一致するものを参照しているストリームとみなす。
- 1回の検索ごとにメモリ上の DuckDB を作り、ストリームを同じ名前のビューにする:
  `SELECT * FROM read_parquet([…キャッシュのファイル…], union_by_name = true) UNION ALL BY NAME（登録済みの列の空の行）WHERE _timestamp BETWEEN 始まり AND 終わり`。
  ファイルがない列・ファイルがないストリームでも、登録済みの列で検索できる。
- ビューを作った後で `allowed_directories`（キャッシュのディレクトリだけ）・`enable_external_access = false`・`lock_configuration = true` にする。
  利用者の SQL からは、手元のほかのファイル・ネットワーク・拡張の読み込み・設定の変更ができない。
- 同時に動かす検索の数（`QUERY_CONCURRENCY`）、1回の DuckDB のメモリ（`DUCKDB_MEMORY`。超えた分は一時ファイルに逃がす）、時間（`QUERY_TIMEOUT_SECONDS`。過ぎたら文を止める）、
  返す行（最大 10,000 行）に上限がある。
- 画面の検索は SQL に組み立てる（`QueryBuilder`）:
  - ログ: 条件（利用者の WHERE を括弧で囲む）＋キーワード（本文の列の小文字の部分一致）、一覧・件数・時間ごとの件数
  - メトリクス: `(_timestamp // 区切り) * 区切り` ごとの集計。rate はラベルの組ごとに前の点との差（減ったらリセットとみなしてその値）を足し、区切りの秒数で割る
  - トレースの一覧はトレースの索引（PostgreSQL）から。トレースの詳細は索引の時間の範囲でスパンを読む
- ダッシュボード・アラートの SQL の `$__interval` は区切りの長さ（マイクロ秒）に置き換える。

- 閲覧の権限: ストリームに閲覧できるグループ（`allowed_groups`）を決めると、その所属者と管理者だけが見られる。一覧・SQL（参照しているストリーム）・ログ・メトリクス・
  トレース（索引の一覧と詳細）・サービス・PromQL・リンクの要約・アラートの作成で確かめる（30秒ごとに読み直す）。
- ファイルの絞り込み: 取り込みで書くファイルごとに、文字列の列の値の一覧（種類が32以下、値が200文字以下の列だけ）を `data_file.tags` に記録する。
  条件の一番外側が AND の `列 = '値'`・`列 IN ('a', 'b')` があれば、その値を含まないファイルは読まない（圧縮でまとめたファイルは値の一覧の和）。
- PromQL（`/api/prom/api/v1/query`・`query_range`・`labels`・`label/{name}/values`・`series`・`metadata`）: 式を構文解析し（`PromQL`）、
  セレクターの対象（メトリクスのストリーム）を、範囲・offset・直近5分の分を足した時間だけ読み、各時刻で評価する（`Prom`）。
  直近5分の最後の点、rate・increase の外挿、histogram_quantile の線形補間、by・without、on・ignoring・group_left など Prometheus に合わせる。
  ラベルの名前は列名の規則（英小文字・数字・_）。ラベルの値の候補はファイルの値の一覧から。

## 4.1 取り込みの変換・上限・プロトコル

- 取り込みの入口（HTTP・gRPC・remote write）は共通（`IngestService`）。ストリームごとの変換（`ingest_rule`。position の順。stream が * ならその種類のすべて）を
  当ててからバッファに入れる。mask（既定の型: email・card・phone・ipv4・token、または正規表現）・drop_field（カンマ区切り、末尾の * で前方一致）・
  drop_rows（列の値が正規表現に合う行）・sample（割合。trace_id があればそのハッシュで決め、トレースの全スパンをそろえる）・rename。
- 取り込みの上限（`stream.max_rows_per_minute`）はノードごとに1分の枠で数え、超えたら 429（送り手は再試行・間引く）。ルールと上限は30秒ごと（変更時はすぐ）に読み直す。
- OTLP/gRPC: `GRPC_PORT`（既定 4317）で LogsService・TraceService・MetricsService を受ける。認証はメタデータの `authorization`、ストリームは `stream-name`。
  バッファがあふれたら UNAVAILABLE、上限なら RESOURCE_EXHAUSTED（送り手が再試行する）。
- Prometheus の remote write: snappy の protobuf の WriteRequest を読み、`__name__` ごとのストリームにする（ラベルは列、`__type__` は prometheus、metadata があれば種類・説明・単位）。

## 5. 圧縮・保持期間

- 圧縮（5分ごと、1ノード）: 書いてから5分たった 256MB 未満のファイルを、ストリーム・時間（1時間）ごとに束ね、1つの Parquet にまとめる。
  まとめたファイルを登録し、元のファイルには削除の印を付ける（1つのトランザクション。ほかで先に消されていたらやめる）。
- 保持期間（30分ごと、1ノード）: ストリームの `retention_days`（なければ `RETENTION_DAYS`）を過ぎたファイルに削除の印を付け、トレースの索引も消す。
- 削除の印を付けて1時間たったファイルを S3 とキャッシュから消し、行を消す（その間に始まった検索が読み終えられるように）。

## 6. アラート

- ルール: 対象（種類・ストリーム）、方法（閾値・SQL）、比べ方（`> >= < <= == !=`）と閾値、期間（直近◯分）、評価の間隔、再通知の間隔、通知先。
  - 閾値: `SELECT [列 AS grp,] 集計 AS value FROM ストリーム WHERE 条件 [GROUP BY 1]`。集計は件数・平均・合計・最小・最大・p50〜p99
  - SQL: 結果の `value`（なければ最初の数値の列）を比べ、`grp`（なければ最初の文字列の列）を分け方として通知に出す
  - 当てはまる行が1つでもあれば「発生」
- 状態: 正常 → 発生（通知）、発生 → 正常（解消を通知）、発生中は再通知の間隔ごとに再び通知（0 ならしない）。評価できない（SQL の誤りなど）は「評価できない」として履歴に残す。
- 評価: 10秒ごとに全ノードで、評価の時刻が来たルールを `FOR UPDATE SKIP LOCKED` で取り、次の評価の時刻を先に進めてから評価する（二重に評価しない）。
- 通知: 履歴（`alert_event`）と同じトランザクションで、通知先ごとに送信待ちの行（`alert_delivery`）を作る。全ノードが SKIP LOCKED で取り合って送り、
  失敗したら30秒から倍々（最大1時間）で8回まで再試行する。404・410 はやめる。
- 通知の形:
  - stui: `{"username": "skorimako", "text": "🔴 **[発生] ルール名**\n…\n[詳しく見る](URL)"}`（stui の受信 Webhook。Markdown）
  - Slack・Mattermost 互換: 同じ形で、Slack の mrkdwn（`*太字*`、`<URL|文字>`）
  - Webhook: `{"event": "alert.firing|alert.resolved", "rule": {...}, "value", "hits", "message", "url", "at"}`、署名の鍵があれば `X-Skorimako-Signature: sha256=<HMAC>`
- 通知先の URL はプライベートの IP も許す（stui は社内のネットワークに置くことが多いため）。`WEBHOOK_DENY_PRIVATE=true` で断る。通知先の追加・変更は管理者だけ
  （自分宛ての stui の DM はだれでも）。
- 途絶（mode absence）: `lookback_minutes` の範囲で来ていたデータ（groupBy の値ごと）の最後の行から、`period_minutes` 以上たっていれば発生。値は最後の行からの分。
  groupBy がなければストリーム全体で、範囲に1行もなくても発生する。
- 対応中（確認済み）: 発生中のルールに印を付けると、通知先に「👀 対応中」を送り、解消するまで再通知しない。解消すると外れる。
- ミュート（`alert_silence`）: ルールごと、またはすべてのルール（管理者。メンテナンスの時間帯）。評価と履歴は続け、送信待ちを作らない（履歴に「ミュート中」の印）。
- stui の DM（kind stui_dm）: 宛先の人が stui と OAuth で連携していれば、その人のトークンで stui の `POST /api/apps/notify` を呼ぶ（401 なら連携を外し、送るのをやめる）。
- stui のチャンネル（kind stui_channel）: url はチャンネルの ID、target_user_id は通知先を保存した人。保存するときに、その人が参加しているチャンネルかを
  stui の `GET /api/channels` で確かめる。送るときはその人のトークンで `POST /api/apps/notify`（channel 付き）を呼ぶ（401 は連携を外す。403・404 は送り直さない）。
- メール（kind email）: url は宛先のアドレス（カンマ区切り・10 件まで）。SMTP（`SMTP_HOST` など。sbirds-lib の Mailer）で宛先ごとに 1 通送る。未設定なら送らない。

## 7. フロントエンドの観測（RUM）

- 管理者がアプリを登録すると、トークン（`rum_…`。ブラウザに見えてよい、取り込みだけに使える値）と、ページに貼る `<script src="…/rum.js" data-token="…" defer>` を発行する。
- `/rum.js`（`frontend/public/rum.js`、依存なし）が送るもの: 表示（ページを離れる・隠れるときに Web Vitals と読み込みの時間を確定して送る）、SPA の画面の切り替え、
  JavaScript のエラー・Promise の拒否・リソースの読み込みの失敗、fetch・XMLHttpRequest の失敗（HTTP 400 以上・つながらない）、`skorimako.track()` の独自のイベント。
  5秒ごと・50件ごとにまとめて送り、ページを離れるときは sendBeacon（text/plain なので CORS の事前確認が要らない）。セッションは同じタブで30分。`data-sample-rate` で間引ける。
- `POST /rum/v1/{トークン}`: アプリの「送ってよいサイト」と Origin を照合する（空か `*` ならどこでも）。User-Agent からブラウザ・OS・端末の種類を足す。
  ブラウザの時計が24時間以上ずれていればサーバーの時刻にする。
- アプリを登録するときに、SDK が送る列を先にストリームに登録しておく（まだ送られていない列も画面の集計で使える）。

## 8. 認証・認可

- ログイン: OIDC（Authorization Code + PKCE）。ID token の `iss`/`aud`/`exp`/`nonce` を検証。開発環境では `DEV_LOGIN=true` で名前だけでログインできる。
- APIキー: `sko_` で始まるランダム値。ハッシュだけを保存。スコープ: `read`（検索・一覧）・`write`（アラート・ダッシュボードの編集）・`admin`（管理者だけ）・`ingest`（取り込みだけ）。
  取り込みは ingest か write、検索は read・write・admin のキー。キーでキーの発行・失効はできない。
- 権限: ログインした人はすべてのストリームを見られ、アラート・ダッシュボードを作れる。ストリームの設定・削除、通知先、RUM のアプリ、ユーザーの管理は管理者だけ。
- 共通の守り（`Guards`）: 本文の上限（取り込みは `MAX_INGEST_MB`、展開後 256MB、それ以外は 2MB）、Cookie のセッションで状態を変える要求は Origin が別のサイトなら断る、
  OIDC の state はブラウザの Cookie にも入れて照合、セキュリティのヘッダー（iframe の禁止・nosniff・Referrer-Policy・https なら HSTS）。
- 監査ログ: ログイン・APIキー・ストリーム・アラート・通知先・ダッシュボード・RUM のアプリ・ユーザーの変更。

## 8.1 stui との連携

- クライアント: `STUI_URL`・`STUI_CLIENT_ID`・`STUI_CLIENT_SECRET`。各自が設定の画面から stui の `/oauth/authorize` で許可し（PKCE、scope read write）、
  トークンを APP_SECRET の鍵で暗号化して `integration_token` に保存する。連携をやめると stui の `/oauth/revoke` を呼ぶ。
- 認可サーバー: `OAUTH_CLIENTS` に stui を登録すると、stui が skorimako の `/oauth/*` で認可を受け、`POST /api/links/lookup` でトレース・アラート・ダッシュボード・
  ログ・メトリクスの URL の要約を読める（stui と同じ実装。使用済みのコードが再び出されたら、そのコードで発行したキーを失効させる）。

## 8.2 自己監視

- `Stats` に各処理（取り込み・書き出し・検索・ファイルの絞り込み・アラートの評価と通知・キャッシュ）の数を集め、30秒ごとに `skorimako_*` のメトリクス
  （カウンターとゲージ。ラベル node・service_name=skorimako）と JVM の `jvm_*`・`process_*` を、自分の取り込みの入口から入れる。
- 警告・エラー（書き出しの失敗・ノードへの問い合わせの失敗など）は標準出力に出し、ログのストリーム `skorimako` にも入れる。
- 起動時に1ノードが「skorimako 自身」のダッシュボードを作る（パネルが読むストリームと列も先に作る）。`SELF_MONITOR=false` で止める。

## 9. MCP

- `POST /mcp`（Streamable HTTP、JSON 応答）。APIキー（Bearer）だけを受け付ける（ingest だけのキーは不可）。
- ツール: `get_me`・`list_streams`・`get_stream_schema`・`search_logs`・`query_sql`・`query_metrics`・`query_promql`・`search_traces`・`get_trace`・`service_map`・`list_alerts`・
  `list_alert_events`、そして画面でできるすべてのための `list_api_endpoints`・`call_api`。どのツールも内部で REST API を同じキーで呼ぶ。

## 10. 冗長構成

- 各ノードに skorimako-app、Patroni + PostgreSQL、etcd。入口は keepalived（VIP）と HAProxy（ラウンドロビン、`/healthz` で確認）。S3 互換ストレージは冗長化したものを使う。
- `/healthz` は DB のプライマリに書けるときだけ 200。DB の URL は複数ホスト + `targetServerType=primary`。
- 起動時のマイグレーションは Flyway のロックで1回だけ適用される。
- ノードごとに `DATA_DIR`（WAL・キャッシュ・一時ファイル）を速いディスクに置く。ノードを外すときは止める（止めるときにバッファを書き出す）。
  止められずに失ったノードの WAL は、同じ `DATA_DIR` で別のノードを起動すれば書き出せる。

## 11. 今後の候補

- PromQL の残り（`@` 修飾子、`predict_linear`・`holt_winters` などの関数、ネイティブヒストグラム）と、Prometheus の recording rule
- 高い種類の列（trace_id など）の Bloom フィルターをファイルごとに持ち、読むファイルをさらに減らす
- stui 側で、skorimako のリンクの要約（`/api/links/lookup`）を問い合わせる
- アラートのエスカレーション（対応中にならなければ別の通知先へ）
