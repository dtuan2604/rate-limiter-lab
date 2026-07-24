package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;

class FixedWindowRateLimiterContractTest extends AbstractRateLimiterContractTest {

  @Override
  RateLimiter<FixedWindowPolicy, FixedWindowState> newLimiter(long limit, MutableClock clock) {
    FixedWindowPolicy policy =
        new FixedWindowPolicy(POLICY_ID, POLICY_VERSION, limit, Duration.ofSeconds(1));
    return new InMemoryFixedWindowRateLimiter(policy, clock);
  }

  @Override
  Duration recoveryDuration() {
    return Duration.ofSeconds(1);
  }
}
