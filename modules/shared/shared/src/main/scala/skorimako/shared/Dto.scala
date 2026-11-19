package skorimako.shared

import io.circe.*

import java.util.UUID

// バックエンド(JVM)と画面(Scala.js)の両方で使う API の型。
// 日時は Timestamp（JVM では Instant）。観測データの時刻は UNIX エポックからのマイクロ秒（Long）。

/** ストリーム（データの入れ物）の種類. */
object StreamType {
  val Logs = "logs"
  val Metrics = "metrics"
  val Traces = "traces"
  val Rum = "rum"
  val all: List[String] = List(Logs, Metrics, Traces, Rum)
  def label(t: String): String = t match {
    case Logs => "ログ"
    case Metrics => "メトリクス"
    case Traces => "トレース"
    case Rum => "フロントエンド"
    case _ => t
  }
}

/** 列の型（Parquet・DuckDB の型に対応する）. */
object FieldType {
  val Utf8 = "utf8"
  val Int64 = "int64"
  val Float64 = "float64"
  val Boolean = "boolean"
  val all: List[String] = List(Utf8, Int64, Float64, Boolean)
}

/** どの種類のストリームにもある列. */
object Columns {

  /** 時刻（マイクロ秒）. */
  val Timestamp = "_timestamp"
}

// ------------------------------------------------------------------ 利用者・設定

/** stui は stui との連携（DM への通知）を設定しているか、grpcPort は OTLP/gRPC の待ち受け（0 は止めている）. */
final case class ClientConfigDto(devLogin: Boolean,
                                 version: String,
                                 publicUrl: String,
                                 flushSeconds: Int,
                                 stui: Boolean,
                                 grpcPort: Int)
  derives Encoder.AsObject, Decoder

final case class MeDto(id: UUID,
                       name: String,
                       email: Option[String],
                       avatarUrl: Option[String],
                       isAdmin: Boolean,
                       timezone: String,
                       appNames: Map[String, String] = Map.empty)
  derives Encoder.AsObject, Decoder

final case class UpdateMeReq(name: Option[String], timezone: Option[String]) derives Encoder.AsObject, Decoder

final case class UserBrief(id: UUID, name: String, avatarUrl: Option[String]) derives Encoder.AsObject, Decoder

final case class UserDto(id: UUID,
                         name: String,
                         email: Option[String],
                         avatarUrl: Option[String],
                         isAdmin: Boolean,
                         disabled: Boolean,
                         lastLoginAt: Option[Timestamp],
                         groups: List[String]) derives Encoder.AsObject, Decoder

final case class UpdateUserReq(isAdmin: Option[Boolean], disabled: Option[Boolean]) derives Encoder.AsObject, Decoder

/** APIキーのスコープ. ingest は観測データの送信（OTLP など）だけ. */
object KeyScope {
  val Read = "read"
  val Write = "write"
  val Admin = "admin"
  val Ingest = "ingest"
  val all: List[String] = List(Read, Write, Admin, Ingest)
  def label(s: String): String = s match {
    case Read => "参照（検索・一覧）"
    case Write => "更新（アラート・ダッシュボードの編集）"
    case Admin => "管理（管理者だけ）"
    case Ingest => "取り込み（OTLP・JSON の送信）"
    case _ => s
  }
}

final case class ApiKeyDto(id: UUID,
                           name: String,
                           prefix: String,
                           scopes: List[String],
                           createdAt: Timestamp,
                           expiresAt: Option[Timestamp],
                           lastUsedAt: Option[Timestamp]) derives Encoder.AsObject, Decoder

final case class CreateKeyReq(name: String, scopes: List[String], expiresInDays: Option[Int]) derives Encoder.AsObject,
    Decoder

/** 作ったときだけ key（平文）を返す. */
final case class CreatedKeyDto(key: String, info: ApiKeyDto) derives Encoder.AsObject, Decoder

final case class AuditDto(id: Long,
                          at: Timestamp,
                          actor: Option[UserBrief],
                          via: String,
                          action: String,
                          target: Option[String],
                          detail: Json)
  derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ ストリーム

/** allowedGroups は閲覧できる IdP のグループ（空ならログインした全員）、maxRowsPerMinute は取り込みの上限（ノードごと）. */
final case class StreamDto(id: UUID,
                           name: String,
                           streamType: String,
                           retentionDays: Int,
                           docCount: Long,
                           storedBytes: Long,
                           fileCount: Long,
                           minTs: Option[Long],
                           maxTs: Option[Long],
                           description: String,
                           unit: String,
                           metricType: String,
                           updatedAt: Timestamp,
                           allowedGroups: List[String],
                           maxRowsPerMinute: Option[Int]) derives Encoder.AsObject, Decoder

final case class FieldDto(name: String, fieldType: String) derives Encoder.AsObject, Decoder

final case class StreamDetailDto(stream: StreamDto, fields: List[FieldDto]) derives Encoder.AsObject, Decoder

/** maxRowsPerMinute は 0 で上限なし. */
final case class UpdateStreamReq(retentionDays: Option[Int],
                                 description: Option[String],
                                 allowedGroups: Option[List[String]] = None,
                                 maxRowsPerMinute: Option[Int] = None) derives Encoder.AsObject, Decoder

/** 取り込みの変換. kind: mask・drop_field・drop_rows・sample・rename. */
object IngestRuleKind {
  val Mask = "mask"
  val DropField = "drop_field"
  val DropRows = "drop_rows"
  val Sample = "sample"
  val Rename = "rename"
  val all: List[String] = List(Mask, DropField, DropRows, Sample, Rename)
  def label(k: String): String = k match {
    case Mask => "値を伏せる"
    case DropField => "列を落とす"
    case DropRows => "行を捨てる"
    case Sample => "間引く"
    case Rename => "列の名前を変える"
    case _ => k
  }

  /** mask の既定の型. */
  val presets: List[(String, String)] = List(
    "email" -> "メールアドレス",
    "card" -> "カード番号",
    "phone" -> "電話番号",
    "ipv4" -> "IPv4 アドレス",
    "token" -> "トークン・パスワードらしい値"
  )
}

final case class IngestRuleDto(id: UUID,
                               streamType: String,
                               stream: String,
                               position: Int,
                               kind: String,
                               field: String,
                               pattern: String,
                               replacement: String,
                               ratio: Double,
                               enabled: Boolean) derives Encoder.AsObject, Decoder

final case class IngestRuleReq(streamType: String,
                               stream: String,
                               position: Int,
                               kind: String,
                               field: String,
                               pattern: String,
                               replacement: String,
                               ratio: Double,
                               enabled: Boolean) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 検索

/** SQL で検索する. FROM にはストリームの名前を書く（例: SELECT * FROM "default"）. start・end はマイクロ秒. */
final case class SqlQueryReq(streamType: String, sql: String, start: Long, end: Long, limit: Option[Int])
  derives Encoder.AsObject, Decoder

final case class ColumnDto(name: String, columnType: String) derives Encoder.AsObject, Decoder

final case class QueryResultDto(columns: List[ColumnDto],
                                rows: List[List[Json]],
                                tookMs: Long,
                                scannedFiles: Int,
                                scannedBytes: Long,
                                truncated: Boolean) derives Encoder.AsObject, Decoder

/** ログの検索. where は SQL の条件（例: severity_text = 'ERROR'）、keyword は本文などの部分一致. order は desc（新しい順）か asc. */
final case class LogSearchReq(stream: String,
                              start: Long,
                              end: Long,
                              where: Option[String],
                              keyword: Option[String],
                              limit: Option[Int],
                              offset: Option[Int],
                              order: Option[String],
                              streamType: Option[String] = None) derives Encoder.AsObject, Decoder

final case class BucketDto(t: Long, count: Long) derives Encoder.AsObject, Decoder

final case class LogSearchDto(result: QueryResultDto,
                              total: Long,
                              histogram: List[BucketDto],
                              intervalMicros: Long,
                              sql: String)
  derives Encoder.AsObject, Decoder

/** メトリクスの検索. agg: avg・sum・min・max・count・last・rate（カウンターの毎秒の増え方）・p50・p90・p95・p99. */
final case class MetricQueryReq(metric: String,
                                start: Long,
                                end: Long,
                                agg: String,
                                groupBy: List[String],
                                where: Option[String],
                                stepSeconds: Option[Int]) derives Encoder.AsObject, Decoder

final case class PointDto(t: Long, v: Option[Double]) derives Encoder.AsObject, Decoder

final case class SeriesDto(labels: Map[String, String], points: List[PointDto]) derives Encoder.AsObject, Decoder

final case class MetricResultDto(series: List[SeriesDto], stepSeconds: Int, sql: String, truncated: Boolean)
  derives Encoder.AsObject, Decoder

object MetricAgg {
  val all: List[String] = List("avg", "sum", "min", "max", "count", "last", "rate", "p50", "p90", "p95", "p99")
  def label(a: String): String = a match {
    case "avg" => "平均"
    case "sum" => "合計"
    case "min" => "最小"
    case "max" => "最大"
    case "count" => "件数"
    case "last" => "最後の値"
    case "rate" => "毎秒の増加（カウンター）"
    case p => p
  }
}

/** トレースの検索（トレースの索引から）. */
final case class TraceSearchReq(start: Long,
                                end: Long,
                                service: Option[String],
                                name: Option[String],
                                minDurationMs: Option[Double],
                                errorsOnly: Option[Boolean],
                                stream: Option[String],
                                limit: Option[Int]) derives Encoder.AsObject, Decoder

final case class TraceSummaryDto(traceId: String,
                                 stream: String,
                                 service: String,
                                 rootName: String,
                                 start: Long,
                                 durationMicros: Long,
                                 spanCount: Int,
                                 errorCount: Int,
                                 services: List[String]) derives Encoder.AsObject, Decoder

final case class TraceDto(traceId: String, spans: List[Json]) derives Encoder.AsObject, Decoder

final case class ServiceDto(name: String, traceCount: Long, errorCount: Long) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ フロントエンドの観測（RUM）

final case class RumAppDto(id: UUID,
                           name: String,
                           token: String,
                           origins: List[String],
                           stream: String,
                           createdAt: Timestamp)
  derives Encoder.AsObject, Decoder

final case class RumAppReq(name: String, origins: List[String], stream: Option[String]) derives Encoder.AsObject,
    Decoder

// ------------------------------------------------------------------ アラート

object AlertMode {

  /** 集計した値と閾値を比べる. */
  val Threshold = "threshold"

  /** SQL の結果の value（または最初の数値の列）と閾値を比べる. */
  val Sql = "sql"

  /** 以前は来ていたデータ（groupBy の値ごと）が来なくなったら発生する. */
  val Absence = "absence"
}

object AlertState {
  val Ok = "ok"
  val Firing = "firing"
  val Error = "error"
  def label(s: String): String = s match {
    case Ok => "正常"
    case Firing => "発生中"
    case Error => "評価できない"
    case _ => s
  }
}

object AlertOp {
  val all: List[String] = List(">", ">=", "<", "<=", "==", "!=")
}

object AlertAgg {
  val all: List[String] = List("count", "avg", "sum", "min", "max", "p50", "p90", "p95", "p99")
}

final case class AlertRuleReq(name: String,
                              description: String,
                              enabled: Boolean,
                              streamType: String,
                              stream: String,
                              mode: String,
                              where: Option[String],
                              agg: String,
                              field: Option[String],
                              groupBy: Option[String],
                              sql: Option[String],
                              op: String,
                              threshold: Double,
                              periodMinutes: Int,
                              everyMinutes: Int,
                              silenceMinutes: Int,
                              destinationIds: List[UUID],
                              lookbackMinutes: Option[Int] = None)
  derives Encoder.AsObject, Decoder

/** 確認済み（対応中）の印. */
final case class AckDto(by: Option[UserBrief], at: Timestamp, note: String) derives Encoder.AsObject, Decoder

final case class AckReq(note: String) derives Encoder.AsObject, Decoder

final case class AlertRuleDto(id: UUID,
                              name: String,
                              description: String,
                              enabled: Boolean,
                              streamType: String,
                              stream: String,
                              mode: String,
                              where: Option[String],
                              agg: String,
                              field: Option[String],
                              groupBy: Option[String],
                              sql: Option[String],
                              op: String,
                              threshold: Double,
                              periodMinutes: Int,
                              everyMinutes: Int,
                              silenceMinutes: Int,
                              destinationIds: List[UUID],
                              state: String,
                              lastValue: Option[Double],
                              lastEvalAt: Option[Timestamp],
                              lastFiredAt: Option[Timestamp],
                              lastError: Option[String],
                              createdBy: Option[UserBrief],
                              createdAt: Timestamp,
                              lookbackMinutes: Int,
                              ack: Option[AckDto],
                              silencedUntil: Option[Timestamp]) derives Encoder.AsObject, Decoder {
  def toReq: AlertRuleReq = AlertRuleReq(
    name,
    description,
    enabled,
    streamType,
    stream,
    mode,
    where,
    agg,
    field,
    groupBy,
    sql,
    op,
    threshold,
    periodMinutes,
    everyMinutes,
    silenceMinutes,
    destinationIds,
    Some(lookbackMinutes)
  )
}

/** ミュート（メンテナンスの時間帯）. ruleId がなければすべてのルール. */
final case class SilenceDto(id: UUID,
                            ruleId: Option[UUID],
                            ruleName: Option[String],
                            reason: String,
                            startsAt: Timestamp,
                            endsAt: Timestamp,
                            createdBy: Option[UserBrief]) derives Encoder.AsObject, Decoder

/** minutes を指定すると今から minutes 分（startsAt・endsAt より優先）. */
final case class SilenceReq(ruleId: Option[UUID],
                            reason: String,
                            startsAt: Option[Timestamp],
                            endsAt: Option[Timestamp],
                            minutes: Option[Int])
  derives Encoder.AsObject, Decoder

/** 条件に当てはまった値（groupBy の値ごと）. */
final case class AlertHitDto(group: Option[String], value: Double) derives Encoder.AsObject, Decoder

final case class AlertEventDto(id: UUID,
                               ruleId: UUID,
                               ruleName: String,
                               state: String,
                               value: Option[Double],
                               hits: List[AlertHitDto],
                               message: String,
                               at: Timestamp,
                               silenced: Boolean) derives Encoder.AsObject, Decoder

/** 試しに評価した結果（通知は送らない）. */
final case class AlertTestDto(firing: Boolean,
                              hits: List[AlertHitDto],
                              values: List[AlertHitDto],
                              sql: String,
                              error: Option[String])
  derives Encoder.AsObject, Decoder

/** 通知先の種類. stui は stui の受信 Webhook（Slack と同じ形の JSON）. */
object DestinationKind {
  val Stui = "stui"
  val Slack = "slack"
  val Webhook = "webhook"

  /** stui の DM（宛先の人が stui と連携している必要がある）. */
  val StuiDm = "stui_dm"
  val all: List[String] = List(Stui, StuiDm, Slack, Webhook)

  /** 種類の名前. stui は stui の表示名（画面では AppNames.nameOf("stui")）. */
  def label(k: String, stui: String = "stui"): String = k match {
    case Stui => s"$stui（受信 Webhook）"
    case StuiDm => s"$stui の DM"
    case Slack => "Slack・Mattermost 互換の Webhook"
    case Webhook => "Webhook（JSON・署名付き）"
    case _ => k
  }
}

final case class DestinationDto(id: UUID,
                                name: String,
                                kind: String,
                                url: String,
                                hasSecret: Boolean,
                                createdAt: Timestamp,
                                targetUser: Option[UserBrief]) derives Encoder.AsObject, Decoder

/** secret は webhook の署名の鍵（省略時は署名しない. 変更時に None なら変えない）. */
/** stui_dm は targetUserId の人の DM へ（url は使わない. 管理者でなければ自分だけ）. */
final case class DestinationReq(name: String,
                                kind: String,
                                url: String,
                                secret: Option[String],
                                targetUserId: Option[UUID] = None)
  derives Encoder.AsObject, Decoder

/** 外部のサービスとの連携（stui）. */
final case class IntegrationDto(provider: String, name: String, url: String, connected: Boolean)
  derives Encoder.AsObject, Decoder

final case class DeliveryDto(id: UUID,
                             destination: String,
                             ruleName: String,
                             state: String,
                             status: String,
                             attempts: Int,
                             lastError: Option[String],
                             createdAt: Timestamp,
                             sentAt: Option[Timestamp]) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ ダッシュボード

object PanelKind {
  val Line = "line"
  val Bar = "bar"
  val Stat = "stat"
  val Table = "table"
  val all: List[String] = List(Line, Bar, Stat, Table)
  def label(k: String): String = k match {
    case Line => "折れ線"
    case Bar => "棒"
    case Stat => "数値"
    case Table => "表"
    case _ => k
  }
}

/**
 * パネル. sql の結果の1列目を時刻（マイクロ秒）、残りの数値の列を系列として描く（line・bar）.
 * stat は1行目の最初の数値、table は結果をそのまま. sql の中の $__interval は区切りの長さ（マイクロ秒）に置き換える.
 */
/** spec: かんたん作成（選んで組み立てる）で作ったパネルの指定. あれば編集のときにかんたん作成で開き直せる（SQL はそこから作ったもの）. */
final case class PanelDto(id: String,
                          title: String,
                          kind: String,
                          streamType: String,
                          sql: String,
                          width: Int,
                          unit: String,
                          spec: Option[PanelSpec] = None) derives Encoder.AsObject, Decoder

/** パネルの条件の1行. op は PanelOp のどれか. */
final case class PanelFilter(field: String, op: String, value: String) derives Encoder.AsObject, Decoder

/**
 * かんたん作成のパネルの指定. measure は集計（count・avg・sum・min・max・last・p50〜p99・rate）、field は集計する列（count・メトリクスでは不要）.
 * groupBy で系列（表なら行）を分け、多いときは上位 topN だけにする. service は service_name の絞り込み.
 */
final case class PanelSpec(streamType: String,
                           stream: String,
                           measure: String,
                           field: Option[String],
                           groupBy: Option[String],
                           filters: List[PanelFilter],
                           service: Option[String],
                           topN: Option[Int]) derives Encoder.AsObject, Decoder

final case class PanelSqlReq(spec: PanelSpec, kind: String) derives Encoder.AsObject, Decoder

/** 組み立てた SQL と、単位の目安（トレースの所要時間なら ms）. */
final case class PanelSqlDto(sql: String, unit: String) derives Encoder.AsObject, Decoder

object PanelOp {
  val all: List[String] = List("=", "!=", "contains", ">", ">=", "<", "<=", "exists")
  def label(o: String): String = o match {
    case "contains" => "を含む"
    case "exists" => "がある"
    case x => x
  }
}

final case class DashboardDto(id: UUID,
                              name: String,
                              description: String,
                              panels: List[PanelDto],
                              createdBy: Option[UserBrief],
                              updatedAt: Timestamp) derives Encoder.AsObject, Decoder

final case class DashboardBrief(id: UUID, name: String, description: String, panelCount: Int, updatedAt: Timestamp)
  derives Encoder.AsObject, Decoder

final case class DashboardReq(name: String, description: String, panels: List[PanelDto]) derives Encoder.AsObject,
    Decoder

// ------------------------------------------------------------------ ホーム

final case class OverviewDto(streams: List[StreamDto],
                             firing: List[AlertRuleDto],
                             recentEvents: List[AlertEventDto],
                             ingestedToday: Long)
  derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ サービス（トレースから）

/** サービスごとの要求の数・エラー・所要時間（ルートのスパンではなく、サービスの入口のスパン: SERVER・CONSUMER）. */
final case class ServiceStatDto(name: String,
                                requests: Long,
                                errors: Long,
                                rps: Double,
                                p50Ms: Double,
                                p95Ms: Double,
                                p99Ms: Double)
  derives Encoder.AsObject, Decoder

/** サービス間の呼び出し（親のスパンのサービス → 子のスパンのサービス）. */
final case class ServiceEdgeDto(from: String, to: String, calls: Long, errors: Long, p95Ms: Double)
  derives Encoder.AsObject, Decoder

final case class ServiceMapDto(services: List[ServiceStatDto], edges: List[ServiceEdgeDto]) derives Encoder.AsObject,
    Decoder

/** サービスの時系列（区切りごとの要求・エラー・p95）. */
final case class ServiceSeriesDto(name: String, points: List[ServicePointDto]) derives Encoder.AsObject, Decoder
final case class ServicePointDto(t: Long, requests: Long, errors: Long, p95Ms: Option[Double]) derives Encoder.AsObject,
    Decoder

// ------------------------------------------------------------------ リンクの要約（stui などから）

final case class LinkLookupReq(urls: List[String]) derives Encoder.AsObject, Decoder

/** kind: trace・alert・dashboard・logs・metric. status は alert の状態など. */
final case class LinkInfoDto(url: String, kind: String, title: String, summary: String, status: Option[String])
  derives Encoder.AsObject, Decoder
