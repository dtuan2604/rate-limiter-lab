package lab.ratelimiter.gateway.domain.limiter;

import java.time.Instant;
import java.util.Objects;

public record SlidingWindowCounterState(
    Instant currentWindowStart, long previousCount, long currentCount, Instant observedAt)
    implements RateLimitState {

  public SlidingWindowCounterState {
    Objects.requireNonNull(currentWindowStart, "currentWindowStart");
    Objects.requireNonNull(observedAt, "observedAt");
    ModelValidation.requireNonNegative(previousCount, "previous count");
    ModelValidation.requireNonNegative(currentCount, "current count");
  }
}
