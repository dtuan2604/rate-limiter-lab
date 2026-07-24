package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;
import java.util.Objects;

public record FixedWindowPolicy(
    PolicyId policyId, PolicyVersion policyVersion, long limit, Duration window)
    implements RateLimitPolicy {

  public FixedWindowPolicy {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(policyVersion, "policyVersion");
    ModelValidation.requirePositive(limit, "limit");
    ModelValidation.requirePositiveWholeMilliseconds(window, "window");
  }

  @Override
  public AlgorithmType algorithm() {
    return AlgorithmType.FIXED_WINDOW;
  }
}
