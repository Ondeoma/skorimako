# 兄弟のサービスとの連携

## stui にアラートを知らせる

skorimako のアラートは、[stui](https://github.com/Ondeoma/stui)（チャット）のチャンネルに投稿できます。stui の受信 Webhook（Slack・Mattermost と同じ形の JSON）を使います。

1. stui で、知らせたいチャンネルの ⚙ →「連携・定期メッセージ」→ 受信 Webhook を作る（名前は「skorimako」など）。表示された URL（`https://stui.example.com/hooks/…`）を控える。
2. skorimako の「アラート」→「通知先」で、種類「stui（受信 Webhook）」として URL を登録する（管理者）。「試しに送る」で届くことを確かめる。
3. アラートのルールで、その通知先を選ぶ。

投稿の例（stui は Markdown で表示する）:

```
🔴 **[発生] API のエラーが多い**
web: 93、orders: 42（条件 > 5）
ログ / default  [詳しく見る](https://observe.example.com/alerts/…)
```

解消したときは「✅ [解消]」、発生中に再通知の間隔が過ぎたときは同じ形で再び投稿します。送信に失敗したら再試行します（「アラート」→「送信の記録」）。

本文の形（`POST {URL}`、`Content-Type: application/json`）:

```json
{ "username": "skorimako", "text": "🔴 **[発生] …**\n…" }
```

stui は社内のネットワークに置くことが多いため、通知先にはプライベートの IP も使えます（`WEBHOOK_DENY_PRIVATE=true` で断る）。

## stui の DM・チャンネルに知らせる（OAuth）

各自が stui と連携すると、アラートをその人の stui の DM や、その人が参加しているチャンネルに（stui のボット「skorimako」から）送れます。
受信 Webhook の URL を作らなくても、チャンネルを一覧から選ぶだけで通知先にできます。

1. stui の `OAUTH_CLIENTS` に skorimako を登録する:
   `{"id": "skorimako", "secret": "（16文字以上）", "redirectUri": "https://observe.example.com/api/integrations/stui/callback", "name": "skorimako", "scopes": ["read", "write"]}`
2. skorimako に `STUI_URL`・`STUI_CLIENT_ID`（skorimako）・`STUI_CLIENT_SECRET` を設定する（サーバーから呼ぶ URL が違えば `STUI_INTERNAL_URL`）。
3. 各自が skorimako の「設定」→「stui と連携する」で許可する。
4. 通知先を作り、ルールで選ぶ:
   - DM: 「アラート」→「通知先」→「自分の stui の DM を通知先にする」（管理者でなくても、自分宛ての DM は作れる）。
   - チャンネル: 「通知先を追加」で種類「stui のチャンネル」を選び、自分が参加しているチャンネルを選ぶ（管理者）。

送るときは stui の `POST /api/apps/notify` を呼びます。

- DM: `{"text": "…"}`、宛先の人のトークン。
- チャンネル: `{"title": "…", "text": "…", "url": "…", "channel": "<チャンネルの ID>"}`、通知先を保存した人のトークン。
  保存するときに、保存する人が参加しているチャンネルか（`GET /api/channels`）を確かめ、その人を連携の持ち主にします。
  ほかの管理者が編集して保存すると、持ち主はその人に変わります。

トークンが失効していたら（401）、連携を外して送るのをやめます。持ち主がチャンネルを抜けた・チャンネルがアーカイブされた（403）、
チャンネルがない（404）ときも、送り直さずに送信の記録に理由を残します。

## メールで知らせる

種類「メール」で、宛先のアドレス（カンマ区切りで 10 件まで）に送ります。本文はプレーンテキストで、件名は `[skorimako] 🔴 [発生] ルールの名前` の形です。

送信は SMTP です（`SMTP_HOST`・`SMTP_PORT`・`SMTP_USER`・`SMTP_PASSWORD`・`SMTP_TLS`（`starttls`（既定）・`ssl`・`none`）・`MAIL_FROM`）。
`SMTP_HOST` が未設定なら、メールの通知先には送らずに、送信の記録に理由を残します。送り先を skereru の SMTP にすれば、skereru から送れます。

宛先ごとに 1 通ずつ送ります。途中で失敗したときは通知全体を送り直すので、先に届いた宛先に重ねて届くことがあります。

## stui からリンクの要約を読む

stui のメッセージに貼られた skorimako の URL に名前を出すため、skorimako は stui と同じ形の OAuth 2.0（認可コード + PKCE）の認可サーバーになります。

- skorimako の `OAUTH_CLIENTS` に stui を登録する: `{"id": "stui", "secret": "…", "redirectUri": "https://chat.example.com/api/integrations/skorimako/callback", "name": "stui", "scopes": ["read"]}`
- 認可: `GET /oauth/authorize`・`POST /oauth/token`・`POST /oauth/revoke`（stui の [integrations.md](https://github.com/Ondeoma/stui) の兄弟 → stui と同じ）
- stui の形（stui の「リンクの名前」の連携先 `skorimako`。stui に `SKORIMAKO_URL`・`SKORIMAKO_CLIENT_ID`・`SKORIMAKO_CLIENT_SECRET` を設定する）:
  `POST /api/links/titles {"ids": ["traces/{id}", "alerts/{id}", "dashboards/{id}"]}` → `{"links": [{id, kind, title, summary, status, icon}]}`。
  stui の画面はメッセージの中の `/traces/{id}`・`/alerts/{id}`・`/dashboards/{id}` のリンクを探して問い合わせ、
  「⚠️ web: GET /orders（80ms・スパン 3・エラー 1）」「🔴 エラーが多い（発生中・値 42）」「📊 ダッシュボードの名前（パネル 8）」と出す。
- URL で問い合わせる形: `POST /api/links/lookup {"urls": [...]}` → `[{url, kind, title, summary, status}]`（logs・metric の URL も読める）。
- どちらも、本人の権限で見られないものは返しません。

## Slack・Mattermost

種類「Slack・Mattermost 互換の Webhook」で Incoming Webhook の URL を登録します。本文は Slack の mrkdwn（`*太字*`、`<URL|文字>`）です。

## Webhook（ほかのシステム）

種類「Webhook」は、次の JSON を POST します。署名の鍵を決めると `X-Skorimako-Signature: sha256=<本文の HMAC-SHA256 の16進数>` を付けます。
`X-Skorimako-Event`（`alert.firing`・`alert.resolved`・`alert.acknowledged`）・`X-Skorimako-Delivery`（送信の ID）も付けます。

```json
{
  "event": "alert.firing",
  "rule": { "id": "…", "name": "API のエラーが多い", "op": ">", "threshold": 5, "streamType": "logs", "stream": "default" },
  "value": 93,
  "hits": [{ "group": "web", "value": 93 }, { "group": "orders", "value": 42 }],
  "message": "API のエラーが多い: web: 93、orders: 42（条件 > 5）",
  "url": "https://observe.example.com/alerts/…",
  "at": "2026-11-17T00:31:50Z"
}
```

2xx で成功、404・410 は送るのをやめ、それ以外は30秒から倍々（最大1時間）で8回まで再試行します。
`event` は `alert.firing`・`alert.resolved`・`alert.acknowledged`（だれかが対応中にした）です。ミュートの間は送りません（履歴には残ります）。

## Grafana・Prometheus

- Grafana のデータソース（種類 Prometheus）の URL を `https://observe.example.com/api/prom` にし、認証は Basic（パスワードに read のキー）か
  `Authorization: Bearer` のヘッダーにします。PromQL・ラベルの候補・メトリクスの説明が使えます。
- Prometheus から送る: `remote_write: [{url: https://observe.example.com/api/prom/api/v1/write, authorization: {credentials: sko_…}}]`（ingest のキー）。

## 兄弟のサービスの観測

skea・skiwi・skawau・stui などの JVM のアプリは、OpenTelemetry の Java エージェント（`-javaagent:opentelemetry-javaagent.jar`）と
`OTEL_EXPORTER_OTLP_ENDPOINT={skorimako の URL}`・`OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer sko_…`（ingest のキー）で、
ログ・メトリクス・トレースを送れます。画面はブラウザの観測（`/rum.js`）を貼れます。
