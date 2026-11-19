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

/** S3 互換ストレージ。AWS SDK の同期クライアントを blocking プールで使う。 */
final class S3ObjectStore private (client: S3Client, bucket: String, prefix: String) extends ObjectStore {

  private def k(key: String): String = prefix + key

  def put(key: String, file: Path): IO[Unit] =
    IO.blocking(client.putObject(
      PutObjectRequest.builder().bucket(bucket).key(k(key)).contentType("application/vnd.apache.parquet").build(),
      RequestBody.fromFile(file)
    )).void

  def download(key: String, dest: Path): IO[Boolean] =
    IO.blocking {
      Files.createDirectories(dest.getParent)
      val tmp = Files.createTempFile(dest.getParent, ".dl-", ".part")
      try {
        Files.deleteIfExists(tmp)
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(k(key)).build(), ResponseTransformer.toFile(tmp))
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
      } catch {
        case _: NoSuchKeyException => false
      } finally Files.deleteIfExists(tmp)
    }

  def delete(key: String): IO[Unit] =
    IO.blocking(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k(key)).build())).void

  /** バケットがなければ作成する（開発環境向け。本番では事前作成を推奨）。 */
  def ensureBucket: IO[Unit] =
    IO.blocking {
      try client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
      catch case _: NoSuchBucketException => client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
    }.void
}

object S3ObjectStore {
  def resource(cfg: S3Config): Resource[IO, S3ObjectStore] =
    Resource
      .fromAutoCloseable(IO.blocking {
        S3Client
          .builder()
          .endpointOverride(URI.create(cfg.endpoint))
          .region(Region.of(cfg.region))
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
            cfg.accessKey,
            cfg.secretKey
          )))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.pathStyle).build())
          .httpClientBuilder(ApacheHttpClient.builder())
          .build()
      })
      .map { c =>
        val p = cfg.prefix.stripPrefix("/")
        new S3ObjectStore(c, cfg.bucket, if p.isEmpty || p.endsWith("/") then p else p + "/")
      }
}
