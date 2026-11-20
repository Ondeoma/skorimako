package skorimako.api

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import sbirds.auth.Crypto
import sbirds.db.{Database, DbConfig}
import skorimako.auth.ApiKeys
import skorimako.core.*
import skorimako.db.*

import java.util.UUID

/** MCP の上限・内部呼び出しの印・エラーの文言。TEST_DATABASE_URL がなければ飛ばす。 */
class McpSpec extends CatsEffectSuite {

  private val db = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(
      url,
      sys.env.getOrElse("TEST_DATABASE_USER", "skorimako"),
      sys.env.getOrElse("TEST_DATABASE_PASSWORD", "skorimako")
    )
  )

  private def call(id: Int, path: String): Json = Json.obj(
    "jsonrpc" -> "2.0".asJson,
    "id" -> id.asJson,
    "method" -> "tools/call".asJson,
    "params" -> Json.obj(
      "name" -> "call_api".asJson,
      "arguments" -> Json.obj("method" -> "GET".asJson, "path" -> path.asJson)
    )
  )

  private def text(j: Json): String =
    j.hcursor.downField("result").downField("content").downArray.get[String]("text").getOrElse(j.noSpaces)

  test("バッチの tools/call は 10 件まで。内部呼び出しの印は属性だけを信用し、例外の文言は返さない") {
    assume(db.isDefined, "TEST_DATABASE_URL がない")
    Database.migrate(db.get) *> Database.transactor(db.get, telemetry = false).use { xa =>
      val users = new UserStore(xa)
      val sessions = new SessionAuth(users, ApiConfig(Set.empty, cookieSecure = false, "http://localhost"))
      // REST API の代わり: 認証の経路（api・mcp）を返すものと、例外で終わるもの
      val api = HttpRoutes.of[IO] {
        case req @ GET -> Root / "api" / "via" =>
          sessions.fromRequest(req).flatMap(s => Ok(Json.obj("via" -> s.map(_.viaString).asJson)))
        case GET -> Root / "api" / "boom" => IO.raiseError(new RuntimeException("/var/secret/path が開けません"))
      }
      val mcp = (api <+> new McpRoutes(api, sessions, "http://localhost").routes).orNotFound
      val (key, prefix) = ApiKeys.generate()
      val auth = Authorization(Credentials.Token(AuthScheme.Bearer, key))
      def post(body: Json) = mcp.run(Request[IO](Method.POST, uri"/mcp").putHeaders(auth).withEntity(body))
      for {
        u <- users.upsert(s"test:${UUID.randomUUID()}", "テスト", None, None)
        _ <- users.createKey(u.id, "test", prefix, Crypto.sha256(key), List("read", "write"), None)
        // 外から付けたヘッダーでは mcp にならない
        outside <- mcp.run(Request[IO](Method.GET, uri"/api/via").putHeaders(auth, "X-Skorimako-Mcp" -> "1"))
          .flatMap(_.as[Json])
        _ = assertEquals(outside.hcursor.get[String]("via"), Right("api"))
        // MCP のツールからの内部呼び出しは mcp
        inside <- post(call(1, "/api/via")).flatMap(_.as[Json])
        _ = assert(text(inside).contains("mcp"), inside)
        // 例外の文言は返さず、コードだけ
        boom <- post(call(2, "/api/boom")).flatMap(_.as[Json])
        _ = assert(text(boom).contains(ErrorCode.McpToolFailed.code), boom)
        _ = assert(!boom.noSpaces.contains("secret"), boom)
        // バッチ
        ten <- post(Json.fromValues((1 to McpRoutes.MaxBatchCalls).map(call(_, "/api/via"))))
        _ = assertEquals(ten.status, Status.Ok)
        many <- post(Json.fromValues((1 to McpRoutes.MaxBatchCalls + 1).map(call(_, "/api/via"))))
        _ = assertEquals(many.status, Status.BadRequest)
        // tools/call 以外は、これまでどおり 50 件まで
        pings <- post(Json.fromValues((1 to 30).map(i =>
          Json.obj("jsonrpc" -> "2.0".asJson, "id" -> i.asJson, "method" -> "ping".asJson)
        )))
        _ = assertEquals(pings.status, Status.Ok)
      } yield ()
    }
  }
}
