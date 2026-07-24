package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryTokenBucketRateLimiterTest {

  private static final PolicyId POLICY_ID = new PolicyId("token");
  private static final PolicyVersion VERSION = new PolicyVersion(6);
  private static final Instant START = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void startsWithTheConfiguredInitialTokenCount() {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter = newLimiter(10, 2, 5, clock);

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(3));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.remaining()).isEqualTo(2);
    assertThat(decision.retryAfter()).contains(Duration.ofMillis(200));
  }

  @Test
  void refillsContinuouslyAtMillisecondPrecision() {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter = newLimiter(10, 0, 4, clock);
    clock.advance(Duration.ofMillis(250));

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(1));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isZero();
    assertThat(limiter.snapshot().scaledTokens()).isZero();
  }

  @Test
  void capsRefillAtCapacityAfterLargeElapsedTime() {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter = newLimiter(10, 0, 2, clock);
    clock.advance(Duration.ofDays(3650));

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(1));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(9);
    assertThat(limiter.snapshot().scaledTokens()).isEqualTo(BigInteger.valueOf(9000));
  }

  @Test
  void computesRequestRetryAndFullRefillResetSeparately() {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter = newLimiter(10, 0, 4, clock);

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(3));

    assertThat(rejected.retryAfter()).contains(Duration.ofMillis(750));
    assertThat(rejected.resetAt()).contains(START.plusMillis(2500));
  }

  @Test
  void rejectedRequestKeepsRefilledTokens() {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter = newLimiter(10, 0, 2, clock);
    clock.advance(Duration.ofMillis(500));

    limiter.decide(new RateLimitRequest(2));

    assertThat(limiter.snapshot().scaledTokens()).isEqualTo(BigInteger.valueOf(1000));
    assertThat(limiter.snapshot().observedAt()).isEqualTo(START.plusMillis(500));
  }

  @Test
  void clockRollbackDoesNotRefillTwice() {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter = newLimiter(10, 0, 2, clock);
    clock.advance(Duration.ofMillis(500));
    limiter.decide(new RateLimitRequest(2));

    clock.set(START);
    RateLimitDecision decision = limiter.decide(new RateLimitRequest(2));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.remaining()).isEqualTo(1);
  }

  private static InMemoryTokenBucketRateLimiter newLimiter(
      long capacity, long initialTokens, long refillTokens, MutableClock clock) {
    return new InMemoryTokenBucketRateLimiter(
        new TokenBucketPolicy(
            POLICY_ID, VERSION, capacity, initialTokens, refillTokens, Duration.ofSeconds(1)),
        clock);
  }
}
