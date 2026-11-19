package skorimako.auth

/** skorimako の APIキー（sko_ で始まる）. */
object ApiKeys extends sbirds.auth.ApiKeys("sko_")

/** skorimako の秘密の値の暗号化（鍵の salt は skorimako 固有. 変えると保存済みの値を復号できなくなる）. */
object SecretBox {
  def apply(secret: String): sbirds.auth.SecretBox = new sbirds.auth.SecretBox(secret, "skorimako-secret-box:")
}

type SecretBox = sbirds.auth.SecretBox
