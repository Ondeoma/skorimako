package skorimako.core

/** 列名・ストリーム名の規則。 */
object Names {

  /** 列名: 英小文字・数字・_ だけにする（service.name → service_name）。長さは100文字まで。空なら None。 */
  def column(
    raw: String
  ): Option[String] = {
    val b = new StringBuilder
    raw.foreach { c =>
      val l = c.toLower
      if (l >= 'a' && l <= 'z') || (l >= '0' && l <= '9') || l == '_' then b.append(l) else b.append('_')
    }
    val s = b.toString.take(100)
    Option.when(s.nonEmpty && s.exists(_ != '_'))(s)
  }

  /** ストリーム名: 英数字・_・-・. を 1〜100 文字（. は _ に揃える）。 */
  def stream(
    raw: String
  ): Either[String, String] = {
    val s = raw.trim.replace('.', '_')
    if s.isEmpty then Left("ストリームの名前を指定してください")
    else if s.length > 100 then Left("ストリームの名前は100文字までにしてください")
    else if !s.forall(c => c.isLetterOrDigit && c < 128 || c == '_' || c == '-') then Left("ストリームの名前には英数字・_・- だけが使えます")
    else Right(s.toLowerCase)
  }

  /** メトリクスの名前からストリーム名（http.server.duration → http_server_duration）。 */
  def metric(
    raw: String
  ): Option[String] = column(raw)
}
