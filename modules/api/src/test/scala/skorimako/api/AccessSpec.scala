package skorimako.api

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import sbirds.db.{Database, DbConfig}
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*
import skorimako.storage.ObjectStore

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * ストリームの閲覧権限と検索の上限。DB を使うテストは TEST_DATABASE_URL がなければ飛ばす
 * （例: jdbc:postgresql://localhost:54383/skorimako_test）。
 */
class AccessSpec extends CatsEffectSuite {

  override def munitIOTimeout: Duration = 2.minutes

  private val db = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(
      url,
      sys.env.getOrElse("TEST_DATABASE_USER", "skorimako"),
      sys.env.getOrElse("TEST_DATABASE_PASSWORD", "skorimako")
    )
  )

  private final case class Env(streams: StreamStore,
                               alerts: AlertStore,
                               users: UserStore,
                               engine: QueryEngine,
                               duck: DuckConfig,
                               files: Path)

  /** S3 の代わりに、手元のディレクトリから取ってくる。 */
  private def objects(dir: Path): ObjectStore = new ObjectStore {
    def put(key: String, file: Path): IO[Unit] = IO.unit
    def download(key: String, dest: Path): IO[Boolean] = IO.blocking {
      val src = dir.resolve(key)
      Files.exists(src) && {
        Files.createDirectories(dest.getParent); Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING); true
      }
    }
    def delete(key: String): IO[Unit] = IO.unit
  }

  private def withEnv(name: String, perUser: Int = 2)(f: Env => IO[Unit]): Unit =
    test(name) {
      assume(db.isDefined, "TEST_DATABASE_URL がない")
      Database.migrate(db.get) *> Database.transactor(db.get, telemetry = false).use { xa =>
        val dir = Files.createTempDirectory("skorimako-access")
        val duck = DuckConfig("256MB", 2, dir.resolve("tmp"))
        val streams = new StreamStore(xa)
        for {
          acl <- Acl.create(streams)
          engine <- QueryEngine.create(
            streams,
            objects(dir.resolve("s3")),
            duck,
            QueryConfig(dir.resolve("cache"), perUser = perUser),
            acl,
            new Stats
          )
          _ <- f(Env(streams, new AlertStore(xa), new UserStore(xa), engine, duck, dir.resolve("s3")))
        } yield ()
      }
    }

  private val now = System.currentTimeMillis() * 1000
  private val admin = Viewer(admin = true, Set.empty, Some("admin"))
  private val outsider = Viewer(admin = false, Set("staff"), Some("outsider"))
  private val member = Viewer(admin = false, Set("ops"), Some("member"))

  /** ops だけが見られるストリームを作り、1 行のファイルを置く。 */
  private def secretStream(env: Env, streamType: String, name: String, service: String): IO[StreamRow] =
    for {
      st <- env.streams.ensure(streamType, name)
      _ <- env.streams.update(st.id, None, None, allowedGroups = Some(List("ops")))
      _ <- env.engine.acl.invalidate
      types = Map("body" -> FieldType.Utf8, "value" -> FieldType.Float64, "service_name" -> FieldType.Utf8)
      _ <- env.streams.mergeFields(st.id, types)
      key = s"$streamType/$name/${UUID.randomUUID()}.parquet"
      size <- IO.blocking {
        val out = env.files.resolve(key)
        Files.createDirectories(out.getParent)
        Duck.writeParquet(
          env.duck,
          Seq(Row(
            now - 1000000,
            Map("body" -> Value.Str("secret line"), "value" -> Value.Dbl(42), "service_name" -> Value.Str(service))
          )),
          types,
          out
        )
        Files.size(out)
      }
      _ <- env.streams.addFile(
        NewFile(st.id, key, now - 1000000, now - 1000000, 1, size, 0, "n", Map("service_name" -> List(service))),
        Nil
      )
    } yield st

  private def logs(env: Env, v: Viewer, stream: String) =
    env.engine.logs(v, LogSearchReq(stream, now - 3600000000L, now, None, Some("secret"), None, None, None))
      .map(_.map(d => (d.total, d.result.rows.size, d.result.columns.map(_.name))).leftMap(_.replace(stream, "X")))

  private def sql(env: Env, v: Viewer, stream: String) =
    env.engine.sql(v, SqlQueryReq(StreamType.Logs, s"""SELECT * FROM "$stream"""", now - 3600000000L, now, None))
      .map(_.map(_.rows.size).leftMap(_.replace(stream, "X")))

  private def metrics(env: Env, v: Viewer, metric: String) =
    env.engine.metrics(v, MetricQueryReq(metric, now - 3600000000L, now, "avg", Nil, None, None))
      .map(_.map(_.series.size).leftMap(_.replace(metric, "X")))

  withEnv("見られないストリームは、ないストリームと同じ結果になる（文言で存在を知らせない）") { env =>
    val tag = UUID.randomUUID().toString.take(8)
    val secret = s"secret_$tag"; val none = s"nosuch_$tag"
    val metric = s"secret_metric_$tag"; val noMetric = s"nosuch_metric_$tag"
    for {
      st <- secretStream(env, StreamType.Logs, secret, s"svc-$tag")
      mt <- secretStream(env, StreamType.Metrics, metric, s"svc-$tag")
      // 見られる人には行が返る
      _ <- logs(env, member, secret).map(r => assertEquals(r.map(_._1), Right(1L)))
      _ <- sql(env, admin, secret).assertEquals(Right(1))
      _ <- metrics(env, member, metric).assertEquals(Right(1))
      // 見られない人には、ないストリームと同じ結果
      l1 <- logs(env, outsider, secret); l2 <- logs(env, outsider, none)
      _ = assertEquals(l1, l2)
      _ = assert(l1.forall(_._1 == 0L), l1)
      s1 <- sql(env, outsider, secret); s2 <- sql(env, outsider, none)
      _ = assertEquals(s1, s2)
      _ = assert(s1.isLeft && !s1.left.exists(_.contains("権限")), s1)
      m1 <- metrics(env, outsider, metric); m2 <- metrics(env, outsider, noMetric)
      _ = assertEquals(m1, m2)
      _ = assert(m1.isLeft)
      // PromQL: 見られないメトリクスは空（ないメトリクスと同じ）
      expr = PromQL.parse(metric).toOption.get
      p1 <- env.engine.promData(outsider, expr, (now - 3600000000L) / 1000, now / 1000)
      _ = assertEquals(p1.map(_.values.map(_.size).sum), Right(0))
      p2 <- env.engine.promData(member, expr, (now - 3600000000L) / 1000, now / 1000)
      _ = assertEquals(p2.map(_.values.map(_.size).sum), Right(1))
      // SQL のルールの保存の判定は、これまでどおり見られないストリームを断る
      _ <- env.engine.allVisible(outsider, StreamType.Logs, Set(secret)).assertEquals(false)
      _ <- env.engine.allVisible(member, StreamType.Logs, Set(secret)).assertEquals(true)
      // サービスの名前: 見られないストリームにだけ出てくる名前は返さない
      hidden <- env.engine.acl.hidden(outsider)
      _ = assert(hidden.contains((StreamType.Logs, secret)))
      _ <- env.engine.acl.hidden(member).map(h => assert(!h.contains((StreamType.Logs, secret))))
      _ <- env.streams.serviceNames(hidden).map(ns => assert(!ns.contains(s"svc-$tag")))
      _ <- env.streams.serviceNames(Nil).map(ns => assert(ns.contains(s"svc-$tag")))
      _ <- env.streams.delete(st.id) *> env.streams.delete(mt.id)
    } yield ()
  }

  private def rule(name: String, stream: String, sql: Option[String] = None) = AlertRuleReq(
    name,
    "",
    enabled = false,
    StreamType.Logs,
    stream,
    if sql.isDefined then AlertMode.Sql else AlertMode.Threshold,
    None,
    "count",
    None,
    None,
    sql,
    ">",
    0,
    5,
    1,
    0,
    Nil
  )

  withEnv("アラートのルール・履歴は、対象のストリームを見られる人だけが見られる。ミュートの作成者が分かる") { env =>
    val tag = UUID.randomUUID().toString.take(8)
    val secret = s"secret_$tag"; val open = s"open_$tag"
    val access = new AlertAccess(env.alerts, env.engine)
    for {
      u <- env.users.upsert(s"test:$tag", "テスト", None, None)
      st <- secretStream(env, StreamType.Logs, secret, s"svc-$tag")
      op <- env.streams.ensure(StreamType.Logs, open)
      a <- env.alerts.create(rule(s"a-$tag", secret), u.id)
      b <- env.alerts.create(rule(s"b-$tag", "", Some(s"""SELECT count(*) AS value FROM "$secret"""")), u.id)
      c <- env.alerts.create(rule(s"c-$tag", open), u.id)
      mine = Set(a, b, c)
      rows <- env.alerts.rules.map(_.filter(r => mine(r.id)))
      _ <- rows.traverse_(r =>
        env.alerts.record(r, EvalRecord("firing", Some(1), None, false, true, Some(("firing", Some(1.0), Nil, r.name))))
      )
      // 一覧
      _ <- access.filter(outsider, rows).map(rs => assertEquals(rs.map(_.id).toSet, Set(c)))
      _ <- access.filter(member, rows).map(rs => assertEquals(rs.map(_.id).toSet, mine))
      _ <- access.filter(admin, rows).map(rs => assertEquals(rs.map(_.id).toSet, mine))
      // 個別（見られないものは、ないものと同じ）
      _ <- access.rule(outsider, a).map(r => assert(r.isEmpty))
      _ <- access.rule(outsider, b).map(r => assert(r.isEmpty))
      _ <- access.rule(outsider, c).map(r => assert(r.isDefined))
      _ <- access.rule(member, b).map(r => assert(r.isDefined))
      // 履歴
      _ <- access.events(outsider, None, 1000).map(es => assertEquals(es.map(_.ruleId).toSet & mine, Set(c)))
      _ <- access.events(outsider, Some(a), 1000).map(es => assertEquals(es, Nil))
      _ <- access.events(member, None, 1000).map(es => assertEquals(es.map(_.ruleId).toSet & mine, mine))
      _ <- access.visibleIds(admin).assertEquals(None)
      // ミュートの作成者
      t = java.time.Instant.now()
      sid <- env.alerts.createSilence(Some(c), "テスト", t, t.plusSeconds(60), u.id)
      _ <- env.alerts.silenceOwner(sid).assertEquals(Some(Some(u.id)))
      _ <- env.alerts.silenceOwner(UUID.randomUUID()).assertEquals(None)
      _ <- mine.toList.traverse_(env.alerts.delete)
      _ <- env.streams.delete(st.id) *> env.streams.delete(op.id)
    } yield ()
  }

  /** 同じ利用者の検索を n 本同時に頼んだときの、同時に動いた数の最大。 */
  private def maxActive(env: Env, v: Viewer, n: Int): IO[Int] = {
    val active =
      new AtomicInteger(0);
    val max = new AtomicInteger(0)
    (1 to n).toList.parTraverse_(_ =>
      env.engine.session(v, StreamType.Logs, Nil, 0, 1) { _ =>
        max.accumulateAndGet(active.incrementAndGet(), math.max(_, _))
        Thread.sleep(300)
        active.decrementAndGet()
      }.map(r => assert(r.isRight, r))
    ).map(_ => max.get)
  }

  withEnv("利用者ごとの検索の同時実行は 2 本まで（サーバーの中の処理は対象外）") { env =>
    for {
      _ <- maxActive(env, outsider, 4).assertEquals(2)
      // ほかの利用者の分は別に数える
      _ <- (maxActive(env, outsider, 2), maxActive(env, member, 2)).parTupled.assertEquals((2, 2))
      _ <- maxActive(env, Viewer.system, 4).map(m => assert(m > 2, m))
    } yield ()
  }

  withEnv("PromQL: セレクターは 20 個まで") { env =>
    def expr(n: Int) = PromQL.parse((1 to n).map(i => s"nosuch_metric_$i").mkString(" + ")).toOption.get
    for {
      _ <- env.engine.promData(outsider, expr(QueryEngine.MaxPromSelectors), 0, 1000).map(r => assert(r.isRight, r))
      _ <- env.engine.promData(outsider, expr(QueryEngine.MaxPromSelectors + 1), 0, 1000).map(r =>
        assert(r.left.exists(_.contains("セレクターが多すぎます")), r)
      )
    } yield ()
  }

  test("PromQL: 式は 10000 文字まで（読む前に断る）") {
    // 長さの確認は、ほかの部品を使う前に行う
    val routes = new PromRoutes(null, null, null, null, null)
    routes.evaluate(outsider, "a" * (PromRoutes.MaxQueryLength + 1), 0, 0, 0).map(r =>
      assert(r.left.exists(_.contains("式が長すぎます")), r)
    )
  }

  test("予期しない失敗は、コードと決まった文言だけを返す（例外の文言を返さない）") {
    QueryEngine.internal(ErrorCode.QueryPrepareFailed, new RuntimeException("/var/secret/path が開けません")).map { m =>
      assert(m.contains(ErrorCode.QueryPrepareFailed.code), m)
      assert(!m.contains("secret"), m)
    }
  }
}
