package lab.ratelimiter.gateway.policy.control;

import java.time.Duration;
import java.util.Objects;

public record FixedWindowAlgorithmDefinition(long limit, Duration window)
    implements PolicyAlgorithmDefinition {

  public FixedWindowAlgorithmDefinition {
    if (limit < 1 || limit > 1_000_000) {
      throw new IllegalArgumentException("limit must be between 1 and 1000000");
    }
    Objects.requireNonNull(window, "window");
    long milliseconds = window.toMillis();
    if (milliseconds < 1
        || milliseconds > Duration.ofDays(1).toMillis()
        || !window.equals(Duration.ofMillis(milliseconds))) {
      throw new IllegalArgumentException("window must be 1..86400000 whole milliseconds");
    }
  }

  @Override
  public PolicyAlgorithmType type() {
    return PolicyAlgorithmType.FIXED_WINDOW;
  }
}
