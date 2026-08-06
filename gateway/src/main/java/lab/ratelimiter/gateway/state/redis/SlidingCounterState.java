package lab.ratelimiter.gateway.state.redis;

import lab.ratelimiter.gateway.application.RedisOutcome;

public record SlidingCounterState(long windowId, long currentCount, long previousCount) {

  public SlidingCounterState {
    if (windowId < 0 || currentCount < 0 || previousCount < 0) {
      throw new RedisStateException(
          RedisOutcome.MALFORMED_STATE, "Sliding Counter state values must be nonnegative");
    }
  }
}
