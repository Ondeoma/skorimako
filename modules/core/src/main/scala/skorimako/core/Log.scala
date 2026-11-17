package skorimako.core

import cats.effect.IO

/**
 * アプリのログ（logback. 標準出力に LOG_FORMAT=text・json で出る）.
 * skorimako は自分の観測データを自分に取り込む（SelfMonitor・SelfLog）ので、OTLP では送らない（送ると取り込みが増えて回り続ける）.
 * これまでの `IO.println("[skorimako] …")` は Log.println に置き換えてある（先頭の [サービス名] を外し、文言から水準を決める）.
 */
object Log:

  val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("skorimako")
  private val Prefix = "^\\[[A-Za-z0-9_-]+\\]\\s*".r
  private val Warn   = "^(?i)warning:\\s*".r

  def info(msg: => String): IO[Unit]                             = IO(logger.info(msg))
  def warn(msg: => String): IO[Unit]                             = IO(logger.warn(msg))
  def error(msg: => String, e: Throwable | Null = null): IO[Unit] = IO(logger.error(msg, e))

  /** 文言から水準を決める: WARNING で始まれば warn、failed・error・exception を含めば error、ほかは info. */
  def println(msg: Any): IO[Unit] = IO {
    val m   = Prefix.replaceFirstIn(String.valueOf(msg), "")
    val low = m.toLowerCase
    if Warn.findPrefixOf(m).isDefined then logger.warn(Warn.replaceFirstIn(m, ""))
    else if low.contains("failed") || low.contains("error") || low.contains("exception") then logger.error(m)
    else logger.info(m)
  }
