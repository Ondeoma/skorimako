package skorimako.storage

import cats.effect.*

import java.nio.file.{Files, Path, StandardCopyOption}

/** ローカルのディレクトリ（S3 を使わない1台だけの構成・試験用）。 */
final class LocalObjectStore(
  root: Path) extends ObjectStore {
  private def p(
    key: String
  ): Path = {
    val r = root.resolve(key).normalize()
    if !r.startsWith(root) then throw new IllegalArgumentException("invalid key")
    r
  }

  def put(
    key: String,
    file: Path
  ): IO[Unit] = IO.blocking {
    val d = p(key)
    Files.createDirectories(d.getParent)
    Files.copy(file, d, StandardCopyOption.REPLACE_EXISTING)
  }.void

  def download(
    key: String,
    dest: Path
  ): IO[Boolean] = IO.blocking {
    val s = p(key)
    if !Files.exists(s) then false
    else {
      Files.createDirectories(dest.getParent)
      val tmp = Files.createTempFile(dest.getParent, ".dl-", ".part")
      Files.copy(s, tmp, StandardCopyOption.REPLACE_EXISTING)
      Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      true
    }
  }

  def delete(
    key: String
  ): IO[Unit] = IO.blocking(Files.deleteIfExists(p(key))).void
}
