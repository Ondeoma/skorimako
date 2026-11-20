# 構築・運用ガイド（VM 3台構成）

設定ファイルのひな形は [deploy/vm/](../deploy/vm/) にあります。IPアドレスは例です（`10.0.0.11〜13` が各VM、`10.0.0.10` がVIP）。
構成は兄弟プロジェクト（stui・skea・skiwi・skawau）と同じです。パッケージ名・パスは Debian/Ubuntu 系で書いています。

> 同じ構成を Docker 上で再現した試験環境があります（`docker-compose.ha.yml`、起動は `scripts/ha-up.sh`）。

## 1. 全体像

```
     送り手（OTel Collector・SDK・ブラウザ）・利用者 ──HTTPS──▶ VIP 10.0.0.10 (keepalived)
                                                       │
   ┌──────────────────────┬────────────────────────────┴┬──────────────────────┐
   │ obs1 10.0.0.11       │ obs2 10.0.0.12               │ obs3 10.0.0.13       │
   │  HAProxy :443        │  HAProxy                     │  HAProxy             │  ← 3台とも同じ設定。VIPを持つ1台が受ける
   │  skorimako-app :8080 │  skorimako-app               │  skorimako-app       │  ← どのノードでも受け・検索する
   │   └ DATA_DIR（WAL・キャッシュ。速いディスク）                                    │
   │  Patroni+PG17 :5432  │  Patroni+PG17                │  Patroni+PG17        │  ← 1台がプライマリ、2台がレプリカ
   │  etcd :2379          │  etcd                        │  etcd                │  ← Patroni のリーダー選出
   └──────────────────────┴──────────────────────────────┴──────────────────────┘
                                   │
                         S3 互換ストレージ（冗長化したもの。観測データの Parquet）
```

| 役割 | 冗長性 | 1台停止時 |
|---|---|---|
| HAProxy + keepalived | VIP が生きている VM へ移る | 数秒で切替 |
| skorimako-app | 3台すべてで稼働。取り込んだ行は数秒ごとに S3 と DB へ書き出す。セッションも DB | HAProxy のヘルスチェックで外れる（約4秒）。送り手（Collector）は失敗した送信を再試行する。止まったノードの書き出す前の行は、そのノードの WAL に残り、起動すると書き出す |
| バックグラウンド処理 | アラートの評価・通知は全ノードで取り合う（二重にしない）。圧縮・保持期間・掃除は `job_lock` のリースで1台だけ | 残ったノードが引き継ぐ |
| PostgreSQL（Patroni） | 非同期レプリケーション、自動フェイルオーバー | プライマリ停止時は短い間書き込みが止まる（その間の取り込みは書き出しを再試行し、たまりすぎたら 503 で送り手に再送させる） |
| etcd | 3台で合意（過半数） | 1台停止は影響なし |
| S3 互換ストレージ | ストレージ側で複製 | 読み書き継続 |

### 通信ポート（VM間）
| ポート | 用途 |
|---|---|
| 443/80 | 利用者・送り手 → HAProxy |
| 4317 | 送り手 → HAProxy → skorimako-app（OTLP/gRPC。TCP のまま振り分ける） |
| 8080 | HAProxy → skorimako-app、ノード間（書き出す前の行の問い合わせ `/internal/buffer`） |
| 5432 | skorimako-app → PostgreSQL、レプリケーション |
| 8008 | Patroni REST API |
| 2379/2380 | etcd |
| VRRP（IP プロトコル 112） | keepalived |

外部への通信: IdP（OIDC）、S3 互換ストレージ、アラートの通知先（stui・Slack・Webhook）。

## 2. 構築手順

### 2.1 共通
- 時刻同期（chrony）、ホスト名解決、ファイアウォール。Java 21。DuckDB のネイティブのライブラリを使うため、glibc の Linux（x86_64・arm64）にする。

### 2.2 etcd（3台）
[etcd.env](../deploy/vm/etcd.env) を各ノード用に書き換えて配置し、`systemctl enable --now etcd`。`etcdctl endpoint health --cluster` で確認。

### 2.3 PostgreSQL 17 + Patroni（3台）
1. PostgreSQL 17（PGDG）を導入。OS 標準の `postgresql` サービスは無効化する（拡張は要らない）。
2. Patroni（`pip install "patroni[etcd3]"`）。[patroni.yml](../deploy/vm/patroni.yml) をノードごとに書き換えて配置。
   `post_bootstrap` には [post_bootstrap.sh](../deploy/docker/post_bootstrap.sh) を使う（`SKORIMAKO_DB_PASSWORD` を環境変数で渡す）。
3. 1台目から順に `systemctl enable --now patroni`。`patronictl -c /etc/patroni/patroni.yml list` で Leader 1台・Replica 2台を確認。
4. アプリの接続先は3台を並べた JDBC URL（`targetServerType=primary`）。

### 2.4 S3 互換ストレージ
パス形式でアクセスできる S3 互換ストレージを用意し、アプリ用のアクセスキーを発行する（バケット `skorimako`。なければ起動時に作る。
ほかの用途と共用するときは `S3_PREFIX`）。観測データの量に合わせて容量を見積もる（Parquet + zstd で、ログはおおむね元の JSON の 1/5〜1/10）。

### 2.5 skorimako-app（3台）
1. 開発機で `scripts/dist.sh` → `dist/`（`lib/*.jar` と `static/`）を各 VM の `/opt/skorimako/releases/<日付>/` に置き、`/opt/skorimako/current` をシンボリックリンクで向ける。
2. `useradd -r -s /usr/sbin/nologin skorimako`、`install -d -o skorimako /var/lib/skorimako`（`DATA_DIR`。SSD を推奨）。
   [skorimako.env](../deploy/vm/skorimako.env) を `/etc/skorimako/skorimako.env`（0600）に、[skorimako.service](../deploy/vm/skorimako.service) を
   `/etc/systemd/system/` に置き、`NODE_ID` をノードごとに変える。`APP_SECRET` は3台共通の長いランダム値（`openssl rand -base64 32`）。
3. `systemctl enable --now skorimako`。初回起動時に1台が DB マイグレーションを行う（他は Flyway のロック待ち）。
4. 確認: `curl http://10.0.0.11:8080/healthz` → `ok node1`。

### 2.6 HAProxy + keepalived（3台）
[haproxy.cfg](../deploy/vm/haproxy.cfg)・[keepalived.conf](../deploy/vm/keepalived.conf) を配置（`priority` をノードごとに変える）。
`timeout server` は検索の上限（`QUERY_TIMEOUT_SECONDS`）より長くする。`PUBLIC_URL` は `https://observe.example.com`（Cookie に Secure が付く）。

### 2.7 IdP（OIDC）
クライアントを登録し、リダイレクト URI を `{PUBLIC_URL}/auth/callback` にする。グループのクレーム（既定 `groups`）を ID token に入れると、
`ADMIN_GROUPS` に入っている人が管理者になる。

## 3. 送り手の設定

1. 画面の「設定」で **ingest** スコープの APIキーを作る（送り手には検索のできないキーを使う）。
2. OpenTelemetry Collector の例（画面の「設定」にも、作ったキー入りの例が出る）:

```yaml
exporters:
  otlphttp/skorimako:
    endpoint: https://observe.example.com
    headers:
      Authorization: "Bearer sko_…"
      stream-name: default        # ログ・トレースのストリーム（省略時 default）
    compression: gzip
service:
  pipelines:
    logs:    { receivers: [otlp], processors: [batch], exporters: [otlphttp/skorimako] }
    traces:  { receivers: [otlp], processors: [batch], exporters: [otlphttp/skorimako] }
    metrics: { receivers: [otlp], processors: [batch], exporters: [otlphttp/skorimako] }
```

- OTLP/HTTP だけを受ける（gRPC の 4317 番では受けない）。protobuf・JSON、gzip・deflate。
- Basic 認証しか使えない送り手は、パスワードに APIキーを入れる（ユーザー名は何でもよい）。
- 任意の JSON のログ: `POST /api/ingest/{ストリーム}/_json`（オブジェクトかその配列）、`/_multi`（NDJSON）。時刻は `_timestamp`・`@timestamp`・`timestamp`・`time`・`ts`
  （数値は秒・ミリ秒・マイクロ秒・ナノ秒を桁で見分ける。文字列は ISO 8601）。
- ブラウザ: 画面の「フロントエンド」→「アプリと埋め込み」でアプリを登録し、表示された `<script>` をページに貼る。

## 4. 設定（環境変数）

| 変数 | 既定 | 内容 |
|---|---|---|
| `PUBLIC_URL` | （必須） | 利用者から見た URL |
| `DATABASE_URL`・`DATABASE_USER`・`DATABASE_PASSWORD` | （必須） | PostgreSQL |
| `OIDC_ISSUER`・`OIDC_CLIENT_ID`・`OIDC_CLIENT_SECRET`・`OIDC_GROUPS_CLAIM` | | OIDC（開発中は `DEV_LOGIN=true` で省略） |
| `ADMIN_GROUPS` | | 管理者にする IdP のグループ（カンマ区切り） |
| `APP_SECRET` | （必須） | 秘密の値の暗号化（16文字以上、全ノード共通） |
| `S3_ENDPOINT`・`S3_REGION`・`S3_BUCKET`・`S3_ACCESS_KEY`・`S3_SECRET_KEY`・`S3_PATH_STYLE`・`S3_PREFIX` | | S3 互換ストレージ |
| `STORAGE_DIR` | | S3 の代わりのローカルのディレクトリ（1台だけで動かすとき。冗長化はできない） |
| `DATA_DIR` | `./data` | ノードの手元のディレクトリ（WAL・キャッシュ・一時ファイル） |
| `NODE_ID` | ホスト名＋乱数 | ノードの名前（ログ・S3 のキー・ジョブのリース） |
| `NODE_URL` | | ほかのノードからこのノードを呼ぶ URL（書き出す前の行をノードをまたいで検索に出す。1台なら不要） |
| `GRPC_PORT` | 4317 | OTLP/gRPC（0 で止める） |
| `STUI_URL`・`STUI_CLIENT_ID`・`STUI_CLIENT_SECRET`・`STUI_INTERNAL_URL` | | stui の DM への通知（[integrations.md](integrations.md)） |
| `OAUTH_CLIENTS` | | skorimako を使う外部のサービス（stui がリンクの要約を読む） |
| `SELF_MONITOR` | true | 自己監視（自分のメトリクス・ログ・ダッシュボード） |
| `LOG_FORMAT`・`LOG_LEVEL` | text・INFO | 標準出力のログの形（`text`・`json`）と水準。自分のログは OTLP では送らない（警告・エラーは自己監視で自分に取り込む） |
| `VAPID_SUBJECT` | `PUBLIC_URL` | プッシュ通知（PWA）の連絡先（`mailto:…` か `https://…`）。VAPID の鍵は初回の起動で作って DB に保存する |
| `FLUSH_SECONDS` | 10 | 取り込んだ行を書き出す間隔 |
| `FLUSH_ROWS` | 200000 | 1つのストリームにこれだけたまったら早めに書き出す |
| `MAX_BUFFERED_ROWS` | 2000000 | 書き出す前の行の上限（超えたら 503） |
| `WAL_SYNC` | false | 受けるたびに fsync する |
| `MAX_INGEST_MB` | 32 | 取り込みの本文の上限（圧縮したまま。展開後は 256MB まで） |
| `RETENTION_DAYS` | 30 | 保持期間の既定（ストリームごとに変えられる） |
| `DUCKDB_MEMORY`・`DUCKDB_THREADS` | 2GB・4 | 1回の検索の DuckDB のメモリとスレッド |
| `QUERY_CONCURRENCY` | 4 | ノードごとの同時の検索の数 |
| `QUERY_PER_USER` | 2 | 1人の利用者（APIキーを含む）がノードごとに同時に動かせる検索の数。超えた分は順番を待つ（アラートの評価などサーバーの中の処理は対象外） |
| `QUERY_TIMEOUT_SECONDS` | 60 | 1回の検索の上限 |
| `CACHE_MAX_GB` | 10 | 検索のキャッシュの上限 |
| `WEBHOOK_DENY_PRIVATE` | false | アラートの通知先にプライベートの IP を許さない |
| `RATE_LIMIT_AUTH`・`RATE_LIMIT_PUBLIC` | 20・120 | IP ごとの1分あたりの回数の上限。ログインと OAuth の認可の画面（`/auth/…`・`/oauth/authorize`）と、OAuth のトークンの発行・失効（`/oauth/token`・`/oauth/revoke`）。0 で制限しない。ノードごとに数える。観測データの取り込み（`/v1/…`・`/rum/…`・`/api/ingest/…`・remote write）と、ログインした人・APIキーの API は数えない |
| `TRUSTED_PROXIES` | | リバースプロキシの IP か CIDR（カンマ区切り）。そこから来た要求だけ `X-Forwarded-For` から利用者の IP を読む。リバースプロキシの後ろに置くときは必ず設定する（未設定だと全員がプロキシの IP として数えられ、上限にすぐ達する） |
| `TIMEZONE` | Asia/Tokyo | |
| `STATIC_DIR` | | 画面（`dist/static`）を配信する |

メモリの目安: Java のヒープ（`-Xmx`）とは別に、検索が `DUCKDB_MEMORY × QUERY_CONCURRENCY` まで、取り込みのバッファが行数に応じて使う。

## 5. 運用

- **容量**: 画面の「ストリーム」で行数・保存量・データの範囲を見る。保持期間はストリームごとに変えられる（管理者）。
  消したデータは、削除の印から1時間後に S3 から消える。
- **取り込みの遅れ**: 管理の「取り込み」で、そのノードの書き出す前の行の数と、動いているノードが見られる。増え続けるなら S3・PostgreSQL への書き出しが
  失敗している（ログに `flush failed`。「skorimako 自身」のダッシュボードとログのストリーム `skorimako` にも出る）。
- **自己監視**: 「skorimako 自身」のダッシュボード（取り込み・書き出す前の行・検索・失敗・JVM・CPU・警告）。`skorimako_flush_failures_total` などにアラートを付けておくとよい。
  skorimako が止まると自分では知らせられないので、外からの死活監視（`/healthz`）も併せて使う。
- **個人情報**: 取り込みの変換（ストリームの画面）で、メールアドレス・カード番号・トークンなどを保存の前に伏せる。閲覧を絞るストリームは「閲覧できるグループ」を決める。
- **バックアップ**: PostgreSQL は pgBackRest（[pgbackrest.conf](../deploy/vm/pgbackrest.conf)）。S3 のバケットはストレージ側の複製・版の管理。
  PostgreSQL のファイルの一覧（`data_file`）と S3 の中身は対応しているので、戻すときは同じ時点のものをそろえる。
- **ノードの入れ替え**: `systemctl stop skorimako`（止めるときにバッファを書き出す）。強制終了したノードは、同じ `DATA_DIR` で起動すれば WAL の行を書き出す。
- **更新**: 1台ずつ `current` を新しい版に向けて再起動する（DB のマイグレーションは後方互換にする）。
