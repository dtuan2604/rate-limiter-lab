package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;

class TokenBucketRateLimiterContractTest extends AbstractRateLimiterContractTest {

  @Override
  RateLimiter<TokenBucketPolicy, TokenBucketState> newLimiter(long limit, MutableClock clock) {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            POLICY_ID, POLICY_VERSION, limit, limit, limit, Duration.ofSeconds(1));
    return new InMemoryTokenBucketRateLimiter(policy, clock);
  }

  @Override
  Duration recoveryDuration() {
    return Duration.ofSeconds(1);
  }
}
