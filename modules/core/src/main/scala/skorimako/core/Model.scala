package skorimako.core

import java.util.UUID

/** UUID として読めれば Some（読めなければ None）。 */
def uuidOpt(
  s: String
): Option[UUID] = scala.util.Try(UUID.fromString(s.trim)).toOption

/** APIキーのスコープ。Ingest は観測データの送信（OTLP・JSON）だけに使う。 */
enum Scope {
  case Read, Write, Admin, Ingest
}

object Scope {
  def parse(
    s: String
  ): Option[Scope] = s.trim.toLowerCase match {
    case "read" => Some(Read)
    case "write" => Some(Write)
    case "admin" => Some(Admin)
    case "ingest" => Some(Ingest)
    case _ => None
  }
  extension (
    s: Scope
  ) {
    def asString: String = s match {
      case Read => "read"
      case Write => "write"
      case Admin => "admin"
      case Ingest => "ingest"
    }
  }
}

final case class User(
  id: UUID,
  sub: String,
  name: String,
  email: Option[String],
  avatarUrl: Option[String],
  isAdmin: Boolean,
  disabled: Boolean,
  timezone: String)
final case class Group(
  id: UUID,
  name: String)

/** 認証手段。 */
enum AuthVia {
  case Session

  /** APIキー。scopes はキーのスコープ、mcp は MCP 経由の呼び出し。 */
  case ApiKey(
    id: UUID,
    scopes: Set[Scope],
    mcp: Boolean = false)

  /** サーバーの中の処理（アラートの評価）。 */
  case System(
    kind: String)
}

/** 認証済みの利用者。isAdmin はシステム管理者（ADMIN_GROUPS の所属、または画面で管理者にしたユーザー）。 */
final case class AuthSession(
  user: User,
  groups: List[Group],
  isAdmin: Boolean,
  via: AuthVia = AuthVia.Session) {

  private def scopes: Option[Set[Scope]] = via match {
    case AuthVia.ApiKey(_, s, _) => Some(s)
    case _ => None
  }

  /** 検索・一覧ができるか（取り込み専用のキーは不可）。 */
  def canRead: Boolean =
    scopes.forall(s => s.contains(Scope.Read) || s.contains(Scope.Write) || s.contains(Scope.Admin))

  /** 更新系の操作ができるか（read スコープだけのキーは不可）。 */
  def canWrite: Boolean = scopes.forall(s => s.contains(Scope.Write) || s.contains(Scope.Admin))

  /** 観測データを送れるか（ingest か write のキー）。 */
  def canIngest: Boolean = scopes.forall(s => s.contains(Scope.Ingest) || s.contains(Scope.Write))

  /** システム管理の操作ができるか（APIキーは admin スコープが必要）。 */
  def canAdmin: Boolean = isAdmin && scopes.forall(_.contains(Scope.Admin))

  def viaString: String = via match {
    case AuthVia.Session => "web"
    case AuthVia.ApiKey(_, _, true) => "mcp"
    case _: AuthVia.ApiKey => "api"
    case AuthVia.System(k) => k
  }

  def isSession: Boolean = via == AuthVia.Session
}
