ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "io.github.ondeoma"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/Ondeoma/skorimako"))
ThisBuild / scalacOptions ++=
  Seq("-deprecation", "-feature", "-Wunused:imports", "-no-indent", "-Xmax-inlines", "64") // 列の多い行の型（doobie の導出）のため

val http4sV = "1.0.0-M49"
val circeV = "0.14.16"
val doobieV = "1.0.0-RC12"
val ceV = "3.7.1"
val fs2V = "3.14.0"
val munitV = "1.3.6"
val awsV = "2.30.38"
val flywayV = "11.3.0"
val duckdbV = "1.5.6.0"
val otlpV = "1.11.1-alpha"
val protobufV = "4.36.2"
val grpcV = "1.78.0"

// sbirds シリーズの共通ライブラリ（GitHub のリポジトリを直接参照する. 手元の sbirds-lib で試すときは SBIRDS_LIB=../sbirds-lib）
// skorimako は観測データの受け手なので、lib の observe の Telemetry（OTLP の送信）は使わない（自分に送ると回り続ける）.
// observe は Log（logback に出すだけ）と、http の Errors が使う部分だけ
lazy val sbirdsLib = sys.env
  .get("SBIRDS_LIB")
  .map(p => file(p).getCanonicalFile.toURI)
  .getOrElse(uri("https://github.com/Ondeoma/sbirds-lib.git#v0.1.0"))
lazy val libSharedJVM = ProjectRef(sbirdsLib, "sharedJVM")
lazy val libSharedJS = ProjectRef(sbirdsLib, "sharedJS")
lazy val libObserve = ProjectRef(sbirdsLib, "observe")
lazy val libAuth = ProjectRef(sbirdsLib, "auth")
lazy val libHttp = ProjectRef(sbirdsLib, "http")
lazy val libPush = ProjectRef(sbirdsLib, "push")
lazy val libWeb = ProjectRef(sbirdsLib, "web")
lazy val libLinks = ProjectRef(sbirdsLib, "links")

lazy val commonTest = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitV % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
  ),
  Test / fork := true
)

// バックエンドと画面(Scala.js)で共有する API の型
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full) // 日時(Timestamp)だけ JVM と JS で実装が違う
  .in(file("modules/shared"))
  .jvmConfigure(_.dependsOn(libSharedJVM))
  .jsConfigure(_.dependsOn(libSharedJS))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

// 画面: Scalactive の SPA（frontend/ の Vite から読み込む）
lazy val web = (project in file("modules/web"))
  .enablePlugins(ScalaJSPlugin, ScalactivePlugin)
  .dependsOn(shared.js, libWeb)
  .settings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.ondeoma" %%% "scalactive" % "0.6.2"
    )
  )

// ドメイン（純粋なロジック: 値の型・列名の規則・SQL の組み立てと検査・アラートの判定）とエラーコード
lazy val core = (project in file("modules/core"))
  .dependsOn(shared.jvm)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-parser" % circeV,
      "org.typelevel" %% "cats-effect" % ceV
    )
  )

lazy val db = (project in file("modules/db"))
  .dependsOn(core, libObserve)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-hikari" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV,
      "org.tpolecat" %% "doobie-postgres-circe" % doobieV,
      "org.typelevel" %% "cats-effect" % ceV,
      "org.postgresql" % "postgresql" % "42.7.14",
      "org.flywaydb" % "flyway-core" % flywayV,
      "org.flywaydb" % "flyway-database-postgresql" % flywayV % Runtime
    )
  )

lazy val auth = (project in file("modules/auth"))
  .dependsOn(core, libAuth)
  .settings(commonTest)

lazy val storage = (project in file("modules/storage"))
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      // netty-nio-client は依存ごとに外す（プロジェクト全体で外すと下流の解決で AWS SDK 自体が落ちることがある）
      ("software.amazon.awssdk" % "s3" % awsV).exclude("software.amazon.awssdk", "netty-nio-client"),
      "software.amazon.awssdk" % "apache-client" % awsV,
      "co.fs2" %% "fs2-io" % fs2V,
      "org.typelevel" %% "cats-effect" % ceV
    )
  )

// 観測データの処理: OTLP の解釈、取り込みのバッファと WAL、Parquet の書き出し、DuckDB による検索、圧縮・保持期間
lazy val engine = (project in file("modules/engine"))
  .dependsOn(core, db, storage)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.duckdb" % "duckdb_jdbc" % duckdbV,
      "io.opentelemetry.proto" % "opentelemetry-proto" % otlpV,
      "com.google.protobuf" % "protobuf-java" % protobufV,
      "com.google.protobuf" % "protobuf-java-util" % protobufV,
      "io.circe" %% "circe-parser" % circeV,
      "co.fs2" %% "fs2-io" % fs2V,
      // Prometheus の remote write（snappy）。純 Java の実装
      "io.airlift" % "aircompressor" % "2.0.2"
    )
  )

lazy val api = (project in file("modules/api"))
  .dependsOn(core, db % "compile;test->test", auth, storage, engine, shared.jvm, libHttp, libPush, libLinks)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-server" % http4sV,
      // OTLP/gRPC の受け付け（スタブは opentelemetry-proto に入っている）
      "io.grpc" % "grpc-netty-shaded" % grpcV,
      "io.grpc" % "grpc-protobuf" % grpcV,
      "io.grpc" % "grpc-stub" % grpcV
    )
  )

lazy val app = (project in file("modules/app"))
  .dependsOn(api)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sV,
      "org.http4s" %% "http4s-ember-client" % http4sV,
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
    ),
    Compile / mainClass := Some("skorimako.app.Main"),
    fork := true,
    // DuckDB は検索ごとに大きめのメモリを使う。ヒープとは別（ネイティブ）に確保される
    run / javaOptions ++= Seq("-Xmx1g")
  )

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, web, core, db, auth, storage, engine, api, app)
  .settings(name := "skorimako", publish / skip := true)
