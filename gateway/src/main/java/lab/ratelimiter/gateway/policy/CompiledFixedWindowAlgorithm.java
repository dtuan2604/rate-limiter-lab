package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;

public record CompiledFixedWindowAlgorithm(FixedWindowPolicy policy) implements CompiledAlgorithm {

  public CompiledFixedWindowAlgorithm {
    Objects.requireNonNull(policy, "policy");
  }
}
