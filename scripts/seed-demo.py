#!/usr/bin/env python3
"""開発用: 直近の時間ぶんのデモのデータ（3つのサービスのログ・トレース・メトリクス）を OTLP/JSON で送る。

使い方: python3 scripts/seed-demo.py [分数（既定60）]
  送り先は BASE（既定 http://localhost:8101）。APIキーは SKORIMAKO_KEY、なければ開発用のログインで作る（DEV_LOGIN=true のときだけ）。
"""
import json, os, random, secrets, sys, time, urllib.request, http.cookiejar

BASE = os.environ.get("BASE", "http://localhost:8101")
MINUTES = int(sys.argv[1]) if len(sys.argv) > 1 else 60


def api_key():
    if os.environ.get("SKORIMAKO_KEY"):
        return os.environ["SKORIMAKO_KEY"]
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    def post(path, body):
        req = urllib.request.Request(BASE + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
        return json.load(op.open(req))
    post("/auth/dev-login", {"name": "demo", "admin": True})
    return post("/api/me/keys", {"name": "seed-demo", "scopes": ["ingest"]})["key"]


KEY = api_key()


def send(path, body):
    req = urllib.request.Request(BASE + path, json.dumps(body).encode(), {"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    urllib.request.urlopen(req).read()


def attr(k, v):
    if isinstance(v, bool):
        return {"key": k, "value": {"boolValue": v}}
    if isinstance(v, int):
        return {"key": k, "value": {"intValue": str(v)}}
    if isinstance(v, float):
        return {"key": k, "value": {"doubleValue": v}}
    return {"key": k, "value": {"stringValue": str(v)}}


def resource(svc):
    return {"attributes": [attr("service.name", svc), attr("deployment.environment", "demo"), attr("host.name", "node-" + str(hash(svc) % 3 + 1))]}


SERVICES = {"web": ["GET /", "GET /orders", "POST /orders", "GET /items/{id}"], "orders": ["CreateOrder", "ListOrders"], "inventory": ["Reserve", "Lookup"]}
now = time.time()
start = now - MINUTES * 60
random.seed(1)

# ---------------------------------------------------------------- ログとトレース（10秒ごと）
logs = {s: [] for s in SERVICES}
spans = {s: [] for s in SERVICES}
t = start
while t < now:
    # 15分ごとに5分間エラーが増える
    burst = int((t - start) // 60) % 15 >= 10
    for _ in range(random.randint(1, 4)):
        trace, root = secrets.token_hex(16), secrets.token_hex(8)
        route = random.choice(SERVICES["web"])
        err = random.random() < (0.25 if burst else 0.03)
        dur = random.lognormvariate(4, 0.6) * (3 if err else 1)  # ミリ秒
        ts = int((t + random.random() * 10) * 1e9)
        spans["web"].append({"traceId": trace, "spanId": root, "name": route, "kind": 2, "startTimeUnixNano": str(ts),
                             "endTimeUnixNano": str(ts + int(dur * 1e6)), "attributes": [attr("http.route", route), attr("http.status_code", 500 if err else 200)],
                             "status": {"code": 2, "message": "upstream timeout"} if err else {}})
        child, svc = secrets.token_hex(8), "orders" if "orders" in route else "inventory"
        cts = ts + int(dur * 0.1e6)
        spans[svc].append({"traceId": trace, "spanId": child, "parentSpanId": root, "name": random.choice(SERVICES[svc]), "kind": 2,
                           "startTimeUnixNano": str(cts), "endTimeUnixNano": str(cts + int(dur * 0.7e6)),
                           "status": {"code": 2} if err else {}})
        gts = cts + int(dur * 0.1e6)
        spans[svc].append({"traceId": trace, "spanId": secrets.token_hex(8), "parentSpanId": child, "name": "SELECT " + svc, "kind": 3,
                           "startTimeUnixNano": str(gts), "endTimeUnixNano": str(gts + int(dur * 0.4e6)), "attributes": [attr("db.system", "postgresql")]})
        logs["web"].append({"timeUnixNano": str(ts), "severityNumber": 17 if err else 9, "body": {"stringValue": f"{route} {'500' if err else '200'} {dur:.1f}ms"},
                            "traceId": trace, "spanId": root, "attributes": [attr("http.route", route), attr("duration_ms", round(dur, 1))]})
        if err:
            logs[svc].append({"timeUnixNano": str(cts), "severityNumber": 17, "body": {"stringValue": "query timeout after 3000ms"},
                              "traceId": trace, "spanId": child, "attributes": [attr("db.system", "postgresql")]})
        elif random.random() < 0.1:
            logs[svc].append({"timeUnixNano": str(cts), "severityNumber": 13, "body": {"stringValue": "slow query detected"}, "traceId": trace})
    t += 10

for s in SERVICES:
    send("/v1/logs", {"resourceLogs": [{"resource": resource(s), "scopeLogs": [{"scope": {"name": "demo"}, "logRecords": logs[s]}]}]})
    send("/v1/traces", {"resourceSpans": [{"resource": resource(s), "scopeSpans": [{"scope": {"name": "demo"}, "spans": spans[s]}]}]})

# ---------------------------------------------------------------- メトリクス（30秒ごと）
for s in SERVICES:
    reqs, pts_req, pts_mem, pts_cpu, hist = 0, [], [], [], []
    t = start
    while t < now:
        burst = int((t - start) // 60) % 15 >= 10
        ts = str(int(t * 1e9))
        reqs += random.randint(20, 60)
        pts_req.append({"timeUnixNano": ts, "asInt": str(reqs), "attributes": [attr("route", SERVICES[s][0])]})
        pts_mem.append({"timeUnixNano": ts, "asDouble": 200e6 + random.random() * 50e6 + (t - start) * 1000})
        pts_cpu.append({"timeUnixNano": ts, "asDouble": round(0.2 + random.random() * 0.2 + (0.4 if burst else 0), 3)})
        counts = [random.randint(10, 30), random.randint(5, 20), random.randint(0, 8 if not burst else 20), random.randint(0, 3)]
        hist.append({"timeUnixNano": ts, "count": str(sum(counts)), "sum": sum(counts) * 80.0, "bucketCounts": [str(c) for c in counts],
                     "explicitBounds": [50, 200, 1000]})
        t += 30
    send("/v1/metrics", {"resourceMetrics": [{"resource": resource(s), "scopeMetrics": [{"metrics": [
        {"name": "http.server.requests", "unit": "1", "description": "処理した要求の数",
         "sum": {"aggregationTemporality": 2, "isMonotonic": True, "dataPoints": pts_req}},
        {"name": "process.memory.usage", "unit": "By", "description": "メモリの使用量", "gauge": {"dataPoints": pts_mem}},
        {"name": "process.cpu.utilization", "unit": "1", "description": "CPU の使用率", "gauge": {"dataPoints": pts_cpu}},
        {"name": "http.server.duration", "unit": "ms", "description": "要求の処理時間",
         "histogram": {"aggregationTemporality": 2, "dataPoints": hist}}]}]}]})

print(f"sent demo data for the last {MINUTES} minutes to {BASE}")
