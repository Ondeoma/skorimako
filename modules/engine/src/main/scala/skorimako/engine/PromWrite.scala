package skorimako.engine

import com.google.protobuf.CodedInputStream
import skorimako.core.*

import scala.collection.mutable

/**
 * Prometheus の remote write（snappy で圧縮した protobuf の WriteRequest）を行に直す。
 * TimeSeries（labels・samples）だけを読み、メトリクスの名前（__name__）ごとのストリームにする。metadata があれば種類・説明・単位も。
 */
object PromWrite:

  def decompress(body: Array[Byte]): Either[String, Array[Byte]] =
    scala.util.Try {
      val len = io.airlift.compress.snappy.SnappyDecompressor.getUncompressedLength(body, 0)
      if len > 256 * 1024 * 1024 then throw new IllegalArgumentException("too large")
      val out = new Array[Byte](len)
      val n = new io.airlift.compress.snappy.SnappyDecompressor().decompress(body, 0, body.length, out, 0, len)
      if n == len then out else java.util.Arrays.copyOf(out, n)
    }.toEither.left.map(_ => "snappy で展開できません")

  private def readLabel(in: CodedInputStream): (String, String) =
    var n = ""; var v = ""
    while !in.isAtEnd do
      val tag = in.readTag()
      (tag >>> 3) match
        case 1 => n = in.readString()
        case 2 => v = in.readString()
        case _ => in.skipField(tag)
    (n, v)

  private def readSample(in: CodedInputStream): (Double, Long) =
    var v = 0.0; var t = 0L
    while !in.isAtEnd do
      val tag = in.readTag()
      (tag >>> 3) match
        case 1 => v = in.readDouble()
        case 2 => t = in.readInt64()
        case _ => in.skipField(tag)
    (v, t)

  private def sub[A](in: CodedInputStream)(f: CodedInputStream => A): A =
    val bytes = in.readBytes()
    f(bytes.newCodedInput())

  def parse(raw: Array[Byte], nowMicros: Long): Either[String, Batch] =
    scala.util.Try {
      val in = CodedInputStream.newInstance(raw)
      in.setSizeLimit(Int.MaxValue)
      val rows = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Row]]
      val meta = mutable.Map.empty[String, MetricMeta]
      while !in.isAtEnd do
        val tag = in.readTag()
        (tag >>> 3) match
          case 1 =>
            sub(in) { ts =>
              val labels = mutable.ArrayBuffer.empty[(String, String)]
              val samples = mutable.ArrayBuffer.empty[(Double, Long)]
              while !ts.isAtEnd do
                val t = ts.readTag()
                (t >>> 3) match
                  case 1 => labels += sub(ts)(readLabel)
                  case 2 => samples += sub(ts)(readSample)
                  case _ => ts.skipField(t)
              labels.find(_._1 == "__name__").flatMap(l => Names.metric(l._2)).foreach { name =>
                val fields = labels.filter(_._1 != "__name__").flatMap((k, v) => Names.column(k).map(_ -> Value.Str(v))).toMap
                val buf = rows.getOrElseUpdate(name, mutable.ArrayBuffer.empty)
                samples.foreach { (v, t) =>
                  buf += Row(if t > 0 then t * 1000 else nowMicros,
                    fields ++ Map("__name__" -> Value.Str(name), "__type__" -> Value.Str("prometheus"), "value" -> Value.Dbl(v)))
                }
              }
            }
          case 3 =>
            sub(in) { md =>
              var typ = 0; var family = ""; var help = ""; var unit = ""
              while !md.isAtEnd do
                val t = md.readTag()
                (t >>> 3) match
                  case 1 => typ = md.readEnum()
                  case 2 => family = md.readString()
                  case 4 => help = md.readString()
                  case 5 => unit = md.readString()
                  case _ => md.skipField(t)
              val kind = typ match
                case 1 => "counter"
                case 2 => "gauge"
                case 3 | 5 => "histogram"
                case 6 => "summary"
                case _ => ""
              Names.metric(family).foreach(n => meta(n) = MetricMeta(help.take(1000), unit.take(50), kind))
            }
          case _ => in.skipField(tag)
      // ヒストグラム・サマリーは _bucket・_sum・_count のストリームにも同じ説明を付ける
      val metas = rows.keys.flatMap { n =>
        meta.get(n).orElse(List("_bucket", "_sum", "_count").collectFirst { case suf if n.endsWith(suf) && meta.contains(n.dropRight(suf.length)) => meta(n.dropRight(suf.length)) })
          .map(n -> _)
      }.toMap
      Batch(rows.view.mapValues(_.toVector).toMap, metas)
    }.toEither.left.map(e => s"remote write の本文が読めません: ${Option(e.getMessage).getOrElse("")}".take(300))
