package skorimako.core

/**
 * PromQL（Prometheus の問い合わせ言語）のよく使う部分の構文。
 * 対応: セレクター（name{a="b", c!="d", e=~"re", f!~"re"}）、範囲（[5m]）、offset、数値、括弧、単項の -、
 * 二項演算（+ - * / % ^、== != > < >= <=、bool、and or unless、on・ignoring、group_left・group_right）、
 * 集計（sum avg min max count stddev stdvar topk bottomk quantile count_values 以外、by・without）、関数（Functions.all）。
 */
object PromQL {

  enum MatchOp {
    case Eq, Ne, Re, Nre
  }

  final case class Matcher(
    label: String,
    op: MatchOp,
    value: String) {
    private lazy val re = value.r
    def matches(
      v: String
    ): Boolean = op match {
      case MatchOp.Eq => v == value
      case MatchOp.Ne => v != value
      case MatchOp.Re => re.matches(v)
      case MatchOp.Nre => !re.matches(v)
    }
  }

  final case class VectorMatching(
    on: Boolean,
    labels: List[String],
    group: Option[String],
    include: List[String])

  enum Expr {
    case Num(
      v: Double)
    case Str(
      v: String)
    case Selector(
      name: Option[String],
      matchers: List[Matcher],
      range: Option[Long],
      offset: Long) // range・offset はミリ秒
    case Call(
      fn: String,
      args: List[Expr])
    case Agg(
      op: String,
      param: Option[Expr],
      expr: Expr,
      by: List[String],
      without: Boolean)
    case Binary(
      op: String,
      lhs: Expr,
      rhs: Expr,
      bool: Boolean,
      matching: Option[VectorMatching])
    case Neg(
      e: Expr)
    case Paren(
      e: Expr)
    case Subquery(
      e: Expr,
      range: Long,
      step: Option[Long])
  }

  val aggregations =
    Set("sum", "avg", "min", "max", "count", "stddev", "stdvar", "topk", "bottomk", "quantile", "group")

  object Functions {
    val rangeFns = Set(
      "rate",
      "irate",
      "increase",
      "delta",
      "idelta",
      "avg_over_time",
      "min_over_time",
      "max_over_time",
      "sum_over_time",
      "count_over_time",
      "last_over_time",
      "stddev_over_time",
      "quantile_over_time",
      "changes",
      "resets",
      "deriv",
      "present_over_time"
    )
    val instantFns = Set(
      "abs",
      "ceil",
      "floor",
      "round",
      "exp",
      "ln",
      "log2",
      "log10",
      "sqrt",
      "clamp",
      "clamp_min",
      "clamp_max",
      "histogram_quantile",
      "scalar",
      "vector",
      "time",
      "timestamp",
      "sort",
      "sort_desc",
      "absent",
      "label_replace",
      "sgn"
    )
    val all: Set[String] = rangeFns ++ instantFns
  }

  /** 期間（5m・1h30m・10s・250ms・1d・1w・1y）をミリ秒に。 */
  def duration(
    s: String
  ): Either[String, Long] = {
    val re = "(\\d+)(ms|s|m|h|d|w|y)".r
    val parts = re.findAllMatchIn(s).toList
    if parts.isEmpty || parts.map(_.matched).mkString != s then Left(s"期間 $s が読めません（例: 5m・1h）")
    else
      Right(parts.map { m =>
        val n = m.group(1).toLong
        m.group(2) match {
          case "ms" => n
          case "s" => n * 1000
          case "m" => n * 60000
          case "h" => n * 3600000
          case "d" => n * 86400000
          case "w" => n * 604800000
          case _ => n * 31536000000L
        }
      }.sum)
  }

  // ---------------------------------------------------------------- 字句

  private enum Tok {
    case Ident(
      s: String)
    case Num(
      v: Double)
    case Str(
      s: String)
    case Dur(
      ms: Long)
    case Op(
      s: String)
    case End
  }

  private def lex(
    src: String
  ): Either[String, Vector[Tok]] = {
    val out = Vector.newBuilder[Tok]
    var i = 0
    val n = src.length
    var err: Option[String] = None
    def peek(
      k: Int = 0
    ) = if i + k < n then src(i + k) else '\u0000'
    while i < n && err.isEmpty do {
      val c = src(i)
      if c.isWhitespace then i += 1
      else if c == '#' then while i < n && src(i) != '\n' do i += 1
      else if c == '"' || c == '\'' || c == '`' then {
        val q = c; val b = new StringBuilder; i += 1
        var done = false
        while i < n && !done do {
          val d = src(i)
          if d == '\\' && q != '`' && i + 1 < n then {
            src(i + 1) match {
              case 'n' => b.append('\n')
              case 't' => b.append('\t')
              case x => b.append(x)
            }
            i += 2
          } else if d == q then { done = true; i += 1 }
          else { b.append(d); i += 1 }
        }
        if done then out += Tok.Str(b.toString) else err = Some("文字列が閉じていません")
      } else if c == '[' then {
        // 範囲 [5m] と [5m:1m]（サブクエリ）
        val e = src.indexOf(']', i)
        if e < 0 then err = Some("[ が閉じていません")
        else {
          out += Tok.Op("[")
          src.substring(i + 1, e).split(":", -1).toList match {
            case r :: Nil => duration(r.trim).fold(m => err = Some(m), ms => out += Tok.Dur(ms))
            case r :: st :: Nil =>
              duration(r.trim).fold(m => err = Some(m), ms => out += Tok.Dur(ms))
              out += Tok.Op(":")
              if st.trim.nonEmpty then duration(st.trim).fold(m => err = Some(m), ms => out += Tok.Dur(ms))
            case _ => err = Some("範囲が読めません")
          }
          out += Tok.Op("]")
          i = e + 1
        }
      } else if c.isDigit || (c == '.' && peek(1).isDigit) then {
        val s = i
        while i < n &&
          (src(i).isLetterOrDigit || src(i) == '.' ||
            ((src(i) == '+' || src(i) == '-') && (src(i - 1) == 'e' || src(i - 1) == 'E')))
        do i += 1
        val t = src.substring(s, i)
        t.toDoubleOption match {
          case Some(v) => out += Tok.Num(v)
          case None => duration(t).fold(_ => err = Some(s"数値 $t が読めません"), ms => out += Tok.Dur(ms))
        }
      } else if c.isLetter || c == '_' || c == ':' then {
        val s = i
        while i < n && (src(i).isLetterOrDigit || src(i) == '_' || src(i) == ':') do i += 1
        out += Tok.Ident(src.substring(s, i))
      } else {
        val two = src.substring(i, math.min(n, i + 2))
        if Set("==", "!=", ">=", "<=", "=~", "!~").contains(two) then { out += Tok.Op(two); i += 2 }
        else if "+-*/%^(){},=<>".contains(c) then { out += Tok.Op(c.toString); i += 1 }
        else err = Some(s"使えない文字 $c")
      }
    }
    out += Tok.End
    err.toLeft(out.result())
  }

  // ---------------------------------------------------------------- 構文

  private final class Parser(
    ts: Vector[Tok]) {
    var p = 0
    def peek: Tok = ts(p)
    def next(): Tok = { val t = ts(p); p += 1; t }
    def isOp(
      s: String
    ): Boolean = peek == Tok.Op(s)
    def isWord(
      s: String
    ): Boolean = peek match {
      case Tok.Ident(w) => w.equalsIgnoreCase(s)
      case _ => false
    }
    def expect(
      s: String
    ): Unit = if isOp(s) then p += 1 else throw new IllegalArgumentException(s"$s がありません")

    private val precedence: List[Set[String]] =
      List(
        Set("or"),
        Set("and", "unless"),
        Set("==", "!=", ">", "<", ">=", "<="),
        Set("+", "-"),
        Set("*", "/", "%", "atan2"),
        Set("^")
      )

    def binOp: Option[String] = peek match {
      case Tok.Op(o) if Set("+", "-", "*", "/", "%", "^", "==", "!=", ">", "<", ">=", "<=").contains(o) => Some(o)
      case Tok.Ident(w) if Set("and", "or", "unless").contains(w.toLowerCase) => Some(w.toLowerCase)
      case _ => None
    }

    def parse(
      level: Int = 0
    ): Expr =
      if level >= precedence.size then unary()
      else {
        var lhs = parse(level + 1)
        var go = true
        while go do
          binOp.filter(precedence(level).contains) match {
            case Some(op) =>
              p += 1
              val bool = if isWord("bool") then { p += 1; true }
              else false
              val matching = vectorMatching()
              // ^ は右結合
              val rhs = if op == "^" then parse(level) else parse(level + 1)
              lhs = Expr.Binary(op, lhs, rhs, bool, matching)
            case None => go = false
          }
        lhs
      }

    def labelList(): List[String] = {
      expect("(")
      val ls = scala.collection.mutable.ListBuffer.empty[String]
      while !isOp(")") do {
        next() match {
          case Tok.Ident(w) => ls += w
          case _ => throw new IllegalArgumentException("ラベルの名前がありません")
        }
        if isOp(",") then p += 1
      }
      expect(")")
      ls.toList
    }

    def vectorMatching(): Option[VectorMatching] =
      if isWord("on") || isWord("ignoring") then {
        val on = isWord("on"); p += 1
        val ls = labelList()
        val (g, inc) =
          if isWord("group_left") || isWord("group_right") then {
            val g = next().asInstanceOf[Tok.Ident].s.toLowerCase
            (Some(g), if isOp("(") then labelList() else Nil)
          } else (None, Nil)
        Some(VectorMatching(on, ls, g, inc))
      } else None

    def unary(): Expr =
      if isOp("-") then { p += 1; Expr.Neg(unary()) }
      else if isOp("+") then { p += 1; unary() }
      else postfix(primary())

    def postfix(
      e0: Expr
    ): Expr = {
      var e = e0
      var go = true
      while go do
        if isOp("[") then {
          p += 1
          val r = next() match { case Tok.Dur(ms) => ms; case _ => throw new IllegalArgumentException("範囲がありません") }
          if isOp(":") then {
            p += 1
            val st = peek match { case Tok.Dur(ms) => p += 1; Some(ms); case _ => None }
            expect("]")
            e = Expr.Subquery(e, r, st)
          } else {
            expect("]")
            e = e match {
              case s: Expr.Selector => s.copy(range = Some(r))
              case _ => throw new IllegalArgumentException("範囲 [..] はセレクターにだけ付けられます")
            }
          }
        } else if isWord("offset") then {
          p += 1
          val neg = if isOp("-") then { p += 1; true }
          else false
          val ms = next() match {
            case Tok.Dur(ms) => ms; case Tok.Num(v) => (v * 1000).toLong;
            case _ => throw new IllegalArgumentException("offset の期間がありません")
          }
          e = e match {
            case s: Expr.Selector => s.copy(offset = if neg then -ms else ms)
            case _ => throw new IllegalArgumentException("offset はセレクターにだけ付けられます")
          }
        } else go = false
      e
    }

    def matchers(): List[Matcher] = {
      expect("{")
      val ms = scala.collection.mutable.ListBuffer.empty[Matcher]
      while !isOp("}") do {
        val l = next() match {
          case Tok.Ident(w) => w; case Tok.Str(s) => s; case _ => throw new IllegalArgumentException("ラベルの名前がありません")
        }
        val op = next() match {
          case Tok.Op("=") => MatchOp.Eq
          case Tok.Op("!=") => MatchOp.Ne
          case Tok.Op("=~") => MatchOp.Re
          case Tok.Op("!~") => MatchOp.Nre
          case _ => throw new IllegalArgumentException("ラベルの比べ方（= != =~ !~）がありません")
        }
        val v =
          next() match { case Tok.Str(s) => s; case _ => throw new IllegalArgumentException("ラベルの値（\"…\"）がありません") }
        ms += Matcher(l, op, v)
        if isOp(",") then p += 1
      }
      expect("}")
      ms.toList
    }

    def args(): List[Expr] = {
      expect("(")
      val as = scala.collection.mutable.ListBuffer.empty[Expr]
      while !isOp(")") do {
        as += parse()
        if isOp(",") then p += 1
      }
      expect(")")
      as.toList
    }

    def primary(): Expr = next() match {
      case Tok.Num(v) => Expr.Num(v)
      case Tok.Str(s) => Expr.Str(s)
      case Tok.Op("(") =>
        val e = parse(); expect(")"); Expr.Paren(e)
      case Tok.Op("{") =>
        p -= 1; Expr.Selector(None, matchers(), None, 0)
      case Tok.Ident(w) if aggregations.contains(w.toLowerCase) && (isOp("(") || isWord("by") || isWord("without")) =>
        val op = w.toLowerCase
        def mods(): (List[String], Boolean) =
          if isWord("by") then { p += 1; (labelList(), false) }
          else if isWord("without") then { p += 1; (labelList(), true) }
          else (Nil, false)
        val (b1, w1) = mods()
        val as = args()
        val (b2, w2) = if b1.isEmpty && !w1 then mods() else (b1, w1)
        val (param, inner) = as match {
          case e :: Nil if !Set("topk", "bottomk", "quantile").contains(op) => (None, e)
          case prm :: e :: Nil => (Some(prm), e)
          case _ => throw new IllegalArgumentException(s"$op の引数が正しくありません")
        }
        Expr.Agg(op, param, inner, b2, w2)
      case Tok.Ident(w) if isOp("(") && Functions.all.contains(w) => Expr.Call(w, args())
      case Tok.Ident(w) if isOp("(") => throw new IllegalArgumentException(s"関数 $w には対応していません")
      case Tok.Ident(w) =>
        val ms = if isOp("{") then matchers() else Nil
        Expr.Selector(Some(w), ms, None, 0)
      case Tok.End => throw new IllegalArgumentException("式が途中で終わっています")
      case t => throw new IllegalArgumentException(s"思いがけない字句 $t")
    }
  }

  def parse(
    src: String
  ): Either[String, Expr] =
    lex(src).flatMap { ts =>
      scala.util.Try {
        val ps = new Parser(ts)
        val e = ps.parse()
        if ps.peek != Tok.End then throw new IllegalArgumentException(s"余分な字句があります: ${ps.peek}")
        e
      }.toEither.left.map(e => s"PromQL が読めません: ${Option(e.getMessage).getOrElse(e.toString)}")
    }

  /** 式の中のセレクター（データを読む対象）。 */
  def selectors(
    e: Expr
  ): List[Expr.Selector] = e match {
    case s: Expr.Selector => List(s)
    case Expr.Call(_, as) => as.flatMap(selectors)
    case Expr.Agg(_, p, x, _, _) => p.toList.flatMap(selectors) ++ selectors(x)
    case Expr.Binary(_, l, r, _, _) => selectors(l) ++ selectors(r)
    case Expr.Neg(x) => selectors(x)
    case Expr.Paren(x) => selectors(x)
    case Expr.Subquery(x, _, _) => selectors(x)
    case _ => Nil
  }

  /** 式が必要とする過去の長さ（範囲 + offset + サブクエリ）。 */
  def lookback(
    e: Expr
  ): Long = e match {
    case s: Expr.Selector => s.range.getOrElse(0L) + math.max(0L, s.offset)
    case Expr.Call(_, as) => as.map(lookback).maxOption.getOrElse(0L)
    case Expr.Agg(_, _, x, _, _) => lookback(x)
    case Expr.Binary(_, l, r, _, _) => math.max(lookback(l), lookback(r))
    case Expr.Neg(x) => lookback(x)
    case Expr.Paren(x) => lookback(x)
    case Expr.Subquery(x, r, _) => r + lookback(x)
    case _ => 0L
  }
}
