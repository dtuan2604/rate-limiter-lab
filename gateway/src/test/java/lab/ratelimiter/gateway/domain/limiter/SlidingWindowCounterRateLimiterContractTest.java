package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;

class SlidingWindowCounterRateLimiterContractTest extends AbstractRateLimiterContractTest {

  @Override
  RateLimiter<SlidingWindowCounterPolicy, SlidingWindowCounterState> newLimiter(
      long limit, MutableClock clock) {
    SlidingWindowCounterPolicy policy =
        new SlidingWindowCounterPolicy(POLICY_ID, POLICY_VERSION, limit, Duration.ofSeconds(1));
    return new InMemorySlidingWindowCounterRateLimiter(policy, clock);
  }

  @Override
  Duration recoveryDuration() {
    return Duration.ofSeconds(2);
  }
}
