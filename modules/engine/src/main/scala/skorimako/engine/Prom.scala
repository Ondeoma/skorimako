package skorimako.engine

import skorimako.core.*
import skorimako.core.PromQL.{Expr, MatchOp, VectorMatching}

import scala.collection.mutable

/**
 * PromQL の評価。式の中のセレクターのデータ（メトリクスのストリーム）をまとめて読み、各時刻で式を評価する。
 * 意味は Prometheus に合わせる（直近5分の最後の点、rate の外挿、histogram_quantile の線形補間、集計の by・without、ベクトルの照合）。
 */
object Prom {

  type Labels = Map[String, String]
  final case class Series(labels: Labels, ts: Array[Long], vs: Array[Double]) // ts はミリ秒（昇順）

  enum V {
    case Scalar(
      v: Double)
    case Str(
      s: String)
    case Instant(
      xs: List[(Labels, Double)])
    case Range(
      xs: List[(Labels, Array[Long], Array[Double])])
  }

  /** 結果（Prometheus の HTTP API の resultType に対応）。 */
  enum Result {
    case Matrix(
      series: List[(Labels, Vector[(Long, Double)])])
    case Vec(
      t: Long,
      xs: List[(Labels, Double)])
    case Scal(
      t: Long,
      v: Double)
    case Strg(
      t: Long,
      s: String)
  }

  val LookbackDelta = 300000L
  private val NameLabel = "__name__"

  private def dropName(l: Labels): Labels = l - NameLabel

  final class Evaluator(data: Map[Expr.Selector, List[Series]]) {

    private def window(s: Series, from: Long, to: Long): (Array[Long], Array[Double]) = {
      // (from, to] の点
      var a = java.util.Arrays.binarySearch(s.ts, from)
      a = if a >= 0 then a + 1 else -a - 1
      var b = java.util.Arrays.binarySearch(s.ts, to)
      b = if b >= 0 then b + 1 else -b - 1
      (s.ts.slice(a, b), s.vs.slice(a, b))
    }

    def eval(e: Expr, t: Long): V = e match {
      case Expr.Num(v) => V.Scalar(v)
      case Expr.Str(s) => V.Str(s)
      case Expr.Paren(x) => eval(x, t)
      case Expr.Neg(x) => eval(x, t) match {
          case V.Scalar(v) => V.Scalar(-v)
          case V.Instant(xs) => V.Instant(xs.map((l, v) => dropName(l) -> -v))
          case _ => throw QueryError("単項の - は数値かベクトルにだけ使えます")
        }
      case s: Expr.Selector =>
        val at = t - s.offset
        val ss = data.getOrElse(s.copy(range = None, offset = 0), Nil)
        s.range match {
          case None =>
            V.Instant(ss.flatMap { x =>
              val (ts, vs) = window(x, at - LookbackDelta, at)
              Option.when(ts.nonEmpty && !vs.last.isNaN)(x.labels -> vs.last)
            })
          case Some(r) => V.Range(ss.flatMap { x =>
              val (ts, vs) = window(x, at - r, at); Option.when(ts.nonEmpty)((x.labels, ts, vs))
            })
        }
      case Expr.Subquery(x, r, st) =>
        val step = st.getOrElse(60000L)
        val ts = ((t - r) / step + 1 to t / step).map(_ * step).toVector
        val acc = mutable.LinkedHashMap.empty[Labels, mutable.ArrayBuffer[(Long, Double)]]
        ts.foreach(tt =>
          eval(x, tt) match {
            case V.Instant(xs) => xs.foreach((l, v) => acc.getOrElseUpdate(l, mutable.ArrayBuffer.empty) += tt -> v)
            case V.Scalar(v) => acc.getOrElseUpdate(Map.empty, mutable.ArrayBuffer.empty) += tt -> v
            case _ => throw QueryError("サブクエリの中はベクトルにしてください")
          }
        )
        V.Range(acc.toList.map((l, ps) => (l, ps.map(_._1).toArray, ps.map(_._2).toArray)))
      case Expr.Call(fn, args) => call(fn, args, t)
      case Expr.Agg(op, param, x, by, without) => aggregate(op, param.map(eval(_, t)), eval(x, t), by, without)
      case Expr.Binary(op, l, r, bool, m) => binary(op, eval(l, t), eval(r, t), bool, m)
    }

    // ---------------------------------------------------------------- 関数

    private def instant(v: V, fn: String): List[(Labels, Double)] = v match {
      case V.Instant(xs) => xs
      case _ => throw QueryError(s"$fn にはベクトルを渡してください")
    }

    private def range(v: V, fn: String): List[(Labels, Array[Long], Array[Double])] = v match {
      case V.Range(xs) => xs
      case _ => throw QueryError(s"$fn には範囲（[5m] など）を付けたセレクターを渡してください")
    }

    private def scalar(v: V, fn: String): Double = v match {
      case V.Scalar(x) => x
      case _ => throw QueryError(s"$fn の引数には数値を渡してください")
    }

    private def extrapolated(ts: Array[Long],
                             vs: Array[Double],
                             from: Long,
                             to: Long,
                             counter: Boolean,
                             rate: Boolean): Option[Double] =
      if ts.length < 2 then None
      else {
        var result = vs.last - vs.head
        if counter then {
          var prev = vs.head
          vs.tail.foreach { v => if v < prev then result += prev; prev = v }
        }
        var toStart = (ts.head - from) / 1000.0
        val toEnd = (to - ts.last) / 1000.0
        val sampled = (ts.last - ts.head) / 1000.0
        val avg = sampled / (ts.length - 1)
        if counter && result > 0 && vs.head >= 0 then {
          val toZero = sampled * (vs.head / result)
          if toZero < toStart then toStart = toZero
        }
        val threshold = avg * 1.1
        var interval = sampled
        interval += (if toStart < threshold then toStart else avg / 2)
        interval += (if toEnd < threshold then toEnd else avg / 2)
        var factor = interval / sampled
        if rate then factor /= (to - from) / 1000.0
        Some(result * factor)
      }

    private def call(fn: String, args: List[Expr], t: Long): V = {
      def arg(i: Int): V = args.lift(i).map(eval(_, t)).getOrElse(throw QueryError(s"$fn の引数が足りません"))
      def rangeOf(i: Int): (Long, List[(Labels, Array[Long], Array[Double])]) = {
        val r = args.lift(i) match {
          case Some(s: Expr.Selector) => s.range.getOrElse(0L)
          case Some(Expr.Subquery(_, r, _)) => r
          case _ => 0L
        }
        (r, range(arg(i), fn))
      }
      def overTime(f: (Array[Long], Array[Double]) => Option[Double]): V =
        V.Instant(range(arg(0), fn).flatMap((l, ts, vs) => f(ts, vs).map(dropName(l) -> _)))
      def math1(f: Double => Double): V = V.Instant(instant(arg(0), fn).map((l, v) => dropName(l) -> f(v)))
      fn match {
        case "rate" | "increase" | "delta" =>
          val (r, xs) = rangeOf(0)
          V.Instant(xs.flatMap((l, ts, vs) =>
            extrapolated(ts, vs, t - r, t, fn != "delta", fn == "rate").map(dropName(l) -> _)
          ))
        case "irate" | "idelta" => overTime {
            (ts, vs) =>
              if ts.length < 2 then None
              else {
                val d = vs.last - vs(vs.length - 2)
                val dv = if fn == "irate" && d < 0 then vs.last else d
                if fn == "irate" then Some(dv / ((ts.last - ts(ts.length - 2)) / 1000.0)) else Some(dv)
              }
          }
        case "deriv" => overTime {
            (ts, vs) =>
              if ts.length < 2 then None
              else {
                val xs = ts.map(_ / 1000.0); val mx = xs.sum / xs.length; val my = vs.sum / vs.length
                val num = xs.indices.map(i => (xs(i) - mx) * (vs(i) - my)).sum
                val den = xs.map(x => (x - mx) * (x - mx)).sum
                Option.when(den != 0)(num / den)
              }
          }
        case "avg_over_time" => overTime((_, vs) => Option.when(vs.nonEmpty)(vs.sum / vs.length))
        case "min_over_time" => overTime((_, vs) => vs.minOption)
        case "max_over_time" => overTime((_, vs) => vs.maxOption)
        case "sum_over_time" => overTime((_, vs) => Some(vs.sum))
        case "count_over_time" => overTime((_, vs) => Some(vs.length.toDouble))
        case "present_over_time" => overTime((_, vs) => Option.when(vs.nonEmpty)(1.0))
        case "last_over_time" => V.Instant(range(arg(0), fn).flatMap((l, _, vs) => vs.lastOption.map(l -> _)))
        case "stddev_over_time" => overTime {
            (_, vs) =>
              Option.when(vs.nonEmpty) {
                val m = vs.sum / vs.length; math.sqrt(vs.map(v => (v - m) * (v - m)).sum / vs.length)
              }
          }
        case "quantile_over_time" =>
          val q = scalar(arg(0), fn)
          V.Instant(range(arg(1), fn).flatMap((l, _, vs) =>
            Option.when(vs.nonEmpty)(dropName(l) -> quantile(q, vs.toList))
          ))
        case "changes" => overTime((_, vs) => Some(vs.sliding(2).count(p => p.length == 2 && p(0) != p(1)).toDouble))
        case "resets" => overTime((_, vs) => Some(vs.sliding(2).count(p => p.length == 2 && p(1) < p(0)).toDouble))
        case "abs" => math1(math.abs)
        case "ceil" => math1(math.ceil)
        case "floor" => math1(math.floor)
        case "exp" => math1(math.exp)
        case "ln" => math1(math.log)
        case "log2" => math1(v => math.log(v) / math.log(2))
        case "log10" => math1(math.log10)
        case "sqrt" => math1(math.sqrt)
        case "sgn" => math1(math.signum)
        case "round" =>
          val to = args.lift(1).map(a => scalar(eval(a, t), fn)).getOrElse(1.0)
          math1(v => math.floor(v / to + 0.5) * to)
        case "clamp_min" => val m = scalar(arg(1), fn); math1(v => math.max(v, m))
        case "clamp_max" => val m = scalar(arg(1), fn); math1(v => math.min(v, m))
        case "clamp" =>
          val lo = scalar(arg(1), fn); val hi = scalar(arg(2), fn); math1(v => math.max(lo, math.min(hi, v)))
        case "scalar" => instant(arg(0), fn) match {
            case (_, v) :: Nil => V.Scalar(v)
            case _ => V.Scalar(Double.NaN)
          }
        case "vector" => V.Instant(List(Map.empty -> scalar(arg(0), fn)))
        case "time" => V.Scalar(t / 1000.0)
        case "timestamp" => V.Instant(instant(arg(0), fn).map((l, _) => dropName(l) -> t / 1000.0))
        case "sort" => V.Instant(instant(arg(0), fn).sortBy(_._2))
        case "sort_desc" => V.Instant(instant(arg(0), fn).sortBy(-_._2))
        case "absent" =>
          if instant(arg(0), fn).nonEmpty then V.Instant(Nil)
          else {
            val ls = args.headOption.collect { case s: Expr.Selector =>
              s.matchers.collect { case m if m.op == MatchOp.Eq && m.label != NameLabel => m.label -> m.value }
            }
            V.Instant(List(ls.getOrElse(Nil).toMap -> 1.0))
          }
        case "label_replace" =>
          val (dst, repl, src, re) = (args.lift(1), args.lift(2), args.lift(3), args.lift(4)) match {
            case (Some(Expr.Str(a)), Some(Expr.Str(b)), Some(Expr.Str(c)), Some(Expr.Str(d))) => (a, b, c, d.r)
            case _ => throw QueryError("label_replace(v, \"dst\", \"replacement\", \"src\", \"regex\") の形で書いてください")
          }
          V.Instant(instant(arg(0), fn).map {
            (l, v) =>
              val sv = l.getOrElse(src, "")
              re.unapplySeq(sv) match {
                case Some(groups) =>
                  val out = groups.zipWithIndex.foldLeft(repl)((acc, gi) =>
                    acc.replace("$" + (gi._2 + 1), Option(gi._1).getOrElse(""))
                  )
                  (if out.isEmpty then l - dst else l + (dst -> out)) -> v
                case None => l -> v
              }
          })
        case "histogram_quantile" =>
          val q = scalar(arg(0), fn)
          val xs = instant(arg(1), fn)
          V.Instant(xs.groupBy((l, _) => dropName(l) - "le").toList.flatMap {
            (ls, bs) =>
              val buckets = bs.flatMap((l, v) =>
                l.get("le").flatMap(le =>
                  (if le == "+Inf" then Some(Double.PositiveInfinity) else le.toDoubleOption).map(_ -> v)
                )
              )
              Option.when(buckets.nonEmpty)(ls -> bucketQuantile(q, buckets))
          })
        case other => throw QueryError(s"関数 $other には対応していません")
      }
    }

    private def bucketQuantile(q: Double, bs0: List[(Double, Double)]): Double =
      if q < 0 then Double.NegativeInfinity
      else if q > 1 then Double.PositiveInfinity
      else {
        val bs = bs0.sortBy(_._1).toVector
        if bs.size < 2 || !bs.last._1.isPosInfinity then Double.NaN
        else {
          // 単調に増えるようにそろえる
          val counts = bs.map(_._2).scanLeft(0.0)(math.max).tail
          val total = counts.last
          if total == 0 then Double.NaN
          else {
            val rank = q * total
            val b = counts.indexWhere(_ >= rank)
            if b == bs.size - 1 then bs(bs.size - 2)._1
            else if b == 0 && bs(0)._1 <= 0 then bs(0)._1
            else {
              val start = if b == 0 then 0.0 else bs(b - 1)._1
              val end = bs(b)._1
              val prev = if b == 0 then 0.0 else counts(b - 1)
              start + (end - start) * ((rank - prev) / (counts(b) - prev))
            }
          }
        }
      }

    private def quantile(q: Double, vs: List[Double]): Double =
      if vs.isEmpty then Double.NaN
      else if q < 0 then Double.NegativeInfinity
      else if q > 1 then Double.PositiveInfinity
      else {
        val s = vs.sorted.toVector
        val rank = q * (s.size - 1)
        val lo = math.floor(rank).toInt; val hi = math.min(lo + 1, s.size - 1)
        s(lo) + (s(hi) - s(lo)) * (rank - lo)
      }

    // ---------------------------------------------------------------- 集計

    private def aggregate(op: String, param: Option[V], v: V, by: List[String], without: Boolean): V = {
      val xs = instant(v, op)
      def key(l: Labels): Labels = if without then dropName(l) -- by
      else
        l.filter((k, _) => by.contains(k))
      val groups = xs.groupBy((l, _) => key(l)).toList
      op match {
        case "topk" | "bottomk" =>
          val k = param.map(scalar(_, op)).getOrElse(1.0).toInt
          V.Instant(groups.flatMap((_, g) => (if op == "topk" then g.sortBy(-_._2) else g.sortBy(_._2)).take(k)))
        case _ =>
          V.Instant(groups.map {
            (k, g) =>
              val vs = g.map(_._2)
              k ->
                (op match {
                  case "sum" => vs.sum
                  case "avg" => vs.sum / vs.size
                  case "min" => vs.min
                  case "max" => vs.max
                  case "count" => vs.size.toDouble
                  case "group" => 1.0
                  case "stddev" | "stdvar" =>
                    val m = vs.sum / vs.size
                    val variance = vs.map(x => (x - m) * (x - m)).sum / vs.size
                    if op == "stddev" then math.sqrt(variance) else variance
                  case "quantile" => quantile(param.map(scalar(_, op)).getOrElse(0.5), vs)
                  case other => throw QueryError(s"集計 $other には対応していません")
                })
          })
      }
    }

    // ---------------------------------------------------------------- 二項演算

    private val comparisons = Set("==", "!=", ">", "<", ">=", "<=")

    private def arith(op: String, a: Double, b: Double): Double = op match {
      case "+" => a + b
      case "-" => a - b
      case "*" => a * b
      case "/" => a / b
      case "%" => a % b
      case "^" => math.pow(a, b)
      case "==" => if a == b then 1 else 0
      case "!=" => if a != b then 1 else 0
      case ">" => if a > b then 1 else 0
      case "<" => if a < b then 1 else 0
      case ">=" => if a >= b then 1 else 0
      case "<=" => if a <= b then 1 else 0
      case _ => throw QueryError(s"演算子 $op は数値には使えません")
    }

    private def signature(l: Labels, m: Option[VectorMatching]): Labels = m match {
      case Some(VectorMatching(true, ls, _, _)) => l.filter((k, _) => ls.contains(k))
      case Some(VectorMatching(false, ls, _, _)) => dropName(l) -- ls
      case None => dropName(l)
    }

    private def binary(op: String, l: V, r: V, bool: Boolean, m: Option[VectorMatching]): V = {
      val cmp = comparisons.contains(op)
      (l, r) match {
        case (V.Scalar(a), V.Scalar(b)) =>
          if cmp && !bool then throw QueryError("数値どうしの比較には bool を付けてください") else V.Scalar(arith(op, a, b))
        case (V.Instant(xs), V.Scalar(b)) =>
          V.Instant(xs.flatMap {
            (lb, a) =>
              val v = arith(op, a, b)
              if cmp && !bool then Option.when(v == 1)(lb -> a) else Some(dropName(lb) -> v)
          })
        case (V.Scalar(a), V.Instant(xs)) =>
          V.Instant(xs.flatMap {
            (lb, b) =>
              val v = arith(op, a, b)
              if cmp && !bool then Option.when(v == 1)(lb -> b) else Some(dropName(lb) -> v)
          })
        case (V.Instant(ls), V.Instant(rs)) if Set("and", "or", "unless").contains(op) =>
          val rsig = rs.map((lb, _) => signature(lb, m)).toSet
          op match {
            case "and" => V.Instant(ls.filter((lb, _) => rsig.contains(signature(lb, m))))
            case "unless" => V.Instant(ls.filterNot((lb, _) => rsig.contains(signature(lb, m))))
            case _ =>
              val lsig = ls.map((lb, _) => signature(lb, m)).toSet
              V.Instant(ls ++ rs.filterNot((lb, _) => lsig.contains(signature(lb, m))))
          }
        case (V.Instant(ls), V.Instant(rs)) =>
          val group = m.flatMap(_.group)
          val (one, many, manyIsLeft) = group match {
            case Some("group_right") => (ls, rs, false)
            case _ => (rs, ls, true)
          }
          val oneBySig = one.groupBy((lb, _) => signature(lb, m))
          if oneBySig.exists(_._2.size > 1) then
            throw QueryError("ベクトルの照合で、片方に同じラベルの組が複数あります（on・ignoring・group_left を使ってください）")
          val manyBySig = many.groupBy((lb, _) => signature(lb, m))
          if group.isEmpty && manyBySig.exists(_._2.size > 1) then
            throw QueryError("ベクトルの照合で、同じラベルの組が複数あります（group_left・group_right を使ってください）")
          V.Instant(many.flatMap {
            (mlb, mv) =>
              oneBySig.get(signature(mlb, m)).flatMap(_.headOption).flatMap {
                (olb, ov) =>
                  val (a, b) = if manyIsLeft then (mv, ov) else (ov, mv)
                  val v = arith(op, a, b)
                  val base: Labels =
                    if group.isDefined then
                      dropName(mlb) ++ m.toList.flatMap(_.include).flatMap(k => olb.get(k).map(k -> _))
                    else signature(mlb, m)
                  if cmp && !bool then Option.when(v == 1)((if manyIsLeft then mlb else olb) -> a)
                  else Some(base -> v)
              }
          })
        case _ => throw QueryError(s"演算子 $op の両辺の型が合いません")
      }
    }
  }

  // ---------------------------------------------------------------- 実行

  /** 範囲の問い合わせ（start・end・step はミリ秒）。 */
  def range(e: Expr, data: Map[Expr.Selector, List[Series]], start: Long, end: Long, step: Long): Result = {
    val ev = new Evaluator(data)
    val acc = mutable.LinkedHashMap.empty[Labels, mutable.ArrayBuffer[(Long, Double)]]
    var t = start
    while t <= end do {
      ev.eval(e, t) match {
        case V.Instant(xs) =>
          xs.foreach((l, v) => if !v.isNaN then acc.getOrElseUpdate(l, mutable.ArrayBuffer.empty) += t -> v)
        case V.Scalar(v) => acc.getOrElseUpdate(Map.empty, mutable.ArrayBuffer.empty) += t -> v
        case _ => throw QueryError("範囲の問い合わせの結果はベクトルか数値にしてください")
      }
      if acc.size > 10000 then throw QueryError("系列が多すぎます（1万まで）。集計（sum by など）で減らしてください")
      t += step
    }
    Result.Matrix(acc.toList.map((l, ps) => l -> ps.toVector))
  }

  /** 時刻 t の問い合わせ。 */
  def instant(e: Expr, data: Map[Expr.Selector, List[Series]], t: Long): Result =
    new Evaluator(data).eval(e, t) match {
      case V.Instant(xs) => Result.Vec(t, xs)
      case V.Scalar(v) => Result.Scal(t, v)
      case V.Str(s) => Result.Strg(t, s)
      case V.Range(xs) => Result.Matrix(xs.map((l, ts, vs) => l -> ts.zip(vs).toVector))
    }

  /** セレクターの対象のメトリクスのストリームの名前。 */
  def metricName(s: Expr.Selector): Option[String] =
    s.name.orElse(
      s.matchers.collectFirst { case m if m.label == NameLabel && m.op == MatchOp.Eq => m.value }
    ).flatMap(Names.metric)

  /** セレクターの条件のうち SQL にできるもの（= と !=）。正規表現は読んだ後で確かめる。 */
  def sqlConds(s: Expr.Selector): List[String] =
    s.matchers.filter(m => m.label != NameLabel && (m.op == MatchOp.Eq || m.op == MatchOp.Ne)).flatMap { m =>
      Names.column(m.label).map { c =>
        val col = SqlText.ident(c)
        if m.op == MatchOp.Eq then
          if m.value.isEmpty then s"($col IS NULL OR CAST($col AS VARCHAR) = '')"
          else s"CAST($col AS VARCHAR) = ${SqlText.lit(m.value)}"
        else if m.value.isEmpty then s"($col IS NOT NULL AND CAST($col AS VARCHAR) <> '')"
        else s"($col IS NULL OR CAST($col AS VARCHAR) <> ${SqlText.lit(m.value)})"
      }
    }

  def regexOk(s: Expr.Selector, labels: Labels): Boolean =
    s.matchers.forall { m =>
      m.label == NameLabel || m.op == MatchOp.Eq || m.op == MatchOp.Ne ||
      m.matches(labels.getOrElse(Names.column(m.label).getOrElse(m.label), ""))
    }
}
