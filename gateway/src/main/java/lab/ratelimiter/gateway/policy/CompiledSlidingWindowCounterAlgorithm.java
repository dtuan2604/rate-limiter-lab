package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.state.redis.SlidingCounterParameters;

public record CompiledSlidingWindowCounterAlgorithm(
    SlidingWindowCounterPolicy policy, long requestCost) implements CompiledAlgorithm {

  public CompiledSlidingWindowCounterAlgorithm {
    Objects.requireNonNull(policy, "policy");
    new SlidingCounterParameters(policy.limit(), policy.window().toMillis(), requestCost);
  }
}
