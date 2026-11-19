package skorimako.storage

import cats.effect.*

import java.nio.file.Path

final case class S3Config(
  endpoint: String,
  region: String,
  bucket: String,
  accessKey: String,
  secretKey: String,
  pathStyle: Boolean = true, // 自前の S3 互換ストレージはパス形式が扱いやすい
  prefix: String = "" // バケットの中の置き場所（ほかの用途と共用するとき）
)

/** 観測データのファイル（Parquet）の置き場所。 */
trait ObjectStore {
  def put(key: String, file: Path): IO[Unit]

  /** dest に保存する（途中で失敗しても dest に半端なファイルを残さない）。なければ false。 */
  def download(key: String, dest: Path): IO[Boolean]
  def delete(key: String): IO[Unit]
}
