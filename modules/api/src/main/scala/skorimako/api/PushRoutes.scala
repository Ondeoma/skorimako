package skorimako.api

import cats.effect.*
import io.circe.Decoder
import org.http4s.*
import skorimako.core.ErrorCode

import java.util.UUID

/**
 * プッシュ通知（PWA）の購読（sbirds.push.PushRoutes）. アラートの発生・解消を、ルールを作った人と、その通知先（stui の DM）の人に送る.
 * 端末の登録は画面のログイン（Cookie のセッション）だけ. publicKey は VAPID の公開鍵（送るのは skorimako の PushService）.
 */
final class PushRoutes(store: sbirds.push.PushStore, publicKey: String, val sessions: SessionAuth)
  extends RouteSupport { self =>

  private val host = new sbirds.push.PushRoutes.Host {
    def authed(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]] = self.authed(req)(s => f(s.user.id))
    def interactive(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]] =
      self.sessionOnly(req)(s => f(s.user.id))
    def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] = self.body[A](req)(f)
  }

  val routes: HttpRoutes[IO] = new sbirds.push.PushRoutes(
    store,
    publicKey,
    host,
    notAllowed = ErrorCode.PushServiceNotAllowed,
    invalid = ErrorCode.PushSubscriptionInvalid
  ).routes
}
