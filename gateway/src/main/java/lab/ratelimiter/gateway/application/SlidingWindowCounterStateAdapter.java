package lab.ratelimiter.gateway.application;

import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import reactor.core.publisher.Mono;

public interface SlidingWindowCounterStateAdapter {

  Mono<SlidingWindowCounterStateResult> decide(
      SlidingWindowCounterPolicy policy, long requestCost, LimiterIdentity identity);
}
