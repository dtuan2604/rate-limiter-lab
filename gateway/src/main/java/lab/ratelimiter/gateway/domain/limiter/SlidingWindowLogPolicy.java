package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;
import java.util.Objects;

public record SlidingWindowLogPolicy(
    PolicyId policyId, PolicyVersion policyVersion, long limit, Duration window, int maximumEntries)
    implements RateLimitPolicy {

  public SlidingWindowLogPolicy {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(policyVersion, "policyVersion");
    ModelValidation.requirePositive(limit, "limit");
    ModelValidation.requirePositiveWholeMilliseconds(window, "window");
    if (maximumEntries < limit) {
      throw new IllegalArgumentException("maximum entries must be at least the limit");
    }
  }

  @Override
  public AlgorithmType algorithm() {
    return AlgorithmType.SLIDING_WINDOW_LOG;
  }
}
