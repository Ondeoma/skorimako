package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.client.Client
import sbirds.auth.Crypto
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import sbirds.http.Errors

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * ノード間の問い合わせ: 書き出す前の行（各ノードのバッファ）を、検索するノードが集める。
 * 各ノードは 30秒ごとに自分の URL（NODE_URL）を node の表に登録する。問い合わせには APP_SECRET から作る署名を付ける。
 * 返事のないノードは飛ばす（検索は止めない。そのノードの書き出す前の行が出ないだけ）。
 */
final class PeerRoutes(
  ingester: Ingester,
  streams: StreamStore,
  http: Client[IO],
  nodeId: String,
  nodeUrl: Option[String],
  secret: String)
  extends org.http4s.dsl.Http4sDsl[IO] {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "internal" / "buffer" => getInternalBuffer(req)
  }

  private def getInternalBuffer(
    req: Request[IO]
  ): IO[Response[IO]] = {
    val p = (k: String) => req.params.getOrElse(k, "")
    val (t, ns, st, en, exp) = (p("type"), p("streams"), p("start"), p("end"), p("exp"))
    val fresh = exp.toLongOption.exists(e =>
      e > System.currentTimeMillis() / 1000 && e < System.currentTimeMillis() / 1000 + 120
    )
    if secret.isEmpty || !fresh || !Crypto.constantTimeEquals(sign(t, ns, st, en, exp), p("sig")) then
      Errors.respond(ErrorCode.PeerSignatureInvalid)
    else
      (st.toLongOption, en.toLongOption).tupled match {
        case None => Errors.respond(ErrorCode.PeerRequestInvalid)
        case Some((a, b)) =>
          ingester.snapshot(t, ns.split(',').filter(_.nonEmpty).toSet, a, b).map(m =>
            Response[IO](Status.Ok).withEntity(rowsJson(m))(using EntityEncoder.stringEncoder)
          )
      }
  }

  private def sign(
    parts: String*
  ): String = Crypto.hmacHex("skorimako-peer:" + secret, parts.mkString("|").getBytes(StandardCharsets.UTF_8))

  private def rowsJson(
    m: Map[String, Vector[Row]]
  ): String =
    m.toList.flatMap(
      (
        n,
        rows
      ) => rows.map(r => Json.obj("s" -> Json.fromString(n), "r" -> r.toJson).noSpaces)
    ).mkString("\n")

  /** このノードのバッファ＋ほかのノードのバッファ。 */
  val live: LiveRows = (
    streamType,
    names,
    start,
    end
  ) => {
    val local = ingester.snapshot(streamType, names, start, end)
    val remote = streams.peers(nodeId).flatMap(_.parTraverse(
      (
        id,
        url
      ) =>
        fetch(url, streamType, names, start, end).handleErrorWith(e =>
          IO(SelfLog.warn(s"buffer query to $id failed: ${e.getClass.getSimpleName}")).as(Map.empty[
            String,
            Vector[Row]
          ])
        )
    ))
      .handleError(_ => Nil)
    (local, remote).parMapN(
      (
        l,
        rs
      ) =>
        (l :: rs).foldLeft(Map.empty[String, Vector[Row]])(
          (
            acc,
            m
          ) =>
            m.foldLeft(acc) { case (a, (k, v)) => a.updated(k, a.getOrElse(k, Vector.empty) ++ v) }
        )
    )
  }

  private def fetch(
    base: String,
    streamType: String,
    names: Set[String],
    start: Long,
    end: Long
  ): IO[Map[String, Vector[Row]]] = {
    val ns = names.toList.sorted.mkString(",")
    val exp = (System.currentTimeMillis() / 1000 + 60).toString
    val uri = Uri.unsafeFromString(base.stripSuffix("/") + "/internal/buffer").withQueryParams(Map(
      "type" -> streamType,
      "streams" -> ns,
      "start" -> start.toString,
      "end" -> end.toString,
      "exp" -> exp,
      "sig" -> sign(streamType, ns, start.toString, end.toString, exp)
    ))
    http.expect[String](uri)(using EntityDecoder.text).timeout(3.seconds).map { text =>
      text.linesIterator.filter(_.nonEmpty).flatMap(l => parse(l).toOption).flatMap { j =>
        val c = j.hcursor
        (c.get[String]("s").toOption, c.downField("r").focus.flatMap(Row.fromJson)).tupled
      }.toVector.groupMap(_._1)(_._2)
    }
  }

  /** 自分を登録し続ける（止めるときは外す）。 */
  def heartbeat: Resource[IO, Unit] =
    nodeUrl match {
      case None =>
        Resource.eval(sbirds.observe.Log.println("[skorimako] NODE_URL が未設定のため、ほかのノードの書き出す前の行は検索に出ません（1台なら不要）"))
      case Some(url) =>
        Resource.make(IO.unit)(_ => streams.leaveNode(nodeId).handleError(_ => ())) *>
          (streams.heartbeat(nodeId, url).handleErrorWith(e =>
            sbirds.observe.Log.report(ErrorCode.HeartbeatFailed, e.toString, e)
          ) *> IO.sleep(30.seconds)).foreverM.background.void
    }
}
