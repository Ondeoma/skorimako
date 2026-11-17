#!/usr/bin/env bash
# 開発用の動作確認: 開発用のログイン → APIキーの発行 → OTLP/JSON でログ・トレース・メトリクスを送る → 書き出しを待って検索する。
# 使い方: scripts/dev-restart.sh で起動してから scripts/smoke.sh（BASE で送り先を変えられる）
set -euo pipefail
BASE=${BASE:-http://localhost:8101}
JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT
j() { curl -sS -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' "$@"; }

j -X POST "$BASE/auth/dev-login" -d '{"name":"smoke","admin":true}' >/dev/null
KEY=$(j -X POST "$BASE/api/me/keys" -d '{"name":"smoke","scopes":["read","write","ingest"]}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')
echo "key: ${KEY:0:10}…"
A=(-H "Authorization: Bearer $KEY" -H 'Content-Type: application/json')

NOW=$(python3 -c 'import time; print(int(time.time()*1e9))')
TRACE=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
ROOT=$(python3 -c 'import secrets; print(secrets.token_hex(8))')
CHILD=$(python3 -c 'import secrets; print(secrets.token_hex(8))')

curl -sS -X POST "$BASE/v1/logs" "${A[@]}" -d '{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"smoke-api"}}]},
 "scopeLogs":[{"logRecords":[
  {"timeUnixNano":"'$NOW'","severityNumber":9,"body":{"stringValue":"request ok"},"attributes":[{"key":"http.status_code","value":{"intValue":"200"}}]},
  {"timeUnixNano":"'$NOW'","severityNumber":17,"body":{"stringValue":"timeout while calling db"},"traceId":"'$TRACE'","spanId":"'$CHILD'",
   "attributes":[{"key":"http.status_code","value":{"intValue":"500"}}]}]}]}]}'; echo " logs"

curl -sS -X POST "$BASE/v1/traces" "${A[@]}" -d '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"smoke-api"}}]},
 "scopeSpans":[{"spans":[
  {"traceId":"'$TRACE'","spanId":"'$ROOT'","name":"GET /orders","kind":2,"startTimeUnixNano":"'$NOW'","endTimeUnixNano":"'$((NOW+120000000))'","status":{"code":2,"message":"db timeout"}},
  {"traceId":"'$TRACE'","spanId":"'$CHILD'","parentSpanId":"'$ROOT'","name":"SELECT orders","kind":3,"startTimeUnixNano":"'$((NOW+10000000))'","endTimeUnixNano":"'$((NOW+110000000))'"}]}]}]}'; echo " traces"

curl -sS -X POST "$BASE/v1/metrics" "${A[@]}" -d '{"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"smoke-api"}}]},
 "scopeMetrics":[{"metrics":[
  {"name":"http.server.requests","unit":"1","sum":{"aggregationTemporality":2,"isMonotonic":true,"dataPoints":[
    {"timeUnixNano":"'$((NOW-60000000000))'","asInt":"100","attributes":[{"key":"route","value":{"stringValue":"/orders"}}]},
    {"timeUnixNano":"'$NOW'","asInt":"160","attributes":[{"key":"route","value":{"stringValue":"/orders"}}]}]}},
  {"name":"process.memory","unit":"By","gauge":{"dataPoints":[{"timeUnixNano":"'$NOW'","asDouble":123456789}]}}]}]}]}'; echo " metrics"

curl -sS -X POST "$BASE/api/ingest/app/_json" "${A[@]}" -d '[{"level":"warn","msg":"disk 85%","host":{"name":"web1"}}]'; echo " json"

sleep "${WAIT:-6}"
NOWU=$((NOW/1000))
echo "--- streams"; j "$BASE/api/streams" | python3 -c 'import json,sys; [print(s["streamType"], s["name"], s["docCount"]) for s in json.load(sys.stdin)]'
echo "--- logs (errors)"; j -X POST "$BASE/api/query/logs" -d '{"stream":"default","start":'$((NOWU-3600000000))',"end":'$((NOWU+60000000))',"where":"severity_text = '"'"'ERROR'"'"'"}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("total", d.get("total"), d.get("error",""))'
echo "--- sql"; j -X POST "$BASE/api/query/sql" -d '{"streamType":"logs","sql":"SELECT service_name, count(*) AS n FROM \"default\" GROUP BY 1","start":0,"end":'$((NOWU+60000000))'}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("rows", d))'
echo "--- metrics rate"; j -X POST "$BASE/api/query/metrics" -d '{"metric":"http_server_requests","start":'$((NOWU-3600000000))',"end":'$((NOWU+60000000))',"agg":"rate","groupBy":["route"],"stepSeconds":3600}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("series", d))'
echo "--- trace"; j "$BASE/api/traces/$TRACE" | python3 -c 'import json,sys; d=json.load(sys.stdin); print([ (s["operation_name"], s["duration"]) for s in d.get("spans",[])] or d)'
echo "--- trace search"; j -X POST "$BASE/api/traces/search" -d '{"start":'$((NOWU-3600000000))',"end":'$((NOWU+60000000))',"errorsOnly":true}' \
  | python3 -c 'import json,sys; print([(t["rootName"], t["spanCount"], t["errorCount"]) for t in json.load(sys.stdin)])'
echo "--- mcp"; curl -sS -X POST "$BASE/mcp" "${A[@]}" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_streams","arguments":{}}}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["content"][0]["text"])'
