package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;
import java.util.Objects;

public record LeakyBucketPolicy(
    PolicyId policyId,
    PolicyVersion policyVersion,
    long capacity,
    long leakUnits,
    Duration leakPeriod)
    implements RateLimitPolicy {

  public LeakyBucketPolicy {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(policyVersion, "policyVersion");
    ModelValidation.requirePositive(capacity, "capacity");
    ModelValidation.requirePositive(leakUnits, "leak units");
    ModelValidation.requirePositiveWholeMilliseconds(leakPeriod, "leak period");
  }

  @Override
  public AlgorithmType algorithm() {
    return AlgorithmType.LEAKY_BUCKET;
  }

  @Override
  public long limit() {
    return capacity;
  }
}
