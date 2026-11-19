package skorimako.core

import skorimako.shared.*
import SqlText.{ident, lit}

/** 画面の条件から SQL（DuckDB）を組み立てる。ストリームは同じ名前のビュー（時間の範囲で絞ったもの）として参照する。 */
object QueryBuilder {

  private val ts = ident(Columns.Timestamp)

  /** 区切りの長さ（マイクロ秒）。範囲をおよそ target 個に分ける、きりのよい長さ。 */
  def interval(start: Long, end: Long, target: Int = 60): Long = {
    val steps = List(
      1L, 5L, 10L, 15L, 30L, 60L, 120L, 300L, 600L, 900L, 1800L, 3600L, 7200L, 10800L, 21600L, 43200L, 86400L, 604800L
    )
      .map(_ * 1000000L)
    val want = math.max(1L, (end - start) / math.max(1, target))
    steps.find(_ >= want).getOrElse(steps.last)
  }

  /** 条件（利用者の WHERE と、キーワードの部分一致）。textColumns はキーワードを探す文字列の列。 */
  def conditions(where: Option[String],
                 keyword: Option[String],
                 textColumns: List[String]): Either[String, List[String]] =
    for w <- where.map(_.trim).filter(_.nonEmpty) match {
        case None => Right(None)
        case Some(x) => SqlText.checkCondition(x).map(Some(_))
      }
    yield {
      val kw = keyword.map(_.trim).filter(_.nonEmpty).map { k =>
        val cols = if textColumns.isEmpty then List(Columns.Timestamp) else textColumns.take(60)
        val l = lit(k.toLowerCase)
        cols.map(c => s"contains(lower(CAST(${ident(c)} AS VARCHAR)), $l)").mkString("(", " OR ", ")")
      }
      w.map(x => s"($x)").toList ++ kw.toList
    }

  private def whereClause(cs: List[String]): String = if cs.isEmpty then "" else cs.mkString(" WHERE ", " AND ", "")

  /** ログの検索: 一覧・件数・時間ごとの件数の SQL。 */
  final case class LogSql(list: String, count: String, histogram: String)

  def logSearch(stream: String, conds: List[String], order: String, limit: Int, offset: Int, bucket: Long): LogSql = {
    val from = ident(stream)
    val w = whereClause(conds)
    val dir = if order.equalsIgnoreCase("asc") then "ASC" else "DESC"
    LogSql(
      s"SELECT * FROM $from$w ORDER BY $ts $dir LIMIT $limit OFFSET $offset",
      s"SELECT count(*) AS c FROM $from$w",
      s"SELECT ($ts // $bucket) * $bucket AS t, count(*) AS c FROM $from$w GROUP BY 1 ORDER BY 1"
    )
  }

  /** 集計の式（value の列について）。 */
  private def aggExpr(agg: String, col: String): Either[String, String] = {
    val c = ident(col)
    agg match {
      case "avg" => Right(s"avg($c)")
      case "sum" => Right(s"sum($c)")
      case "min" => Right(s"min($c)")
      case "max" => Right(s"max($c)")
      case "count" => Right("count(*)")
      case "last" => Right(s"arg_max($c, $ts)")
      case p if p.matches("p\\d{2}") => Right(s"quantile_cont($c, ${p.drop(1).toInt / 100.0})")
      case _ => Left(s"集計の方法 $agg は使えません")
    }
  }

  /**
   * メトリクスの時系列。labels は value・__name__ などを除いたラベルの列（rate で系列を分けるため）。
   * 結果の列: t（区切りの始まり）、groupBy の列、v。
   */
  def metric(stream: String,
             agg: String,
             groupBy: List[String],
             conds: List[String],
             step: Long,
             labels: List[String]): Either[String, String] = {
    val from = ident(stream)
    val w = whereClause(conds)
    val gs = groupBy.map(ident)
    val gsel = gs.map(_ + ", ").mkString
    val bucket = s"($ts // $step) * $step"
    if agg == "rate" then {
      val part = if labels.isEmpty then "" else labels.map(ident).mkString("PARTITION BY ", ", ", " ")
      val win = s"OVER (${part}ORDER BY $ts)"
      Right(
        s"""WITH d AS (SELECT *, "value" - lag("value") $win AS dv FROM $from$w)
           |SELECT $bucket AS t, $gsel sum(CASE WHEN dv >= 0 THEN dv ELSE "value" END) / ${step / 1e6} AS v
           |FROM d WHERE dv IS NOT NULL GROUP BY ALL ORDER BY t""".stripMargin
      )
    } else aggExpr(agg, "value").map(e => s"SELECT $bucket AS t, $gsel$e AS v FROM $from$w GROUP BY ALL ORDER BY t")
  }

  /** アラート（閾値）の SQL。結果の列: grp（groupBy があるとき）、value。 */
  def alert(stream: String,
            agg: String,
            field: Option[String],
            groupBy: Option[String],
            conds: List[String]): Either[String, String] = {
    val col = field.map(_.trim).filter(_.nonEmpty)
    for {
      e <- if agg == "count" then Right("count(*)")
      else col.toRight("集計する列を指定してください").flatMap(aggExpr(agg, _))
    } yield {
      val from = ident(stream)
      val w = whereClause(conds)
      groupBy.map(_.trim).filter(_.nonEmpty) match {
        case Some(g) =>
          s"SELECT CAST(${ident(g)} AS VARCHAR) AS grp, CAST($e AS DOUBLE) AS value FROM $from$w GROUP BY 1"
        case None => s"SELECT CAST($e AS DOUBLE) AS value FROM $from$w"
      }
    }
  }

  /** 途絶のアラートの SQL。結果の列: grp（groupBy があるとき）、last（最後の行の時刻）。 */
  def absence(stream: String, groupBy: Option[String], conds: List[String]): String = {
    val from = ident(stream)
    val w = whereClause(conds)
    groupBy.map(_.trim).filter(_.nonEmpty) match {
      case Some(g) => s"SELECT CAST(${ident(g)} AS VARCHAR) AS grp, max($ts) AS last FROM $from$w GROUP BY 1"
      case None => s"SELECT max($ts) AS last FROM $from$w"
    }
  }

  /** ダッシュボードの SQL の $__interval を区切りの長さ（マイクロ秒）に置き換える。 */
  def expand(sql: String, start: Long, end: Long): String =
    sql.replace("$__interval", interval(start, end, 120).toString)

  /**
   * かんたん作成のパネルの SQL。区切りは $__interval のまま残す（表示する期間に合わせて置き換わる）。
   * labels はメトリクスのラベルの列（rate で系列を分けるため）、numericCols は数値の列（条件の値を数として書くため）。
   * 折れ線・棒: t（区切りの始まり）、分ける列、v。数値: v。表: 分ける列、v（大きい順）。
   */
  def panel(spec: PanelSpec,
            kind: String,
            labels: List[String],
            numericCols: Set[String]): Either[String, (String, String)] = {
    val stream = spec.stream.trim
    if stream.isEmpty then Left("ストリーム（メトリクス）を選んでください")
    else {
      val from = ident(stream)
      val iv = "$__interval"
      val conds = spec.filters.filter(_.field.trim.nonEmpty).map(f => filterSql(f, numericCols)) ++
        spec.service.map(_.trim).filter(_.nonEmpty).map(v => s"${ident("service_name")} = ${lit(v)}").toList
      val group = spec.groupBy.map(_.trim).filter(_.nonEmpty)
      val topN = spec.topN.filter(_ > 0).getOrElse(10).min(50)
      val series = kind == PanelKind.Line || kind == PanelKind.Bar
      // トレースの所要時間（マイクロ秒）は ms で見せる
      val isDuration = spec.streamType == StreamType.Traces && spec.field.contains("duration")
      val unit = if isDuration then "ms" else if spec.measure == "rate" then "/s" else ""
      val col =
        if spec.streamType == StreamType.Metrics then Some("value") else spec.field.map(_.trim).filter(_.nonEmpty)
      val gsel = group.map(g => s"CAST(${ident(g)} AS VARCHAR) AS ${ident(g)}, ").getOrElse("")
      // 分ける値が多いときは、件数の多い上位だけ
      val top = group.filter(_ => series).map(g =>
        s"${ident(g)} IN (SELECT ${ident(g)} FROM $from${whereClause(conds)} GROUP BY 1 ORDER BY count(*) DESC LIMIT $topN)"
      )
      val w = whereClause(conds ++ top.toList)
      if spec.measure == "rate" then {
        val part = if labels.isEmpty then "" else labels.map(ident).mkString("PARTITION BY ", ", ", " ")
        val bucket = if series then s"($ts // $iv) * $iv AS t, " else ""
        val per = if series then s"($iv / 1e6)" else "greatest(1, (max(_timestamp) - min(_timestamp)) / 1e6)"
        val tail = if series then "GROUP BY ALL ORDER BY t"
        else if group.nonEmpty then s"GROUP BY ALL ORDER BY v DESC LIMIT $topN" else ""
        Right((
          s"""WITH d AS (SELECT *, "value" - lag("value") OVER (${part}ORDER BY $ts) AS dv FROM $from$w)
             |SELECT $bucket$gsel sum(CASE WHEN dv >= 0 THEN dv ELSE "value" END) / $per AS v
             |FROM d WHERE dv IS NOT NULL $tail""".stripMargin.trim,
          unit
        ))
      } else
        (if spec.measure == "count" then Right("count(*)")
         else col.toRight("集計する列を選んでください").flatMap(aggExpr(spec.measure, _))).map { e0 =>
          val e = if isDuration && spec.measure != "count" then s"$e0 / 1000.0" else e0
          val sql =
            if series then s"SELECT ($ts // $iv) * $iv AS t, $gsel$e AS v FROM $from$w GROUP BY ALL ORDER BY t"
            else if group.nonEmpty then s"SELECT $gsel$e AS v FROM $from$w GROUP BY ALL ORDER BY v DESC LIMIT $topN"
            else s"SELECT $e AS v FROM $from$w"
          (sql, unit)
        }
    }
  }

  private def filterSql(f: PanelFilter, numericCols: Set[String]): String = {
    val c = ident(f.field.trim)
    val v = f.value.trim
    val num = numericCols.contains(f.field.trim) && v.toDoubleOption.isDefined
    val value = if num then v else lit(v)
    f.op match {
      case "!=" => s"$c IS DISTINCT FROM $value"
      case "contains" => s"contains(lower(CAST($c AS VARCHAR)), ${lit(v.toLowerCase)})"
      case op @ (">" | ">=" | "<" | "<=") => s"$c $op $value"
      case "exists" => s"$c IS NOT NULL"
      case _ => s"$c = $value"
    }
  }
}
