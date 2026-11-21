package skorimako.api

import munit.FunSuite
import skorimako.shared.*

import java.util.UUID

/** 通知先の入力の確認（種類ごとの url の意味・メールの宛先）。DB は使わない。 */
class DestinationSpec extends FunSuite {

  private def req(kind: String, url: String, target: Option[UUID] = None, secret: Option[String] = None) =
    DestinationReq(" 通知先 ", kind, url, secret, target)

  test("メールの宛先: 区切りを読み、重なりを除き、カンマ区切りにそろえる") {
    val r = AlertRoutes.destOk(req(DestinationKind.Email, "a@example.com;\n b@example.com  a@example.com"))
    assertEquals(r.map(_.url), Right("a@example.com, b@example.com"))
    assertEquals(r.map(_.name), Right("通知先"))
  }

  test("メールの宛先: 正しくないアドレス・空・多すぎるものは断る") {
    assert(AlertRoutes.destOk(req(DestinationKind.Email, "a@example.com, nope")).isLeft)
    assert(AlertRoutes.destOk(req(DestinationKind.Email, "Foo <a@example.com>")).isLeft)
    assert(AlertRoutes.destOk(req(DestinationKind.Email, "a@exa\u0000mple.com")).isLeft)
    assert(AlertRoutes.destOk(req(DestinationKind.Email, "  ")).isLeft)
    val many = (1 to DestinationKind.MaxEmails + 1).map(i => s"u$i@example.com").mkString(",")
    assert(AlertRoutes.destOk(req(DestinationKind.Email, many)).isLeft)
  }

  test("stui のチャンネル: チャンネルの ID が要る. 宛先の人は受け取らない（保存する人を持ち主にする）") {
    assert(AlertRoutes.destOk(req(DestinationKind.StuiChannel, "")).isLeft)
    val r = AlertRoutes.destOk(req(DestinationKind.StuiChannel, " c1 ", Some(UUID.randomUUID())))
    assertEquals(r.map(x => (x.url, x.targetUserId)), Right(("c1", None)))
  }

  test("stui の DM: 宛先の人が要り、url は使わない") {
    assert(AlertRoutes.destOk(req(DestinationKind.StuiDm, "")).isLeft)
    val u = UUID.randomUUID()
    assertEquals(
      AlertRoutes.destOk(req(DestinationKind.StuiDm, "x", Some(u))).map(x => (x.url, x.targetUserId)),
      Right(("", Some(u)))
    )
  }

  test("URL の種類: http(s) だけ. 署名の鍵は webhook だけ") {
    assert(AlertRoutes.destOk(req(DestinationKind.Webhook, "ftp://example.com")).isLeft)
    assertEquals(
      AlertRoutes.destOk(req(DestinationKind.Webhook, "https://example.com/h", secret = Some("k"))).map(_.secret),
      Right(Some("k"))
    )
    assertEquals(
      AlertRoutes.destOk(req(DestinationKind.Slack, "https://example.com/h", secret = Some("k"))).map(_.secret),
      Right(Some(""))
    )
    assert(AlertRoutes.destOk(req("unknown", "https://example.com")).isLeft)
  }
}
