package skorimako.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, Location}
import sbirds.auth.Crypto
import skorimako.auth.SecretBox
import skorimako.core.*
import skorimako.db.*
import skorimako.shared.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * stui（チャット）との連携のクライアント側: 各ユーザーが stui と OAuth で連携すると、アラートをその人の stui の DM や、
 * その人が参加しているチャンネルに送れる（stui の POST /api/apps/notify。stui のボット「skorimako」からの投稿になる）。
 * stui 側の OAUTH_CLIENTS に skorimako を登録する（redirectUri は {PUBLIC_URL}/api/integrations/stui/callback、scopes は read・write）。
 */
final class StuiClient(cfg: Option[LinkService],
                       tokens: sbirds.links.IntegrationTokenStore,
                       http: Client[IO],
                       box: SecretBox,
                       publicUrl: String) {

  val Provider = "stui"

  def configured: Boolean = cfg.isDefined

  private def redirectUri: String = s"${publicUrl.stripSuffix("/")}/api/integrations/stui/callback"

  def connected(userId: UUID): IO[Boolean] = tokens.token(userId, Provider).map(_.isDefined)

  private def token(userId: UUID): IO[Option[String]] = tokens.token(userId, Provider).map(_.flatMap(box.decrypt))

  /** その人の stui の DM に送る。 */
  def notify(userId: UUID, text: String): IO[OutboxResult] =
    post(userId, Json.obj("text" -> text.asJson), "宛先の人")

  /**
   * その人の連携で、その人が参加しているチャンネルに投稿する（通知先の stui_channel）。title は見出し（url があればリンク）、text は Markdown。
   */
  def notifyChannel(userId: UUID, channel: String, title: String, text: String, url: String): IO[OutboxResult] =
    post(
      userId,
      Json.obj(
        "title" -> title.take(300).asJson,
        "text" -> text.take(2000).asJson,
        "url" -> url.asJson,
        "channel" -> channel.asJson
      ),
      "通知先を作った人"
    )

  /** who は連携の持ち主（エラーの文言に使う）。 */
  private def post(userId: UUID, body: Json, who: String): IO[OutboxResult] =
    cfg match {
      case None => IO.pure(OutboxResult.Skip("stui との連携が設定されていません（STUI_URL など）"))
      case Some(c) =>
        token(userId).flatMap {
          case None => IO.pure(OutboxResult.Skip(s"${who}が stui と連携していません"))
          case Some(t) =>
            val req = Request[IO](Method.POST, Uri.unsafeFromString(c.apiUrl + "/api/apps/notify"))
              .withEntity(body)
              .putHeaders(bearer(t), "User-Agent" -> "skorimako")
            http.run(req).use(r => IO.pure(r.status.code)).timeout(15.seconds).attempt.flatMap {
              case Right(code) if code >= 200 && code < 300 => IO.pure(OutboxResult.Sent)
              // トークンが失効している: 連携を外し、送るのをやめる（その人が連携し直す）
              case Right(401) =>
                tokens.deleteToken(userId, Provider).as(OutboxResult.Skip(s"${who}の stui との連携が切れています（連携し直してください）"))
              case Right(403) =>
                IO.pure(OutboxResult.Skip("このチャンネルには投稿できません（チャンネルを抜けたか、アーカイブされたかもしれません）"))
              case Right(404) => IO.pure(OutboxResult.Skip("チャンネルが見つかりません"))
              case Right(code) => IO.pure(OutboxResult.Retry(s"HTTP $code"))
              case Left(_) => IO.pure(OutboxResult.Retry("stui に接続できませんでした"))
            }
        }
    }

  /** 投稿先に選べるチャンネル（その人が参加している公開・非公開のもの。アーカイブしたものは除く。名前の順）。 */
  def channels(userId: UUID): IO[Either[ErrorCode, List[StuiChannelDto]]] =
    cfg match {
      case None => IO.pure(Left(ErrorCode.StuiNotConfigured))
      case Some(c) =>
        token(userId).flatMap {
          case None => IO.pure(Left(ErrorCode.StuiNotConnected))
          case Some(t) =>
            val req = Request[IO](Method.GET, Uri.unsafeFromString(c.apiUrl + "/api/channels"))
              .putHeaders(bearer(t), "User-Agent" -> "skorimako")
            http.run(req).use { r =>
              if r.status == Status.Unauthorized then
                tokens.deleteToken(userId, Provider).as(Left(ErrorCode.StuiConnectionExpired))
              else if !r.status.isSuccess then IO.pure(Left(ErrorCode.StuiChannelsUnreadable))
              else
                r.as[Json].map(j =>
                  Right(j.asArray.getOrElse(Vector.empty).toList.flatMap { x =>
                    val h = x.hcursor
                    for {
                      id <- h.get[String]("id").toOption
                      name <- h.get[String]("name").toOption
                      kind <- h.get[String]("kind").toOption if kind == "public" || kind == "private"
                      if !h.get[Boolean]("archived").getOrElse(false) && h.get[Boolean]("member").getOrElse(true)
                    } yield StuiChannelDto(id, name, kind, h.get[String]("role").toOption.contains("admin"))
                  }.sortBy(_.name.toLowerCase))
                )
            }.timeout(10.seconds).handleError(_ => Left(ErrorCode.StuiUnreachable))
        }
    }

  private def bearer(t: String): Authorization = Authorization(Credentials.Token(AuthScheme.Bearer, t))

  private def basic(c: LinkService): Authorization = Authorization(BasicCredentials(c.clientId, c.clientSecret))

  private def found(to: String): IO[Response[IO]] =
    IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to))))

  def routes(auth: SessionAuth, users: UserStore): HttpRoutes[IO] = {
    object R extends RouteSupport {

      val r: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ GET -> Root / "api" / "integrations" => getIntegrations(req)
        case req @ GET -> Root / "api" / "integrations" / "stui" / "connect" => getIntegrationsStuiConnect(req)
        case req @ GET -> Root / "api" / "integrations" / "stui" / "channels" => getIntegrationsStuiChannels(req)
        case req @ GET -> Root / "api" / "integrations" / "stui" / "callback" => getIntegrationsStuiCallback(req)
        case req @ DELETE -> Root / "api" / "integrations" / "stui" / "connection" =>
          deleteIntegrationsStuiConnection(req)
      }

      private def getIntegrations(req: Request[IO]): IO[Response[IO]] =
        authed(req)(s =>
          cfg match {
            case None => Ok(List.empty[IntegrationDto])
            case Some(c) =>
              // 名前は sruru に登録した表示名（なければサービスの名前）
              (connected(s.user.id), auth.appNames(req)).flatMapN((on, names) =>
                Ok(List(IntegrationDto(Provider, names.getOrElse(Provider, Provider), c.url, on)))
              )
          }
        )

      /** 本人が投稿先に選べる stui のチャンネル（通知先の stui_channel を作るとき）。 */
      private def getIntegrationsStuiChannels(req: Request[IO]): IO[Response[IO]] =
        sessionOnly(req)(s => channels(s.user.id).flatMap(_.fold(err(_), Ok(_))))

      private def getIntegrationsStuiConnect(req: Request[IO]): IO[Response[IO]] =
        sessionOnly(req)(s =>
          cfg match {
            case None => err(ErrorCode.StuiNotConfigured)
            case Some(c) =>
              val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48)
              val back = sbirds.shared.SafePath.orElse(param(req, "returnTo"), "/settings")
              tokens.saveState(state, s.user.id, Provider, verifier, back, Instant.now().plusSeconds(600)) *>
                found(Uri.unsafeFromString(c.url + "/oauth/authorize").withQueryParams(Map(
                  "response_type" -> "code",
                  "client_id" -> c.clientId,
                  "redirect_uri" -> redirectUri,
                  "scope" -> "read write",
                  "state" -> state,
                  "code_challenge" -> Crypto.pkceChallenge(verifier),
                  "code_challenge_method" -> "S256"
                )).renderString)
          }
        )

      private def getIntegrationsStuiCallback(req: Request[IO]): IO[Response[IO]] =
        (cfg, param(req, "state")) match {
          case (Some(c), Some(state)) =>
            tokens.takeState(state, Provider).flatMap {
              case None => found("/settings?integration=stui&result=expired")
              case Some((userId, verifier, back)) =>
                // 連携を始めた本人のブラウザーに戻ってきたときだけ受け取る
                // （他人に state を踏ませて、その人の stui のトークンを自分に結び付けるのを防ぐ）
                auth.fromRequest(req).map(_.exists(s => s.isSession && s.user.id == userId)).flatMap {
                  case false => found("/settings?integration=stui&result=expired")
                  case true => exchange(c, param(req, "code"), userId, verifier, back)
                }
            }
          case _ => err(ErrorCode.StuiStateMissing)
        }

      private def exchange(c: LinkService,
                           code: Option[String],
                           userId: UUID,
                           verifier: String,
                           back: String): IO[Response[IO]] =
        code match {
          case None => found(s"$back?integration=stui&result=denied")
          case Some(code) =>
            val form = UrlForm(
              "grant_type" -> "authorization_code",
              "code" -> code,
              "redirect_uri" -> redirectUri,
              "code_verifier" -> verifier
            )
            val tr = Request[IO](
              Method.POST,
              Uri.unsafeFromString(c.apiUrl + "/oauth/token")
            ).withEntity(form).putHeaders(basic(c))
            http.expect[Json](tr).timeout(15.seconds).attempt.flatMap {
              case Right(j) =>
                j.hcursor.get[String]("access_token").toOption match {
                  case Some(t) =>
                    tokens.saveToken(userId, Provider, box.encrypt(t)) *>
                      users.audit(Some(userId), "web", "integration.connect", Some(Provider), Json.obj()) *>
                      found(s"$back?integration=stui&result=connected")
                  case None => found(s"$back?integration=stui&result=error")
                }
              case Left(_) => found(s"$back?integration=stui&result=error")
            }
        }

      private def deleteIntegrationsStuiConnection(req: Request[IO]): IO[Response[IO]] =
        sessionOnly(req)(s =>
          cfg match {
            case None => NoContent()
            case Some(c) =>
              token(s.user.id).flatMap(_.traverse_ { t =>
                http.run(Request[IO](
                  Method.POST,
                  Uri.unsafeFromString(c.apiUrl + "/oauth/revoke")
                ).withEntity(UrlForm("token" -> t)).putHeaders(basic(c)))
                  .use(_ => IO.unit).timeout(10.seconds).handleError(_ => ())
              }) *> tokens.deleteToken(s.user.id, Provider) *>
                users.audit(Some(s.user.id), "web", "integration.disconnect", Some(Provider), Json.obj()) *>
                NoContent()
          }
        )

      protected def sessions: SessionAuth = auth
    }
    R.r
  }
}
