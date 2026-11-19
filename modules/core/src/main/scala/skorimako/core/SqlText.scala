package skorimako.core

/** SQL の文字列の扱い（引用・字句の分解・利用者が書いた SQL の検査）。 */
object SqlText {

  def ident(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

  def lit(s: String): String = "'" + s.replace("'", "''") + "'"

  enum Token {
    case Word(
      text: String
    ) // 予約語・関数名・引用していない名前
    case Quoted(
      text: String
    ) // "名前"
    case Str(
      text: String
    ) // '文字列'
    case Num(
      text: String)
    case Sym(
      text: String
    ) // 記号（( ) , ; など）
  }

  /** 字句に分ける（コメントは捨てる）。閉じていない引用・コメントは Left。 */
  def tokenize(sql: String): Either[String, List[Token]] = {
    val out = List.newBuilder[Token]
    var i = 0
    val n = sql.length
    var err: Option[String] = None
    def quoted(q: Char): Option[String] = {
      val b = new StringBuilder
      var j = i + 1
      var done = false
      while j < n && !done do
        if sql(j) == q then
          if j + 1 < n && sql(j + 1) == q then { b.append(q); j += 2 }
          else { done = true; j += 1 }
        else { b.append(sql(j)); j += 1 }
      i = j
      Option.when(done)(b.toString)
    }
    while i < n && err.isEmpty do {
      val c = sql(i)
      if c.isWhitespace then i += 1
      else if c == '-' && i + 1 < n && sql(i + 1) == '-' then
        while i < n && sql(i) != '\n' do i += 1
      else if c == '/' && i + 1 < n && sql(i + 1) == '*' then {
        val e = sql.indexOf("*/", i + 2)
        if e < 0 then err = Some("コメント（/* */）が閉じていません") else i = e + 2
      } else if c == '\'' then
        quoted('\'') match {
          case Some(s) => out += Token.Str(s)
          case None => err = Some("文字列（'…'）が閉じていません")
        }
      else if c == '"' then
        quoted('"') match {
          case Some(s) => out += Token.Quoted(s)
          case None => err = Some("名前（\"…\"）が閉じていません")
        }
      else if c.isLetter || c == '_' then {
        val s = i
        while i < n && (sql(i).isLetterOrDigit || sql(i) == '_' || sql(i) == '$') do i += 1
        out += Token.Word(sql.substring(s, i))
      } else if c.isDigit then {
        val s = i
        while i < n && (sql(i).isLetterOrDigit || sql(i) == '.') do i += 1
        out += Token.Num(sql.substring(s, i))
      } else {
        out += Token.Sym(c.toString)
        i += 1
      }
    }
    err.toLeft(out.result())
  }

  /** 括弧の対応と、文の区切り（;）がないことを確かめる。 */
  private def balanced(ts: List[Token]): Either[String, Unit] = {
    var depth = 0
    var bad: Option[String] = None
    ts.foreach {
      case Token.Sym("(") => depth += 1
      case Token.Sym(")") =>
        depth -= 1
        if depth < 0 && bad.isEmpty then bad = Some("括弧の対応が正しくありません")
      case Token.Sym(";") => if bad.isEmpty then bad = Some("SQL は1つの文だけにしてください（; は使えません）")
      case _ => ()
    }
    bad.orElse(Option.when(depth != 0)("括弧の対応が正しくありません")).toLeft(())
  }

  /** 検索の SQL（SELECT・WITH・FROM で始まる1つの文）。末尾の ; は取り除く。参照している名前（引用の有無を問わない）を返す。 */
  def checkQuery(sql: String): Either[String, (String, Set[String])] = {
    val s = sql.trim.stripSuffix(";").trim
    for {
      _ <- Either.cond(s.nonEmpty, (), "SQL を入力してください")
      _ <- Either.cond(s.length <= 100000, (), "SQL が長すぎます")
      ts <- tokenize(s)
      _ <- balanced(ts)
      _ <- ts.headOption match {
        case Some(Token.Word(w)) if Set("select", "with", "from").contains(w.toLowerCase) => Right(())
        case _ => Left("SELECT（または WITH・FROM）で始まる検索の SQL だけが使えます")
      }
    } yield (s, names(ts))
  }

  /** 条件の式（WHERE に入れる）。; と、外側の括弧を閉じることを禁じる。 */
  def checkCondition(expr: String): Either[String, String] = {
    val s = expr.trim
    for {
      _ <- Either.cond(s.length <= 20000, (), "条件が長すぎます")
      ts <- tokenize(s)
      _ <- balanced(ts)
    } yield s
  }

  def names(ts: List[Token]): Set[String] = ts.collect {
    case Token.Word(w) => w.toLowerCase
    case Token.Quoted(q) => q
  }.toSet
}
