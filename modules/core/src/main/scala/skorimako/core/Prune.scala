package skorimako.core

import SqlText.Token

/**
 * 条件の式から「列 = 値」「列 IN (値, …)」の制約を取り出す（一番外側が AND でつながっているものだけ）。
 * ファイルごとの値の一覧（tags）と照らして、読まなくてよいファイルを決めるのに使う。OR を含むなど判断できないときは何も返さない。
 */
object Prune {

  /** 列 → 取りうる値。 */
  def constraints(where: Option[String]): Map[String, Set[String]] =
    where.map(_.trim).filter(_.nonEmpty).flatMap(w => SqlText.tokenize(w).toOption).map(fromTokens).getOrElse(Map.empty)

  private def name(t: Token): Option[String] = t match {
    case Token.Word(w) => Some(w.toLowerCase)
    case Token.Quoted(q) => Some(q)
    case _ => None
  }

  def fromTokens(ts: List[Token]): Map[String, Set[String]] = {
    // 一番外側の括弧を外す
    val inner = ts match {
      case Token.Sym("(") :: rest if rest.lastOption.contains(Token.Sym(")")) && balancedInside(rest.init) => rest.init
      case _ => ts
    }
    // 一番外側の AND で分ける（OR があれば諦める）
    val parts = scala.collection.mutable.ListBuffer(List.empty[Token])
    var depth = 0
    var ok = true
    inner.foreach {
      case t @ Token.Sym("(") => depth += 1; parts(parts.size - 1) = parts.last :+ t
      case t @ Token.Sym(")") => depth -= 1; parts(parts.size - 1) = parts.last :+ t
      case Token.Word(w) if depth == 0 && w.equalsIgnoreCase("and") => parts += Nil
      case Token.Word(w) if depth == 0 && (w.equalsIgnoreCase("or") || w.equalsIgnoreCase("not")) => ok = false
      case t => parts(parts.size - 1) = parts.last :+ t
    }
    if !ok then Map.empty
    else
      parts.toList.flatMap { p =>
        p match {
          case c :: Token.Sym("=") :: Token.Str(v) :: Nil => name(c).map(_ -> Set(v))
          case Token.Str(v) :: Token.Sym("=") :: c :: Nil => name(c).map(_ -> Set(v))
          case c :: Token.Word(in) :: Token.Sym("(") :: rest
              if in.equalsIgnoreCase("in") && rest.lastOption.contains(Token.Sym(")")) =>
            val vs = rest.init.filterNot(_ == Token.Sym(","))
            if vs.nonEmpty && vs.forall(_.isInstanceOf[Token.Str]) then
              name(c).map(_ -> vs.collect { case Token.Str(v) => v }.toSet)
            else None
          case (open @ Token.Sym("(")) :: rest if rest.lastOption.contains(Token.Sym(")")) =>
            fromTokens(open :: rest).toList
          case _ => None
        }
      }.groupMapReduce(_._1)(_._2)(_ intersect _)
  }

  private def balancedInside(ts: List[Token]): Boolean = {
    var d = 0
    ts.forall {
      case Token.Sym("(") => d += 1; true
      case Token.Sym(")") => d -= 1; d >= 0
      case _ => true
    } && d == 0
  }

  /** ファイルを読む必要があるか（tags にその列があり、どの値も含まなければ読まない）。 */
  def keep(tags: Map[String, List[String]], cs: Map[String, Set[String]]): Boolean =
    cs.forall((col, vs) => tags.get(col).forall(have => have.exists(vs.contains)))

  /** ファイルに記録する値の一覧（文字列の列ごとに、種類が max 以下で値が短いものだけ）。 */
  def tags(rows: Iterable[Row], max: Int = 32): Map[String, List[String]] = {
    val acc = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
    val dead = scala.collection.mutable.Set.empty[String]
    rows.foreach(_.fields.foreach {
      case (k, Value.Str(v)) if !dead.contains(k) =>
        if v.length > 200 then { dead += k; acc -= k }
        else {
          val s = acc.getOrElseUpdate(k, scala.collection.mutable.Set.empty)
          s += v
          if s.size > max then { dead += k; acc -= k }
        }
      case _ => ()
    })
    acc.view.mapValues(_.toList.sorted).toMap
  }

  /** 圧縮でまとめたファイルの値の一覧（すべてのファイルにあり、合わせても max 以下の列だけ）。 */
  def mergeTags(ts: List[Map[String, List[String]]], max: Int = 32): Map[String, List[String]] =
    if ts.isEmpty then Map.empty
    else
      ts.map(_.keySet).reduce(_ intersect _).toList.flatMap { k =>
        val vs = ts.flatMap(_(k)).distinct
        Option.when(vs.size <= max)(k -> vs.sorted)
      }.toMap
}
