package skorimako.storage

import cats.effect.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.{RequestBody, ResponseTransformer}
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import software.amazon.awssdk.services.s3.model.*

import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}

final case class S3Config(
    endpoint: String,
    region: String,
    bucket: String,
    accessKey: String,
    secretKey: String,
    pathStyle: Boolean = true, // 自前の S3 互換ストレージはパス形式が扱いやすい
    prefix: String = ""        // バケットの中の置き場所（ほかの用途と共用するとき）
)

/** 観測データのファイル（Parquet）の置き場所。 */
trait ObjectStore:
  def put(key: String, file: Path): IO[Unit]
  /** dest に保存する（途中で失敗しても dest に半端なファイルを残さない）。なければ false。 */
  def download(key: String, dest: Path): IO[Boolean]
  def delete(key: String): IO[Unit]

/** S3 互換ストレージ。AWS SDK の同期クライアントを blocking プールで使う。 */
final class S3ObjectStore private (client: S3Client, bucket: String, prefix: String) extends ObjectStore:

  private def k(key: String): String = prefix + key

  def put(key: String, file: Path): IO[Unit] =
    IO.blocking(client.putObject(PutObjectRequest.builder().bucket(bucket).key(k(key)).contentType("application/vnd.apache.parquet").build(),
      RequestBody.fromFile(file))).void

  def download(key: String, dest: Path): IO[Boolean] =
    IO.blocking {
      Files.createDirectories(dest.getParent)
      val tmp = Files.createTempFile(dest.getParent, ".dl-", ".part")
      try
        Files.deleteIfExists(tmp)
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(k(key)).build(), ResponseTransformer.toFile(tmp))
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
      catch
        case _: NoSuchKeyException => false
      finally Files.deleteIfExists(tmp)
    }

  def delete(key: String): IO[Unit] =
    IO.blocking(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k(key)).build())).void

  /** バケットがなければ作成する（開発環境向け。本番では事前作成を推奨）。 */
  def ensureBucket: IO[Unit] =
    IO.blocking {
      try client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
      catch case _: NoSuchBucketException => client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
    }.void

object S3ObjectStore:
  def resource(cfg: S3Config): Resource[IO, S3ObjectStore] =
    Resource
      .fromAutoCloseable(IO.blocking {
        S3Client
          .builder()
          .endpointOverride(URI.create(cfg.endpoint))
          .region(Region.of(cfg.region))
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey)))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.pathStyle).build())
          .httpClientBuilder(ApacheHttpClient.builder())
          .build()
      })
      .map { c =>
        val p = cfg.prefix.stripPrefix("/")
        new S3ObjectStore(c, cfg.bucket, if p.isEmpty || p.endsWith("/") then p else p + "/")
      }

/** ローカルのディレクトリ（S3 を使わない1台だけの構成・試験用）。 */
final class LocalObjectStore(root: Path) extends ObjectStore:
  private def p(key: String): Path =
    val r = root.resolve(key).normalize()
    if !r.startsWith(root) then throw new IllegalArgumentException("invalid key")
    r

  def put(key: String, file: Path): IO[Unit] = IO.blocking {
    val d = p(key)
    Files.createDirectories(d.getParent)
    Files.copy(file, d, StandardCopyOption.REPLACE_EXISTING)
  }.void

  def download(key: String, dest: Path): IO[Boolean] = IO.blocking {
    val s = p(key)
    if !Files.exists(s) then false
    else
      Files.createDirectories(dest.getParent)
      val tmp = Files.createTempFile(dest.getParent, ".dl-", ".part")
      Files.copy(s, tmp, StandardCopyOption.REPLACE_EXISTING)
      Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      true
  }

  def delete(key: String): IO[Unit] = IO.blocking(Files.deleteIfExists(p(key))).void
