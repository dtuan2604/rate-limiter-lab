package lab.ratelimiter.gateway.state.redis;

import java.util.Objects;

public record SlidingCounterTransition(
    boolean allowed,
    SlidingCounterState state,
    long currentWindowStartMilliseconds,
    long elapsedMilliseconds,
    long weightedNumerator,
    long weightedEstimate,
    long remainingCapacity,
    long retryAfterMilliseconds,
    long resetAfterMilliseconds,
    SlidingCounterRotation rotation) {

  public SlidingCounterTransition {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(rotation, "rotation");
    if (currentWindowStartMilliseconds < 0
        || elapsedMilliseconds < 0
        || weightedNumerator < 0
        || weightedEstimate < 0
        || remainingCapacity < 0
        || retryAfterMilliseconds < 0
        || resetAfterMilliseconds < 0) {
      throw new IllegalArgumentException("Sliding Counter result values must be nonnegative");
    }
  }
}
