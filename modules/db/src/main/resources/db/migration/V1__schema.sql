-- skorimako のスキーマ
-- 観測データの中身は S3 互換ストレージの Parquet に置き、ここにはその一覧・列の型・トレースの索引・設定を置く。
-- 適用済みのマイグレーションは書き換えないこと。変更は新しい番号の V<n>__*.sql を追加する。

-- ------------------------------------------------------------------ ユーザー・認証

create table app_user (
  id            uuid primary key,
  sub           text not null unique,            -- OIDC の sub（開発用のログインは dev:<名前>）
  name          text not null,
  email         text,
  avatar_url    text,
  is_admin      boolean not null default false,  -- 画面で付けた管理者権限（ADMIN_GROUPS の所属者は別途管理者）
  disabled      boolean not null default false,
  timezone      text not null default 'Asia/Tokyo',
  created_at    timestamptz not null default now(),
  last_login_at timestamptz
);

create table user_group (
  id   uuid primary key,
  name text not null unique
);

create table group_member (
  group_id uuid not null references user_group(id) on delete cascade,
  user_id  uuid not null references app_user(id) on delete cascade,
  primary key (group_id, user_id)
);

create table session (
  token_hash bytea primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index session_expires_idx on session (expires_at);

-- OIDC のログイン途中の状態（複数ノードのどこに戻ってきてもよいよう DB に置く）
create table login_state (
  state      text primary key,
  verifier   text not null,
  nonce      text not null,
  return_to  text not null default '/',
  created_at timestamptz not null default now()
);

create table api_key (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  name         text not null,
  prefix       text not null,                    -- 画面に出す先頭部分（sko_xxxx）
  key_hash     bytea not null unique,
  scopes       text[] not null,                  -- read / write / admin / ingest
  created_at   timestamptz not null default now(),
  expires_at   timestamptz,
  last_used_at timestamptz,
  revoked_at   timestamptz,
  oauth_client text                              -- 外部のサービス（stui など）への認可で発行したキーのクライアント（許可し直したら前のキーを失効させる）
);
create index api_key_user_idx on api_key (user_id);
create index api_key_oauth_idx on api_key (user_id, oauth_client) where oauth_client is not null;

-- skorimako が認可する側（stui などがリンクの要約を読む）
create table oauth_code (
  code_hash      bytea primary key,
  client_id      text not null,
  redirect_uri   text not null,
  user_id        uuid not null references app_user(id) on delete cascade,
  code_challenge text not null,
  scope          text not null,
  expires_at     timestamptz not null,
  used_at        timestamptz
);
create index oauth_code_expires_idx on oauth_code (expires_at);

-- skorimako が認可を受ける側（stui の DM に送る）。ユーザーごとのトークン（APP_SECRET の鍵で暗号化）
create table integration_token (
  user_id    uuid not null references app_user(id) on delete cascade,
  provider   text not null,
  token_enc  text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, provider)
);

-- 認可を始めたときの state と PKCE の code_verifier（10分で失効）
create table integration_state (
  state         text primary key,
  user_id       uuid not null references app_user(id) on delete cascade,
  provider      text not null,
  code_verifier text not null,
  return_to     text not null,
  expires_at    timestamptz not null
);

-- ------------------------------------------------------------------ ストリーム（観測データの入れ物）

-- stream_type: logs / metrics / traces / rum。メトリクスはメトリクスの名前ごとに1つ。
create table stream (
  id             uuid primary key,
  stream_type    text not null check (stream_type in ('logs', 'metrics', 'traces', 'rum')),
  name           text not null,
  retention_days int,                              -- null なら既定（RETENTION_DAYS）
  description    text not null default '',
  unit           text not null default '',          -- メトリクスの単位
  metric_type    text not null default '',          -- gauge / counter / histogram / summary
  doc_count      bigint not null default 0,         -- 保存している行の数（圧縮・削除で増減する）
  stored_bytes   bigint not null default 0,
  file_count     bigint not null default 0,
  min_ts         bigint,                            -- 保存している最も古い・新しい時刻（マイクロ秒）
  max_ts         bigint,
  max_rows_per_minute int,                          -- 取り込みの上限（1分あたりの行。ノードごとに数える）
  allowed_groups text[] not null default '{}',      -- 閲覧できる IdP のグループ（空ならログインした全員）
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (stream_type, name)
);

-- 列と型（Parquet のファイルごとに列が違ってもよい。ここは画面と検索の空のビューのため）
create table stream_field (
  stream_id  uuid not null references stream(id) on delete cascade,
  name       text not null,
  field_type text not null check (field_type in ('utf8', 'int64', 'float64', 'boolean')),
  created_at timestamptz not null default now(),
  primary key (stream_id, name)
);

-- S3 に置いた Parquet のファイル。level 0 は取り込みで書いたもの、1 以上は圧縮でまとめたもの。
-- 削除は deleted_at を付け、しばらく（検索の途中のもののため）待ってから S3 と行を消す。
create table data_file (
  id         bigserial primary key,
  stream_id  uuid not null references stream(id) on delete cascade,
  s3_key     text not null unique,
  min_ts     bigint not null,
  max_ts     bigint not null,
  row_count  bigint not null,
  size_bytes bigint not null,
  level      int not null default 0,
  node       text not null default '',
  tags       jsonb not null default '{}',             -- 文字列の列ごとの値の一覧（種類が少ない列だけ。列 = 値 の条件で読まないファイルを決める）
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index data_file_range_idx on data_file (stream_id, max_ts, min_ts) where deleted_at is null;
create index data_file_deleted_idx on data_file (deleted_at) where deleted_at is not null;
create index data_file_compact_idx on data_file (stream_id, level, created_at) where deleted_at is null;

-- 取り込みの量（日ごと）
create table ingest_stat (
  day        date not null,
  stream_id  uuid not null references stream(id) on delete cascade,
  rows       bigint not null default 0,
  bytes      bigint not null default 0,
  primary key (day, stream_id)
);

-- トレースの索引（トレースごとに1行。トレース ID から時間の範囲を引き、一覧を速く出す）
create table trace_index (
  stream_id    uuid not null references stream(id) on delete cascade,
  trace_id     text not null,
  min_ts       bigint not null,
  max_ts       bigint not null,                     -- 最後に終わったスパンの終了時刻
  root_service text not null default '',
  root_name    text not null default '',
  services     text[] not null default '{}',
  span_count   int not null default 0,
  error_count  int not null default 0,
  primary key (stream_id, trace_id)
);
create index trace_index_time_idx on trace_index (stream_id, min_ts desc);
create index trace_index_service_idx on trace_index using gin (services);

-- 取り込みの変換（ストリームごと。position の順に当てる）
-- kind: mask（値の一部を伏せる）・drop_field（列を落とす）・drop_rows（条件に合う行を捨てる）・sample（割合で残す）・rename（列の名前を変える）
create table ingest_rule (
  id          uuid primary key,
  stream_type text not null,
  stream      text not null,                  -- * はその種類のすべてのストリーム
  position    int not null default 0,
  kind        text not null check (kind in ('mask', 'drop_field', 'drop_rows', 'sample', 'rename')),
  field       text not null default '',       -- 対象の列（mask は空ですべての文字列の列）
  pattern     text not null default '',       -- mask・drop_rows の正規表現、または mask の既定の型（email・card・phone・ipv4・token）
  replacement text not null default '',       -- mask の置き換え、rename の新しい名前
  ratio       double precision not null default 1, -- sample で残す割合
  enabled     boolean not null default true,
  created_at  timestamptz not null default now()
);
create index ingest_rule_stream_idx on ingest_rule (stream_type, stream, position);

-- 動いているノードと、ほかのノードから呼ぶ URL（書き出す前の行を問い合わせるため。30秒ごとに更新）
create table node (
  id        text primary key,
  url       text not null,
  last_seen timestamptz not null default now()
);

-- ------------------------------------------------------------------ フロントエンドの観測（RUM）

-- token はブラウザに埋め込む公開の値（取り込みだけに使える）。origins は送ってよいサイト（* はどこでも）。
create table rum_app (
  id         uuid primary key,
  name       text not null,
  token      text not null unique,
  origins    text[] not null default '{}',
  stream     text not null default 'default',
  created_by uuid references app_user(id) on delete set null,
  created_at timestamptz not null default now()
);

-- ------------------------------------------------------------------ アラート

create table alert_destination (
  id         uuid primary key,
  name       text not null,
  kind       text not null check (kind in ('stui', 'slack', 'webhook', 'stui_dm')),
  url        text not null,
  secret     text,                                   -- webhook の署名の鍵（APP_SECRET で暗号化）
  target_user_id uuid references app_user(id) on delete cascade, -- stui_dm: この人の stui の連携のトークンで、その人に DM を送る
  created_by uuid references app_user(id) on delete set null,
  created_at timestamptz not null default now()
);

create table alert_rule (
  id               uuid primary key,
  name             text not null,
  description      text not null default '',
  enabled          boolean not null default true,
  stream_type      text not null,
  stream           text not null,
  mode             text not null check (mode in ('threshold', 'sql', 'absence')),
  where_expr       text,
  agg              text not null default 'count',
  field            text,
  group_by         text,
  sql_text         text,
  op               text not null,
  threshold        double precision not null,
  period_minutes   int not null,
  every_minutes    int not null,
  silence_minutes  int not null default 0,
  destination_ids  uuid[] not null default '{}',
  state            text not null default 'ok' check (state in ('ok', 'firing', 'error')),
  last_value       double precision,
  last_eval_at     timestamptz,
  last_notified_at timestamptz,
  last_fired_at    timestamptz,
  last_error       text,
  lookback_minutes int not null default 1440,         -- 途絶: この範囲で来ていたデータ（groupBy の値ごと）が直近 period の間に来ていなければ発生
  ack_by           uuid references app_user(id) on delete set null, -- 対応中（確認済み）。解消すると外す。対応中の間は再通知しない
  ack_at           timestamptz,
  ack_note         text,
  next_eval_at     timestamptz not null default now(),
  created_by       uuid references app_user(id) on delete set null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create index alert_rule_due_idx on alert_rule (next_eval_at) where enabled;

create table alert_event (
  id       uuid primary key,
  rule_id  uuid not null references alert_rule(id) on delete cascade,
  state    text not null,                            -- firing / resolved / error
  value    double precision,
  hits     jsonb not null default '[]',
  message  text not null,
  silenced boolean not null default false,           -- ミュート中（通知を送らなかった）
  at       timestamptz not null default now()
);
create index alert_event_rule_idx on alert_event (rule_id, at desc);
create index alert_event_at_idx on alert_event (at desc);

-- ミュート（メンテナンスの時間帯）。rule_id が null ならすべてのルール。評価と履歴は続け、通知だけ止める
create table alert_silence (
  id         uuid primary key,
  rule_id    uuid references alert_rule(id) on delete cascade,
  reason     text not null default '',
  starts_at  timestamptz not null,
  ends_at    timestamptz not null,
  created_by uuid references app_user(id) on delete set null,
  created_at timestamptz not null default now()
);
create index alert_silence_time_idx on alert_silence (ends_at);

-- 通知の送信待ち（アウトボックス。全ノードが SKIP LOCKED で取り合う）
create table alert_delivery (
  id             uuid primary key,
  event_id       uuid not null references alert_event(id) on delete cascade,
  destination_id uuid not null references alert_destination(id) on delete cascade,
  status         text not null default 'pending' check (status in ('pending', 'sent', 'failed', 'skipped')),
  attempts       int not null default 0,
  next_at        timestamptz not null default now(),
  last_error     text,
  created_at     timestamptz not null default now(),
  sent_at        timestamptz
);
create index alert_delivery_pending_idx on alert_delivery (next_at) where status = 'pending';

-- ------------------------------------------------------------------ ダッシュボード

create table dashboard (
  id          uuid primary key,
  name        text not null,
  description text not null default '',
  panels      jsonb not null default '[]',
  created_by  uuid references app_user(id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

-- ------------------------------------------------------------------ 運用

-- 複数ノードのうち1つだけで動かす処理のリース（圧縮・保持期間・掃除・自己監視のダッシュボード）
create table job_lock (
  name       text primary key,
  holder     text not null,
  expires_at timestamptz not null
);

create table audit_log (
  id       bigserial primary key,
  at       timestamptz not null default now(),
  actor_id uuid references app_user(id) on delete set null,
  via      text not null,
  action   text not null,
  target   text,
  detail   jsonb not null default '{}'
);
create index audit_log_at_idx on audit_log (at desc);
