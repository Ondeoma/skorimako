package skorimako.api

import cats.effect.*
import skorimako.core.*
import skorimako.engine.*
import sbirds.shared.AppError

/**
 * 観測データの取り込み。
 *  - OTLP/HTTP: POST /v1/logs・/v1/metrics・/v1/traces（protobuf または JSON、gzip 可）。ストリームは stream-name ヘッダー（既定 default）
 *  - JSON: POST /api/ingest/{ストリーム}/_json（オブジェクトかその配列）・/_multi（NDJSON）
 *  - ブラウザ（RUM）: POST /rum/v1/{アプリのトークン}（/rum.js が送る。CORS）
 * 認証は ingest（または write）スコープの APIキー（Bearer、または Basic のパスワード）。RUM はアプリのトークン。
 */
/** 取り込みの入口（HTTP・gRPC・remote write で共通）: 変換と上限を当ててからバッファに入れる。 */
final class IngestService(ingester: Ingester, transform: Transform) {
  def ingest(streamType: String, batch: Batch): IO[Either[AppError, Int]] =
    transform(streamType, batch).flatMap {
      case Left(r) => IO.pure(Left(r))
      case Right(b) => ingester.ingest(streamType, b).map(_.left.map(AppError(ErrorCode.IngestBufferFull, _)))
    }
}
