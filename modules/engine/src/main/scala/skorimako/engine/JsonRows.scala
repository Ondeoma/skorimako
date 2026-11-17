package skorimako.engine

import io.circe.{Json, JsonObject}
import skorimako.core.*

import java.time.{Instant, OffsetDateTime}
import scala.collection.mutable

/** JSON（任意の形のログ・RUM のイベント）を行に直す。 */
object JsonRows:

  /** 時刻: 数値は桁数で秒・ミリ秒・マイクロ秒・ナノ秒を見分ける。文字列は ISO 8601。 */
  def toMicros(j: Json): Option[Long] =
    j.asNumber.flatMap(n => n.toLong.map(_.toDouble).orElse(Some(n.toDouble))).map { d =>
      if d > 1e17 then (d / 1000).toLong
      else if d > 1e14 then d.toLong
      else if d > 1e11 then (d * 1000).toLong
      else (d * 1e6).toLong
    }.orElse(j.asString.flatMap { s =>
      scala.util.Try(Instant.parse(s)).orElse(scala.util.Try(OffsetDateTime.parse(s).toInstant)).toOption
        .map(i => i.getEpochSecond * 1000000L + i.getNano / 1000)
        .orElse(s.toDoubleOption.flatMap(d => toMicros(Json.fromDoubleOrNull(d))))
    })

  private val timeKeys = List("_timestamp", "@timestamp", "timestamp", "time", "ts")

  /** 入れ子のオブジェクトは a_b の列にし、配列は JSON の文字列にする。 */
  def flatten(o: JsonObject, out: mutable.Map[String, Value], prefix: String = "", depth: Int = 0): Unit =
    o.toIterable.foreach { (k, v) =>
      Names.column(prefix + k).foreach { name =>
        v.asObject match
          case Some(child) if depth < 8 => flatten(child, out, name + "_", depth + 1)
          case _                        => Value.fromJson(v).foreach(out(name) = _)
      }
    }

  /** 任意の JSON のログ（オブジェクト、またはその配列）。 */
  def logs(j: Json, nowMicros: Long, maxRows: Int): Either[String, Vector[Row]] =
    val objs = j.asArray.map(_.flatMap(_.asObject)).orElse(j.asObject.map(Vector(_))).getOrElse(Vector.empty)
    if objs.isEmpty then Left("JSON のオブジェクト、またはオブジェクトの配列を送ってください")
    else if objs.size > maxRows then Left(s"1回に送れるのは $maxRows 件までです")
    else Right(objs.map(obj(_, nowMicros)))

  def obj(o: JsonObject, nowMicros: Long = System.currentTimeMillis() * 1000): Row =
    val ts = timeKeys.iterator.flatMap(k => o(k).flatMap(toMicros)).nextOption().getOrElse(nowMicros)
    val f  = mutable.LinkedHashMap.empty[String, Value]
    flatten(o.filterKeys(k => !timeKeys.contains(k)), f)
    Row(ts, f.toMap)

  /** NDJSON（1行に1つのオブジェクト）。 */
  def ndjson(text: String, nowMicros: Long, maxRows: Int): Either[String, Vector[Row]] =
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    if lines.size > maxRows then Left(s"1回に送れるのは $maxRows 件までです")
    else
      val parsed = lines.zipWithIndex.map((l, i) => io.circe.parser.parse(l).toOption.flatMap(_.asObject).toRight(s"${i + 1}行目が JSON のオブジェクトではありません"))
      parsed.collectFirst { case Left(e) => e }.toLeft(parsed.collect { case Right(o) => obj(o, nowMicros) })

  // ---------------------------------------------------------------- RUM

  /** User-Agent からブラウザ・OS・端末の種類。 */
  def userAgent(ua: String): Map[String, Value] =
    def ver(re: String): Option[String] = re.r.findFirstMatchIn(ua).map(_.group(1).takeWhile(_ != '.'))
    val browser =
      if ua.contains("Edg/") then Some("Edge" -> ver("Edg/([\\d.]+)"))
      else if ua.contains("OPR/") then Some("Opera" -> ver("OPR/([\\d.]+)"))
      else if ua.contains("Firefox/") then Some("Firefox" -> ver("Firefox/([\\d.]+)"))
      else if ua.contains("Chrome/") then Some("Chrome" -> ver("Chrome/([\\d.]+)"))
      else if ua.contains("Safari/") then Some("Safari" -> ver("Version/([\\d.]+)"))
      else None
    val os =
      if ua.contains("Windows") then "Windows"
      else if ua.contains("iPhone") || ua.contains("iPad") then "iOS"
      else if ua.contains("Android") then "Android"
      else if ua.contains("Mac OS X") then "macOS"
      else if ua.contains("CrOS") then "ChromeOS"
      else if ua.contains("Linux") then "Linux"
      else "other"
    val device =
      if ua.contains("iPad") || ua.contains("Tablet") then "tablet"
      else if ua.contains("Mobi") || ua.contains("iPhone") then "mobile"
      else if ua.matches("(?i).*(bot|crawler|spider).*") then "bot"
      else "desktop"
    Map("user_agent" -> Value.Str(ua.take(500)), "os" -> Value.Str(os), "device" -> Value.Str(device)) ++
      browser.map((b, v) => Map("browser" -> Value.Str(b), "browser_version" -> Value.Str(v.getOrElse("")))).getOrElse(Map.empty)

  /**
   * ブラウザの SDK（/rum.js）から送られたイベント。{"events": [{type, ts, ...}], "ctx": {...}}。
   * 共通の ctx を各イベントに足し、User-Agent の情報と app を付ける。
   */
  def rum(j: Json, app: String, ua: String, nowMicros: Long, maxRows: Int): Either[String, Vector[Row]] =
    val c      = j.hcursor
    val events = c.downField("events").as[Vector[Json]].getOrElse(Vector.empty).flatMap(_.asObject)
    if events.isEmpty then Left("events がありません")
    else if events.size > maxRows then Left(s"1回に送れるのは $maxRows 件までです")
    else
      val ctx  = c.downField("ctx").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
      val base = mutable.LinkedHashMap.empty[String, Value]
      flatten(ctx, base)
      base ++= userAgent(ua)
      base("app") = Value.Str(app)
      Right(events.map { e =>
        val f = mutable.LinkedHashMap.empty[String, Value]
        f ++= base
        flatten(e.filterKeys(k => k != "ts" && k != "type"), f)
        f("event_type") = Value.Str(e("type").flatMap(_.asString).getOrElse("custom").take(40))
        // 時刻はブラウザの時計なので、大きくずれていればサーバーの時刻にする
        val ts = e("ts").flatMap(toMicros).filter(t => math.abs(t - nowMicros) < 24L * 3600 * 1000000).getOrElse(nowMicros)
        Row(ts, f.toMap)
      })
