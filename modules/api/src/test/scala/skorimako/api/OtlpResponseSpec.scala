package skorimako.api

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.headers.`Content-Length`

/** OTLP/HTTP の応答の本文（Collector は protobuf の応答を ExportXxxServiceResponse として読む）. */
class OtlpResponseSpec extends CatsEffectSuite:

  test("protobuf の応答は本文が 0 バイト（空のメッセージ）") {
    for
      r <- IngestRoutes.otlpOk(json = false)
      body <- r.body.compile.to(Array)
    yield
      assertEquals(body.length, 0)
      assertEquals(r.contentType.map(m => s"${m.mediaType.mainType}/${m.mediaType.subType}"), Some("application/x-protobuf"))
      assert(r.headers.get[`Content-Length`].forall(_.length == 0))
  }

  test("JSON の応答は {}") {
    IngestRoutes.otlpOk(json = true).flatMap(_.bodyText.compile.string).assertEquals("{}")
  }
