package skorimako.core

import io.circe.Json
import skorimako.shared.FieldType

/** 観測データの1つの値。 */
enum Value:
  case Str(v: String)
  case Int(v: Long)
  case Dbl(v: Double)
  case Bool(v: Boolean)

  def fieldType: String = this match
    case _: Str  => FieldType.Utf8
    case _: Int  => FieldType.Int64
    case _: Dbl  => FieldType.Float64
    case _: Bool => FieldType.Boolean

  def asText: String = this match
    case Str(v)  => v
    case Int(v)  => v.toString
    case Dbl(v)  => v.toString
    case Bool(v) => v.toString

  /** 列の型に合わせて変換する（変換できなければ None = null）。 */
  def coerce(t: String): Option[Value] = (t, this) match
    case (FieldType.Utf8, v)               => Some(Str(v.asText))
    case (FieldType.Int64, v: Int)         => Some(v)
    case (FieldType.Int64, Dbl(d))         => Option.when(!d.isNaN && !d.isInfinite)(Int(d.toLong))
    case (FieldType.Int64, Bool(b))        => Some(Int(if b then 1 else 0))
    case (FieldType.Int64, Str(s))         => s.trim.toLongOption.map(Int(_))
    case (FieldType.Float64, Int(i))       => Some(Dbl(i.toDouble))
    case (FieldType.Float64, d: Dbl)       => Some(d)
    case (FieldType.Float64, Str(s))       => s.trim.toDoubleOption.map(Dbl(_))
    case (FieldType.Float64, Bool(b))      => Some(Dbl(if b then 1 else 0))
    case (FieldType.Boolean, b: Bool)      => Some(b)
    case (FieldType.Boolean, Str(s))       => s.trim.toLowerCase match
        case "true"  => Some(Bool(true))
        case "false" => Some(Bool(false))
        case _       => None
    case (FieldType.Boolean, Int(i))       => Some(Bool(i != 0))
    case _                                 => None

  /** JSON（WAL と DuckDB への受け渡し用）。小数は必ず小数点付きで書き、整数と区別できるようにする。 */
  def toJson: Json = this match
    case Str(v)  => Json.fromString(v)
    case Int(v)  => Json.fromLong(v)
    case Dbl(v)  => if v.isNaN || v.isInfinite then Json.Null else Json.fromDoubleOrNull(v)
    case Bool(v) => Json.fromBoolean(v)

object Value:
  /** JSON の値から（入れ子のオブジェクト・配列は JSON の文字列にする）。null は None。 */
  def fromJson(j: Json): Option[Value] =
    j.fold(
      None,
      b => Some(Bool(b)),
      n => {
        val s = n.toString
        if !s.exists(c => c == '.' || c == 'e' || c == 'E') then n.toLong.map(Int(_)).orElse(Some(Dbl(n.toDouble)))
        else Some(Dbl(n.toDouble))
      },
      s => Some(Str(s)),
      a => Some(Str(Json.fromValues(a).noSpaces)),
      o => Some(Str(Json.fromJsonObject(o).noSpaces))
    )

/** 1行（1件のログ・メトリクスの点・スパン・RUM のイベント）。ts はマイクロ秒。fields の名前は列名の規則に合わせたもの。 */
final case class Row(ts: Long, fields: Map[String, Value]):
  def toJson: Json = Json.fromFields((skorimako.shared.Columns.Timestamp -> Json.fromLong(ts)) :: fields.toList.map((k, v) => k -> v.toJson))
  def str(k: String): Option[String] = fields.get(k).map(_.asText)

object Row:
  def fromJson(j: Json): Option[Row] =
    j.asObject.flatMap { o =>
      o(skorimako.shared.Columns.Timestamp).flatMap(_.asNumber).flatMap(_.toLong).map { ts =>
        Row(ts, o.toList.collect { case (k, v) if k != skorimako.shared.Columns.Timestamp => Value.fromJson(v).map(k -> _) }.flatten.toMap)
      }
    }

/** 列の型の決め方。 */
object FieldTypes:
  /** すでにある型 a に新しい値の型 b が来たときの型（整数と小数は小数、それ以外の食い違いは文字列）。 */
  def widen(a: String, b: String): String =
    if a == b then a
    else if Set(a, b) == Set(FieldType.Int64, FieldType.Float64) then FieldType.Float64
    else FieldType.Utf8

  def sqlType(t: String): String = t match
    case FieldType.Int64   => "BIGINT"
    case FieldType.Float64 => "DOUBLE"
    case FieldType.Boolean => "BOOLEAN"
    case _                 => "VARCHAR"

  /** DuckDB の型の名前から。 */
  def fromSql(t: String): String =
    val u = t.toUpperCase
    if Set("BIGINT", "INTEGER", "SMALLINT", "TINYINT", "HUGEINT", "UBIGINT", "UINTEGER", "USMALLINT", "UTINYINT").contains(u) then FieldType.Int64
    else if u == "DOUBLE" || u == "FLOAT" || u == "REAL" || u.startsWith("DECIMAL") then FieldType.Float64
    else if u == "BOOLEAN" then FieldType.Boolean
    else FieldType.Utf8

/** 列名・ストリーム名の規則。 */
object Names:
  /** 列名: 英小文字・数字・_ だけにする（service.name → service_name）。長さは100文字まで。空なら None。 */
  def column(raw: String): Option[String] =
    val b = new StringBuilder
    raw.foreach { c =>
      val l = c.toLower
      if (l >= 'a' && l <= 'z') || (l >= '0' && l <= '9') || l == '_' then b.append(l) else b.append('_')
    }
    val s = b.toString.take(100)
    Option.when(s.nonEmpty && s.exists(_ != '_'))(s)

  /** ストリーム名: 英数字・_・-・. を 1〜100 文字（. は _ に揃える）。 */
  def stream(raw: String): Either[String, String] =
    val s = raw.trim.replace('.', '_')
    if s.isEmpty then Left("ストリームの名前を指定してください")
    else if s.length > 100 then Left("ストリームの名前は100文字までにしてください")
    else if !s.forall(c => c.isLetterOrDigit && c < 128 || c == '_' || c == '-') then Left("ストリームの名前には英数字・_・- だけが使えます")
    else Right(s.toLowerCase)

  /** メトリクスの名前からストリーム名（http.server.duration → http_server_duration）。 */
  def metric(raw: String): Option[String] = column(raw)
