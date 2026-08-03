package lab.ratelimiter.gateway.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;

public record RateLimitEvaluation(
    RateLimitOutcome outcome,
    Optional<RateLimitDecision> rateLimitDecision,
    Optional<Duration> resetAfter,
    StateBackend stateBackend,
    RedisOutcome redisOutcome,
    FailureMode failureMode) {

  public RateLimitEvaluation {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(rateLimitDecision, "rateLimitDecision");
    Objects.requireNonNull(resetAfter, "resetAfter");
    Objects.requireNonNull(stateBackend, "stateBackend");
    Objects.requireNonNull(redisOutcome, "redisOutcome");
    Objects.requireNonNull(failureMode, "failureMode");
  }

  public boolean degraded() {
    return outcome == RateLimitOutcome.DEGRADED_ALLOW
        || outcome == RateLimitOutcome.STATE_UNAVAILABLE;
  }
}
