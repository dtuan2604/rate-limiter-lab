package lab.ratelimiter.gateway.policy.control;

import java.util.Objects;

public record SlidingWindowCounterAlgorithmDefinition(
    long limit, WindowDuration window, long requestCost) implements PolicyAlgorithmDefinition {

  public static final long MAXIMUM_LIMIT = 1_000_000;

  public SlidingWindowCounterAlgorithmDefinition {
    if (limit < 1 || limit > MAXIMUM_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and 1000000");
    }
    Objects.requireNonNull(window, "window");
    if (requestCost < 1 || requestCost > limit) {
      throw new IllegalArgumentException("request cost must be between 1 and limit");
    }
    Math.multiplyExact(limit, window.toMilliseconds());
  }

  @Override
  public PolicyAlgorithmType type() {
    return PolicyAlgorithmType.SLIDING_WINDOW_COUNTER;
  }
}
