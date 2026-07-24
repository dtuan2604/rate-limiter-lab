package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;
import java.util.Objects;

public record TokenBucketPolicy(
    PolicyId policyId,
    PolicyVersion policyVersion,
    long capacity,
    long initialTokens,
    long refillTokens,
    Duration refillPeriod)
    implements RateLimitPolicy {

  public TokenBucketPolicy {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(policyVersion, "policyVersion");
    ModelValidation.requirePositive(capacity, "capacity");
    ModelValidation.requireNonNegative(initialTokens, "initial tokens");
    ModelValidation.requirePositive(refillTokens, "refill tokens");
    ModelValidation.requirePositiveWholeMilliseconds(refillPeriod, "refill period");
    if (initialTokens > capacity) {
      throw new IllegalArgumentException("initial tokens must not exceed capacity");
    }
  }

  @Override
  public AlgorithmType algorithm() {
    return AlgorithmType.TOKEN_BUCKET;
  }

  @Override
  public long limit() {
    return capacity;
  }
}
