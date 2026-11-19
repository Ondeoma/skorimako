package skorimako.engine

import cats.effect.*
import cats.syntax.all.*
import skorimako.core.*
import skorimako.db.StreamStore
import skorimako.shared.*
import sbirds.shared.AppError

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.util.matching.Regex

/**
 * 取り込みの変換と上限。受けた行をバッファに入れる前に、ストリームごとのルールを順に当てる。
 *  - mask: 値の一部を伏せる（正規表現、または既定の型 email・card・phone・ipv4・token）
 *  - drop_field: 列を落とす（カンマ区切り、末尾の * は前方一致）
 *  - drop_rows: 列の値が正規表現に合う行を捨てる
 *  - sample: 割合で残す（trace_id があればトレースごとにそろえる）
 *  - rename: 列の名前を変える
 * 取り込みの上限（1分あたりの行。ノードごと）を超えたら 429 で断る。ルールと上限は30秒ごとに読み直す。
 */
final class Transform private (
  store: StreamStore,
  rules: Ref[IO, Map[(String, String), List[Transform.Compiled]]],
  limits: Ref[IO, Map[(String, String), Int]]) {

  private val counters = new ConcurrentHashMap[(String, String), (Long, AtomicLong)]()

  def reload: IO[Unit] =
    for {
      rs <- store.ingestRules
      ls <- store.limits
      compiled = rs.filter(_.enabled).flatMap(r => Transform.compile(r).map((r.streamType, r.stream) -> _))
      _ <- rules.set(compiled.groupMap(_._1)(_._2))
      _ <- limits.set(ls.map(
        (
          t,
          n,
          m
        ) => (t, n) -> m
      ).toMap)
    } yield ()

  def loop: IO[Nothing] =
    (IO.sleep(Acl.refresh) *>
      reload.handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.IngestRulesReloadFailed, e.toString, e))).foreverM

  /** ルールを当て、上限を確かめる。 */
  def apply(
    streamType: String,
    batch: Batch
  ): IO[Either[AppError, Batch]] =
    (rules.get, limits.get).tupled.map {
      (
        rs,
        ls
      ) =>
        val out = batch.rows.map {
          (
            stream,
            rows
          ) =>
            val applicable = rs.getOrElse((streamType, "*"), Nil) ++ rs.getOrElse((streamType, stream), Nil)
            stream ->
              (if applicable.isEmpty then rows
               else
                 rows.flatMap(r =>
                   applicable.foldLeft(Option(r))(
                     (
                       acc,
                       c
                     ) => acc.flatMap(c.run)
                   )
                 ))
        }
        val over = out.toList.collectFirst(Function.unlift {
          (
            stream,
            rows
          ) =>
            ls.get((streamType, stream)).filter(max => !take(streamType, stream, rows.size, max)).map(max =>
              AppError(ErrorCode.IngestRateLimited, s"ストリーム $stream の取り込みの上限（1分あたり $max 行）を超えました")
            )
        })
        over.toLeft(batch.copy(rows = out.filter(_._2.nonEmpty)))
    }

  /** 1分の枠で数える。上限を超えるなら false（数えない）。 */
  private def take(
    streamType: String,
    stream: String,
    n: Int,
    max: Int
  ): Boolean = {
    val minute = System.currentTimeMillis() / 60000
    val k = (streamType, stream)
    val (m, c) = counters.compute(
      k,
      (
        _,
        old
      ) => if old == null || old._1 != minute then (minute, new AtomicLong(0)) else old
    )
    if c.get() + n > max then false else { c.addAndGet(n); true }
  }
}

object Transform {

  /** 当てる処理（None は行を捨てる）。 */
  final case class Compiled(
    run: Row => Option[Row])

  private val presets: Map[String, (Regex, String)] = Map(
    "email" -> ("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}".r, "***@***"),
    "card" -> ("\\b\\d(?:[ -]?\\d){12,18}\\b".r, "****-****-****-****"),
    "phone" ->
      ("\\b0\\d{1,4}-?\\d{1,4}-?\\d{3,4}\\b|\\+\\d{1,3}[ -]?\\d{1,4}[ -]?\\d{2,4}[ -]?\\d{3,4}".r, "***-****-****"),
    "ipv4" -> ("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b".r, "*.*.*.*"),
    "token" ->
      (
        "(?i)((?:password|passwd|pwd|secret|token|api[_-]?key|authorization)[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;&]+)".r,
        "$1***"
      )
  )

  private def fieldMatch(
    spec: String
  ): String => Boolean = {
    val parts = spec.split(',').map(_.trim).filter(_.nonEmpty).toList
    if parts.contains("*") then _ => true
    else name => parts.exists(p => if p.endsWith("*") then name.startsWith(p.dropRight(1)) else name == p)
  }

  /** ルールを実行できる形にする（正規表現が壊れていれば None）。 */
  def compile(
    r: IngestRuleDto
  ): Option[Compiled] =
    scala.util.Try {
      r.kind match {
        case IngestRuleKind.Mask =>
          val (re, repl) =
            presets.getOrElse(r.pattern.trim, (r.pattern.r, if r.replacement.isEmpty then "***" else r.replacement))
          val replacement = if presets.contains(r.pattern.trim) && r.replacement.nonEmpty then r.replacement else repl
          val f = fieldMatch(if r.field.trim.isEmpty then "*" else r.field)
          Compiled(row =>
            Some(row.copy(fields = row.fields.map {
              case (k, Value.Str(v)) if f(k) => k -> Value.Str(re.replaceAllIn(v, replacement))
              case kv => kv
            }))
          )
        case IngestRuleKind.DropField =>
          val f = fieldMatch(r.field)
          Compiled(row =>
            Some(row.copy(fields =
              row.fields.filterNot(
                (
                  k,
                  _
                ) => f(k)
              )
            ))
          )
        case IngestRuleKind.DropRows =>
          val re = r.pattern.r
          Compiled(row =>
            if row.fields.get(r.field.trim).exists(v => re.findFirstIn(v.asText).isDefined) then None else Some(row)
          )
        case IngestRuleKind.Sample =>
          val ratio = math.max(0.0, math.min(1.0, r.ratio))
          Compiled(row => {
            val h = row.str(
              "trace_id"
            ).map(t => (t.hashCode & 0x7fffffff) % 10000 / 10000.0).getOrElse(scala.util.Random.nextDouble())
            Option.when(h < ratio)(row)
          })
        case IngestRuleKind.Rename =>
          val to = Names.column(r.replacement).getOrElse(r.replacement)
          Compiled(row =>
            Some(row.fields.get(r.field.trim).fold(row)(v => row.copy(fields = row.fields - r.field.trim + (to -> v))))
          )
        case _ => throw new IllegalArgumentException(r.kind)
      }
    }.toOption

  /** ルールの検査（API で作る・変えるとき）。 */
  def validate(
    r: IngestRuleReq
  ): Either[String, IngestRuleReq] =
    for {
      _ <- Either.cond(StreamType.all.contains(r.streamType), (), "データの種類が正しくありません")
      _ <- Either.cond(IngestRuleKind.all.contains(r.kind), (), "変換の種類が正しくありません")
      _ <- Either.cond(r.stream == "*" || Names.stream(r.stream).isRight, (), "ストリームの名前が正しくありません（* はすべて）")
      _ <- Either.cond(
        r.kind == IngestRuleKind.Sample || r.kind == IngestRuleKind.Mask || r.field.trim.nonEmpty,
        (),
        "列を指定してください"
      )
      _ <- Either.cond(r.kind != IngestRuleKind.Rename || Names.column(r.replacement).isDefined, (), "新しい列の名前を指定してください")
      _ <- Either.cond(r.kind != IngestRuleKind.Sample || (r.ratio >= 0 && r.ratio <= 1), (), "残す割合は 0〜1 にしてください")
      needsRe = (r.kind == IngestRuleKind.Mask && !presets.contains(r.pattern.trim)) ||
        r.kind == IngestRuleKind.DropRows
      _ <- Either.cond(!needsRe || r.pattern.nonEmpty, (), "正規表現（または既定の型）を指定してください")
      _ <- if needsRe then scala.util.Try(r.pattern.r).toEither.left.map(_ => "正規表現が正しくありません").void else Right(())
    } yield r.copy(stream = if r.stream == "*" then "*" else Names.stream(r.stream).getOrElse(r.stream))

  def create(
    store: StreamStore
  ): IO[Transform] =
    for {
      r <- Ref.of[IO, Map[(String, String), List[Compiled]]](Map.empty)
      l <- Ref.of[IO, Map[(String, String), Int]](Map.empty)
      t = new Transform(store, r, l)
      _ <- t.reload
    } yield t
}
