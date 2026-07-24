package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;

class SlidingWindowLogRateLimiterContractTest extends AbstractRateLimiterContractTest {

  @Override
  RateLimiter<SlidingWindowLogPolicy, SlidingWindowLogState> newLimiter(
      long limit, MutableClock clock) {
    SlidingWindowLogPolicy policy =
        new SlidingWindowLogPolicy(
            POLICY_ID, POLICY_VERSION, limit, Duration.ofSeconds(1), Math.toIntExact(limit));
    return new InMemorySlidingWindowLogRateLimiter(policy, clock);
  }

  @Override
  Duration recoveryDuration() {
    return Duration.ofSeconds(1);
  }
}
