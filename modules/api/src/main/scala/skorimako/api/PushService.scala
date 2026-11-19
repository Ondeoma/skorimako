package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString
import sbirds.push.{PushMessage, PushStore, Subscription, VapidKeys, WebPush}

import java.util.UUID
import scala.concurrent.duration.*

// 購読の保存・暗号化・VAPID は sbirds-lib（sbirds.push）. 送る処理だけ skorimako に残す:
// lib の PushService は送った数を OpenTelemetry のメトリクスで数えるが、skorimako は自分に OTLP で送らない（自己監視の Stats で数える）.
/**
 * プッシュ通知を送る. VAPID の subject は `VAPID_SUBJECT`（`mailto:` か `https:`. 省略すると公開 URL）.
 * 送れなかった購読（404・410）は消す. 失敗しても呼び出し元の処理は止めない.
 * sent は送った結果（ok・gone・error）ごとに呼ぶ（自己監視の数. skorimako は自分に OTLP で送らないので、呼び出し元の Stats で数える）.
 */
final class PushService(store: PushStore,
                        http: Client[IO],
                        keys: VapidKeys,
                        subject: String,
                        sent: String => IO[Unit]) {

  def publicKey: String = keys.publicKey

  def send(userId: UUID, m: PushMessage): IO[Unit] =
    store.forUser(userId).flatMap(_.traverse_(s => deliver(s, m))).handleErrorWith(e =>
      sbirds.observe.Log.warn(s"push failed: $e")
    )

  private def deliver(s: Subscription, m: PushMessage): IO[Unit] =
    if !WebPush.allowedEndpoint(s.endpoint) then store.remove(s.endpoint)
    else {
      val payload = Json.obj(
        "title" -> Json.fromString(m.title.take(200)),
        "body" -> Json.fromString(m.body.take(1000)),
        "url" -> Json.fromString(m.url),
        "tag" -> m.tag.fold(Json.Null)(Json.fromString)
      ).noSpaces
      for {
        now <- IO.realTime.map(_.toSeconds)
        body <- IO(WebPush.encrypt(s, payload.getBytes("UTF-8")))
        req = Request[IO](Method.POST, Uri.unsafeFromString(s.endpoint))
          .withEntity(body)
          .putHeaders(
            Header.Raw(CIString("Authorization"), WebPush.vapidAuthorization(keys, s.endpoint, subject, now)),
            Header.Raw(CIString("Content-Encoding"), "aes128gcm"),
            Header.Raw(CIString("Content-Type"), "application/octet-stream"),
            Header.Raw(CIString("TTL"), "86400"),
            Header.Raw(CIString("Urgency"), "normal")
          )
        code <- http.run(req).use(r => IO.pure(r.status.code)).timeout(15.seconds).attempt
        _ <- code match {
          case Right(c) if c >= 200 && c < 300 => store.touch(s.endpoint) *> sent("ok")
          case Right(c) if c == 404 || c == 410 => store.remove(s.endpoint) *> sent("gone")
          case Right(c) => sbirds.observe.Log.warn(s"push rejected: HTTP $c") *> sent("error")
          case Left(e) => sbirds.observe.Log.warn(s"push failed: ${e.getClass.getSimpleName}") *> sent("error")
        }
      } yield ()
    }
}

object PushService {

  def create(store: PushStore, http: Client[IO], publicUrl: String, sent: String => IO[Unit]): IO[PushService] =
    store.vapidKeys.map { k =>
      val subject = sys.env.get("VAPID_SUBJECT").map(_.trim).filter(s =>
        s.startsWith("mailto:") || s.startsWith("https://")
      ).getOrElse(publicUrl)
      new PushService(store, http, k, subject, sent)
    }
}
