package lab.ratelimiter.gateway.application;

import java.time.Instant;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import reactor.core.publisher.Mono;

public interface TokenBucketStateAdapter {

  Mono<TokenBucketStateResult> decide(
      TokenBucketPolicy policy, long requestCost, Instant activationTime, LimiterIdentity identity);
}
