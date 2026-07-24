package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

public record TokenBucketState(BigInteger scaledTokens, Instant observedAt)
    implements RateLimitState {

  public TokenBucketState {
    Objects.requireNonNull(scaledTokens, "scaledTokens");
    Objects.requireNonNull(observedAt, "observedAt");
    ModelValidation.requireNonNegative(scaledTokens, "scaled tokens");
  }
}
