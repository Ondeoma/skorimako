# skorimako

<p align="center"><img src="frontend/public/logo.svg" width="128" height="128" alt="skorimako のロゴ"></p>

skorimako は、チームのためのオブザーバビリティ基盤です（ログ・メトリクス・トレース・フロントエンドの観測データの収集と検索）。
OpenTelemetry Collector や SDK から送られたデータを、S3 互換ストレージの Parquet と PostgreSQL に保存し、画面・API・AI（MCP）から調べられます。ライセンス: MIT

- **取り込み**: OTLP/HTTP（`/v1/logs`・`/v1/metrics`・`/v1/traces`。protobuf・JSON、gzip）、OTLP/gRPC（4317 番）、Prometheus の remote write、
  JSON のログ（`/api/ingest/{ストリーム}/_json`・NDJSON の `_multi`）、ブラウザの観測（`/rum.js` を貼るだけ）。送り手は ingest スコープの APIキー（Bearer、または Basic のパスワード）
- **取り込みの変換と上限**: 個人情報を伏せる（メールアドレス・カード番号・電話番号・IP・トークン、または正規表現）、列を落とす、行を捨てる、
  間引く（トレースごとにそろえる）、列の名前を変える。ストリームごとの取り込みの上限（超えたら 429）
- **書き出す前の行も検索に出る**: 各ノードのバッファにも問い合わせるので、送ってすぐ検索・ライブ表示に出る
- **ログ**: ストリーム・時間の範囲・条件（SQL の WHERE）・キーワードで探す。時間ごとの件数のグラフ（ドラッグで範囲を狭める）、行を開いてすべての列、
  値で絞り込む・除く、列を一覧に足す、トレース ID からトレースへ、ライブ表示（2秒ごとに新しい行を足す）。SQL のタブで自由に集計
- **メトリクス**: メトリクスの名前ごとのストリーム。平均・合計・最小・最大・件数・最後の値・rate（カウンターの毎秒の増加）・p50〜p99、ラベルで分ける、条件、区切り。
  ヒストグラムは Prometheus と同じく `_bucket`（`le`）・`_sum`・`_count` に分けて保存。**PromQL**（rate・histogram_quantile・sum by など）と
  Prometheus の HTTP API（`/api/prom`。Grafana のデータソースとしてつなげる）。系列からそのサービスのトレースへ
- **トレース**: サービス・名前・所要時間・エラーで探す（トレースの索引で速く）、所要時間の散らばり、ウォーターフォール（親子の順・サービスの色分け・エラー）、
  スパンの属性とイベント、そのトレースのログへ
- **サービス**: トレースから、サービスごとの要求・エラー率・所要時間（p50・p95・p99）、サービス間の呼び出しの図、サービスごとの時系列
- **フロントエンドの観測（RUM）**: 表示の数・セッション・JavaScript のエラー・通信の失敗・Web Vitals（LCP・INP・CLS・FCP・TTFB の p75 と評価）、
  ページごと・エラーごと・ブラウザごと。SPA の画面の切り替えも数える。アプリごとのトークンと、送ってよいサイトの制限
- **ダッシュボード**: SQL のパネル（折れ線・棒・数値・表）を並べる。`$__interval` で時間の範囲に合わせた区切り
- **アラート**: 閾値（件数・平均・合計・パーセンタイル、列の値ごとに分けて）、SQL、**途絶**（データが来なくなったら。サービスごとにも）。
  発生・解消・発生中の再通知、**対応中（確認済み）**の印（再通知を止め、通知先にも知らせる）、**ミュート**（ルールごと・メンテナンスの時間帯）、試しに評価、履歴。
  通知先は **stui**（受信 Webhook、または OAuth で連携した各自の **DM**）・Slack / Mattermost 互換・Webhook（署名付き JSON）。送信待ちの表から再試行
- **ストリーム**: 列と型（自動で増える。型が食い違えば広げる）、行数・保存量・データの範囲、保持期間（ストリームごと）、削除、
  **閲覧できるグループ**（IdP のグループで見られる人を絞る）
- **検索の高速化**: ファイルごとに文字列の列の値の一覧を記録し、`列 = 値`・`列 IN (…)` の条件に合わないファイルは読まない
- **自己監視**: skorimako 自身の取り込み・書き出し・検索・アラート・JVM をメトリクスとして、警告・エラーをログとして自分に取り込む（「skorimako 自身」のダッシュボード）
- **API・AI**: ユーザーごとのスコープ付き APIキー（read・write・admin・ingest）、REST API（一覧は `GET /api/endpoints`）、
  MCP（`POST /mcp`。ログ・メトリクス・トレースを AI から調べ、SQL・PromQL で集計し、サービスの関係とアラートの状態を見る）。
  stui などがリンクの要約を読む OAuth（`/oauth/*`）と `POST /api/links/lookup`
- **認証・権限**: OIDC（Authorization Code + PKCE）、システム管理者・一般。ログインした人はすべてのデータを見られる（マルチテナントではない）
- **冗長化**: アプリは複数ノードで動く（取り込みはどのノードでもよい。検索・アラートの評価・圧縮・保持期間の処理もノードをまたいで正しく動く）。
  PostgreSQL は Patroni、観測データは S3 互換ストレージ
- レスポンシブ対応、ダークモード対応

設計は [docs/design.md](docs/design.md)、構築・運用は [docs/operations.md](docs/operations.md)、
stui への通知は [docs/integrations.md](docs/integrations.md) を参照してください。

兄弟のシステム: [stui](https://github.com/Ondeoma/stui)（チャット）、[skea](https://github.com/Ondeoma/skea)（タスク管理）、
[skiwi](https://github.com/Ondeoma/skiwi)（Wiki）、[skawau](https://github.com/Ondeoma/skawau)（ファイル共有）。同じ技術・同じ運用方針で作っています。

## 構成

| モジュール | 内容 |
|---|---|
| `modules/shared` | バックエンドと画面（Scala.js）で共有する API の型（circe） |
| `modules/core` | ドメインの純粋なロジック（値と列の型・列名の規則・SQL の検査と組み立て・アラートの判定） |
| `modules/db` | doobie のストア（ストリーム・ファイルの一覧・トレースの索引・アラート・ダッシュボード・ユーザー）、Flyway のマイグレーション、ジョブのリース |
| `modules/auth` | OIDC（Authorization Code + PKCE）、APIキー、暗号化 |
| `modules/storage` | S3 互換ストレージ（Parquet のファイル）。1台だけで動かすときのローカルのディレクトリ |
| `modules/engine` | OTLP の解釈、取り込みのバッファと WAL、Parquet の書き出し、DuckDB による検索、圧縮・保持期間 |
| `modules/push` | プッシュ通知（PWA。Web Push の暗号化と VAPID。アラートの発生・解消を、ルールを作った人と stui の DM の宛先の人に送る） |
| `modules/api` | http4s のルート（取り込み・REST・MCP）と、アラートの評価・通知のサービス |
| `modules/app` | 起動・設定（環境変数）・画面の配信・定期処理 |
| `modules/web` | 画面（[Scalactive](https://github.com/Ondeoma/scalactive) の `.st` 単一ファイルコンポーネント） |
| `frontend/` | Vite。Scala.js の出力と uPlot（グラフ）をまとめてビルドする。`public/rum.js` はブラウザの観測の SDK、`public/sw.js` は PWA の Service Worker（オフラインで開く・プッシュ通知） |
| `deploy/` | Docker（試験用の3ノード構成）と VM 構築用の設定のひな形 |

技術: Scala 3.3 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway、PostgreSQL 17、DuckDB（組み込み）、Apache Parquet、
OpenTelemetry Protocol、S3 互換ストレージ、Scala.js + Scalactive、uPlot。

## 開発

必要なもの: JDK 21、sbt、Node.js、Docker。

```bash
docker compose -f docker-compose.dev.yml up -d --wait   # PostgreSQL (:54383)、ScalaS3 (:3923)
scripts/dev-restart.sh                                   # バックエンドを :8101 で起動（scripts/dev-env.sh の設定。DEV_LOGIN=true）
(cd frontend && npm install && npm run dev)              # 画面の開発サーバー http://localhost:5181（/api などは :8101 へ中継）
python3 scripts/seed-demo.py 60                          # 直近60分のデモのデータ（3つのサービスのログ・トレース・メトリクス）を送る
sbt "api/Test/runMain skorimako.api.SendSmoke <APIキー>"   # OTLP/gRPC（:4319）と Prometheus の remote write を試す
scripts/smoke.sh                                         # 取り込みから検索・MCP までの動作確認
sbt test                                                 # テスト
```

3ノード構成の試験: `scripts/ha-up.sh`（入口 http://localhost:8102）。配布物: `scripts/dist.sh`（`dist/lib` と `dist/static`）。

## ライセンス

MIT
