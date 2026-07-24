package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

public record LeakyBucketState(BigInteger scaledLevel, Instant observedAt)
    implements RateLimitState {

  public LeakyBucketState {
    Objects.requireNonNull(scaledLevel, "scaledLevel");
    Objects.requireNonNull(observedAt, "observedAt");
    ModelValidation.requireNonNegative(scaledLevel, "scaled level");
  }
}
