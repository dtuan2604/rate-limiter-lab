package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;
import java.util.Objects;

public record SlidingWindowCounterPolicy(
    PolicyId policyId, PolicyVersion policyVersion, long limit, Duration window)
    implements RateLimitPolicy {

  public SlidingWindowCounterPolicy {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(policyVersion, "policyVersion");
    ModelValidation.requirePositive(limit, "limit");
    ModelValidation.requirePositiveWholeMilliseconds(window, "window");
  }

  @Override
  public AlgorithmType algorithm() {
    return AlgorithmType.SLIDING_WINDOW_COUNTER;
  }
}
