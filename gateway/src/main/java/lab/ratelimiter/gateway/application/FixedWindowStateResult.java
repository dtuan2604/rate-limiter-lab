package lab.ratelimiter.gateway.application;

import java.time.Duration;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;

public record FixedWindowStateResult(
    RateLimitDecision decision,
    long currentCount,
    Duration resetAfter,
    StateBackend stateBackend,
    RedisOutcome redisOutcome) {

  public FixedWindowStateResult {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(resetAfter, "resetAfter");
    Objects.requireNonNull(stateBackend, "stateBackend");
    Objects.requireNonNull(redisOutcome, "redisOutcome");
    if (currentCount < 0 || currentCount > decision.limit()) {
      throw new IllegalArgumentException("current count must be within the configured limit");
    }
    if (resetAfter.isNegative()) {
      throw new IllegalArgumentException("reset after must not be negative");
    }
  }
}
