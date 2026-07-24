package lab.ratelimiter.gateway.domain.limiter;

import java.time.Instant;

public sealed interface RateLimitState
    permits FixedWindowState,
        SlidingWindowLogState,
        SlidingWindowCounterState,
        TokenBucketState,
        LeakyBucketState {

  Instant observedAt();
}
