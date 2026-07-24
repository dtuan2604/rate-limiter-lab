package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;

class LeakyBucketRateLimiterContractTest extends AbstractRateLimiterContractTest {

  @Override
  RateLimiter<LeakyBucketPolicy, LeakyBucketState> newLimiter(long limit, MutableClock clock) {
    LeakyBucketPolicy policy =
        new LeakyBucketPolicy(POLICY_ID, POLICY_VERSION, limit, limit, Duration.ofSeconds(1));
    return new InMemoryLeakyBucketRateLimiter(policy, clock);
  }

  @Override
  Duration recoveryDuration() {
    return Duration.ofSeconds(1);
  }
}
