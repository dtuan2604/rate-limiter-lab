package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySlidingWindowCounterRateLimiterTest {

  private static final PolicyId POLICY_ID = new PolicyId("sliding-counter");
  private static final PolicyVersion VERSION = new PolicyVersion(5);
  private static final Duration WINDOW = Duration.ofSeconds(1);
  private static final Instant START = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void weightsThePreviousWindowByItsRemainingFraction() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowCounterRateLimiter limiter = newLimiter(10, clock);
    limiter.decide(new RateLimitRequest(10));
    clock.advance(WINDOW.plusMillis(500));

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(5));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isZero();
    assertThat(limiter.snapshot().previousCount()).isEqualTo(10);
    assertThat(limiter.snapshot().currentCount()).isEqualTo(5);
  }

  @Test
  void rotatesAtOneBoundaryAndClearsAfterTwoSkippedWindows() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowCounterRateLimiter limiter = newLimiter(4, clock);
    limiter.decide(new RateLimitRequest(4));

    clock.advance(WINDOW);
    assertThat(limiter.decide(new RateLimitRequest(1)).allowed()).isFalse();
    assertThat(limiter.snapshot().previousCount()).isEqualTo(4);
    assertThat(limiter.snapshot().currentCount()).isZero();

    clock.advance(WINDOW);
    assertThat(limiter.decide(new RateLimitRequest(4)).allowed()).isTrue();
    assertThat(limiter.snapshot().previousCount()).isZero();
  }

  @Test
  void reportsConservativeWholeUnitRemainingForFractionalUsage() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowCounterRateLimiter limiter = newLimiter(10, clock);
    limiter.decide(new RateLimitRequest(3));
    clock.advance(WINDOW.plusMillis(1));

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(11));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.remaining()).isEqualTo(7);
  }

  @Test
  void computesRetryAndFullResetForWeightedPreviousUsage() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowCounterRateLimiter limiter = newLimiter(10, clock);
    limiter.decide(new RateLimitRequest(10));
    clock.advance(WINDOW);

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(1));

    assertThat(rejected.retryAfter()).contains(Duration.ofMillis(100));
    assertThat(rejected.resetAt()).contains(START.plusSeconds(2));
  }

  @Test
  void rejectedRequestPersistsRotationWithoutIncrementingCurrentCount() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowCounterRateLimiter limiter = newLimiter(2, clock);
    limiter.decide(new RateLimitRequest(2));
    clock.advance(WINDOW);

    limiter.decide(new RateLimitRequest(1));

    assertThat(limiter.snapshot().previousCount()).isEqualTo(2);
    assertThat(limiter.snapshot().currentCount()).isZero();
  }

  @Test
  void clockRollbackKeepsTheLastObservedWindow() {
    MutableClock clock = new MutableClock(START.plusMillis(500));
    InMemorySlidingWindowCounterRateLimiter limiter = newLimiter(2, clock);
    limiter.decide(new RateLimitRequest(2));

    clock.set(START.minusSeconds(2));
    RateLimitDecision decision = limiter.decide(new RateLimitRequest(1));

    assertThat(decision.allowed()).isFalse();
    assertThat(limiter.snapshot().observedAt()).isEqualTo(START.plusMillis(500));
  }

  private static InMemorySlidingWindowCounterRateLimiter newLimiter(
      long limit, MutableClock clock) {
    return new InMemorySlidingWindowCounterRateLimiter(
        new SlidingWindowCounterPolicy(POLICY_ID, VERSION, limit, WINDOW), clock);
  }
}
