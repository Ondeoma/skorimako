package skorimako.api

import cats.effect.*
import org.http4s.*
import sbirds.http.RateLimitConfig
import skorimako.core.ErrorCode

import scala.concurrent.duration.FiniteDuration

/**
 * skorimako の数え方（どの入口をどのカウンタで数えるか）.
 * 共通の数え方（/auth/…・/oauth/authorize は auth、/oauth/token・/oauth/revoke・/api/public/… は public の枠）は
 * sbirds-lib の RateLimit.standard. ここでは、skorimako だけの違いを決める.
 * 観測データの取り込み（Guards.isIngest: OTLP・JSON・remote write・RUM）は、エージェントが同じ IP から大量に送るのが正常なので、数えない.
 */
object RateLimit {

  def apply(routes: HttpRoutes[IO],
            cfg: RateLimitConfig,
            clock: IO[FiniteDuration] = IO.realTime): HttpRoutes[IO] =
    sbirds.http.RateLimit.standard(routes, cfg, ErrorCode.TooManyRequests, clock) { b =>
      { case req if Guards.isIngest(req.pathInfo.renderString) => b.skip }
    }
}
