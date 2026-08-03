package lab.ratelimiter.gateway.application;

import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import reactor.core.publisher.Mono;

public interface FixedWindowStateAdapter {

  Mono<FixedWindowStateResult> decide(
      FixedWindowPolicy policy, LimiterIdentity identity, RateLimitRequest request);
}
