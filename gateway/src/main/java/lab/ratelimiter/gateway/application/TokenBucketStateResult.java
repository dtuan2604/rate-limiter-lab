package lab.ratelimiter.gateway.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;

public record TokenBucketStateResult(
    RateLimitDecision decision,
    long remainingScaledTokens,
    long requestCostScaled,
    long refillTokensScaled,
    Duration refillPeriod,
    Duration retryAfter,
    Duration resetAfter,
    Instant redisNow,
    Duration ttl,
    long refillRemainder,
    boolean stateReconstructed,
    StateBackend stateBackend,
    RedisOutcome redisOutcome) {

  public TokenBucketStateResult {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(refillPeriod, "refillPeriod");
    Objects.requireNonNull(retryAfter, "retryAfter");
    Objects.requireNonNull(resetAfter, "resetAfter");
    Objects.requireNonNull(redisNow, "redisNow");
    Objects.requireNonNull(ttl, "ttl");
    Objects.requireNonNull(stateBackend, "stateBackend");
    Objects.requireNonNull(redisOutcome, "redisOutcome");
    if (remainingScaledTokens < 0
        || requestCostScaled < 1
        || refillTokensScaled < 1
        || refillRemainder < 0
        || retryAfter.isNegative()
        || resetAfter.isNegative()
        || ttl.isNegative()) {
      throw new IllegalArgumentException("Token Bucket result contains an invalid value");
    }
  }
}
