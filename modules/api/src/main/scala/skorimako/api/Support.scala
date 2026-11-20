package skorimako.api

import cats.effect.*
import skorimako.core.*
import sbirds.shared.AppError

import scala.concurrent.duration.*

final case class ApiConfig(
  adminGroups: Set[String],
  cookieSecure: Boolean,
  publicUrl: String,
  sessionTtl: FiniteDuration = 14.days,
  cookieName: String = "skorimako_session",
  devLogin: Boolean = false,
  timezone: String = "Asia/Tokyo",
  retentionDays: Int = 30,
  flushSeconds: Int = 10,
  maxIngestBytes: Long = 32L * 1024 * 1024, // 送られてくる本文（圧縮したまま）の上限
  maxInflatedBytes: Long = 256L * 1024 * 1024, // 展開した後の上限
  maxIngestRows: Int = 100000, // JSON・RUM の1回の行数の上限
  webhookDenyPrivate: Boolean = false,
  version: String = "0.1.0",
  oauthClients: List[OAuthClient] = Nil,
  stui: Option[LinkService] = None,
  internalSecret: String = "", // ノード間の問い合わせの署名（APP_SECRET から）
  grpcPort: Int = 0,
  rateLimit: sbirds.http.RateLimitConfig = sbirds.http.RateLimitConfig())

/** 連携する stui（skorimako をクライアントとして登録しておく）。url は画面から開く URL、internalUrl はサーバーから呼ぶ URL。 */
final case class LinkService(provider: String,
                             url: String,
                             clientId: String,
                             clientSecret: String,
                             internalUrl: Option[String] = None) {
  def apiUrl: String = internalUrl.getOrElse(url)
}

/** 認可を求める外部のサービス（OAUTH_CLIENTS で登録する。stui がリンクの要約を読むため）。redirectUri は完全一致で照合する。 */
type OAuthClient = sbirds.oauth.OAuthClient
val OAuthClient = sbirds.oauth.OAuthClient

extension (s: AuthSession) {

  /** 検索の権限（管理者・グループ）と、検索の同時実行の上限の単位（ユーザーごと）。 */
  def viewer: skorimako.engine.Viewer =
    skorimako.engine.Viewer(s.isAdmin, s.groups.map(_.name).toSet, Some(s.user.id.toString))
}

type Res[A] = IO[Either[AppError, A]]
