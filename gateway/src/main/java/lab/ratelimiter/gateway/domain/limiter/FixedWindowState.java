package lab.ratelimiter.gateway.domain.limiter;

import java.time.Instant;
import java.util.Objects;

public record FixedWindowState(Instant windowStart, long used, Instant observedAt)
    implements RateLimitState {

  public FixedWindowState {
    Objects.requireNonNull(windowStart, "windowStart");
    Objects.requireNonNull(observedAt, "observedAt");
    ModelValidation.requireNonNegative(used, "used");
  }
}
