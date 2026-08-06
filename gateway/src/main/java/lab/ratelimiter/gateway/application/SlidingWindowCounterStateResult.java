package lab.ratelimiter.gateway.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.state.redis.SlidingCounterRotation;

public record SlidingWindowCounterStateResult(
    RateLimitDecision decision,
    long currentWindowId,
    long currentWindowCount,
    long previousWindowCount,
    Duration windowElapsed,
    long weightedNumerator,
    long weightedEstimate,
    long requestCost,
    long remainingCapacity,
    Duration retryAfter,
    Duration resetAfter,
    Instant redisNow,
    Duration ttl,
    SlidingCounterRotation rotation,
    StateBackend stateBackend,
    RedisOutcome redisOutcome) {

  public SlidingWindowCounterStateResult {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(windowElapsed, "windowElapsed");
    Objects.requireNonNull(retryAfter, "retryAfter");
    Objects.requireNonNull(resetAfter, "resetAfter");
    Objects.requireNonNull(redisNow, "redisNow");
    Objects.requireNonNull(ttl, "ttl");
    Objects.requireNonNull(rotation, "rotation");
    Objects.requireNonNull(stateBackend, "stateBackend");
    Objects.requireNonNull(redisOutcome, "redisOutcome");
    if (currentWindowId < 0
        || currentWindowCount < 0
        || previousWindowCount < 0
        || weightedNumerator < 0
        || weightedEstimate < 0
        || requestCost < 1
        || remainingCapacity < 0
        || windowElapsed.isNegative()
        || retryAfter.isNegative()
        || resetAfter.isNegative()
        || ttl.isNegative()) {
      throw new IllegalArgumentException("Sliding Counter result contains an invalid value");
    }
  }
}
