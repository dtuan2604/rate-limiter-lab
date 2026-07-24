package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class InMemoryLeakyBucketRateLimiterTest {

  private static final PolicyId POLICY_ID = new PolicyId("leaky");
  private static final PolicyVersion VERSION = new PolicyVersion(8);
  private static final Instant START = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void admittedCostImmediatelyAddsToBacklog() {
    MutableClock clock = new MutableClock(START);
    InMemoryLeakyBucketRateLimiter limiter = newLimiter(10, 4, clock);

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(3));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(7);
    assertThat(limiter.snapshot().scaledLevel()).isEqualTo(BigInteger.valueOf(3000));
  }

  @Test
  void drainsContinuouslyAtMillisecondPrecision() {
    MutableClock clock = new MutableClock(START);
    InMemoryLeakyBucketRateLimiter limiter = newLimiter(10, 4, clock);
    limiter.decide(new RateLimitRequest(10));
    clock.advance(Duration.ofMillis(250));

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(1));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isZero();
    assertThat(limiter.snapshot().scaledLevel()).isEqualTo(BigInteger.valueOf(10000));
  }

  @Test
  void computesSpaceRetryAndEmptyResetSeparately() {
    MutableClock clock = new MutableClock(START);
    InMemoryLeakyBucketRateLimiter limiter = newLimiter(10, 4, clock);
    limiter.decide(new RateLimitRequest(10));

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(2));

    assertThat(rejected.retryAfter()).contains(Duration.ofMillis(500));
    assertThat(rejected.resetAt()).contains(START.plusMillis(2500));
  }

  @Test
  void rejectedRequestKeepsDrainageButAddsNoBacklog() {
    MutableClock clock = new MutableClock(START);
    InMemoryLeakyBucketRateLimiter limiter = newLimiter(10, 4, clock);
    limiter.decide(new RateLimitRequest(10));
    clock.advance(Duration.ofMillis(250));

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(2));

    assertThat(rejected.allowed()).isFalse();
    assertThat(limiter.snapshot().scaledLevel()).isEqualTo(BigInteger.valueOf(9000));
    assertThat(limiter.snapshot().observedAt()).isEqualTo(START.plusMillis(250));
  }

  @Test
  void clockRollbackCannotDrainTheSameIntervalTwice() {
    MutableClock clock = new MutableClock(START);
    InMemoryLeakyBucketRateLimiter limiter = newLimiter(10, 2, clock);
    limiter.decide(new RateLimitRequest(10));
    clock.advance(Duration.ofMillis(500));
    limiter.decide(new RateLimitRequest(2));

    clock.set(START);
    RateLimitDecision decision = limiter.decide(new RateLimitRequest(2));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.remaining()).isEqualTo(1);
  }

  @Test
  void stateContainsNoRequestQueueOrDeliveryOwnership() {
    assertThat(
            Arrays.stream(LeakyBucketState.class.getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly("scaledLevel", "observedAt");
  }

  private static InMemoryLeakyBucketRateLimiter newLimiter(
      long capacity, long leakUnits, MutableClock clock) {
    return new InMemoryLeakyBucketRateLimiter(
        new LeakyBucketPolicy(POLICY_ID, VERSION, capacity, leakUnits, Duration.ofSeconds(1)),
        clock);
  }
}
