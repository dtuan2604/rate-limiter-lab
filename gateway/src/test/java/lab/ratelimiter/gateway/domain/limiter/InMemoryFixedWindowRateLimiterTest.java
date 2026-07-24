package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryFixedWindowRateLimiterTest {

  private static final PolicyId POLICY_ID = new PolicyId("fixed");
  private static final PolicyVersion VERSION = new PolicyVersion(2);
  private static final Duration WINDOW = Duration.ofSeconds(1);

  @Test
  void rotatesAtTheExactEpochAlignedBoundary() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-24T12:00:00.999Z"));
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            new FixedWindowPolicy(POLICY_ID, VERSION, 2, WINDOW), clock);
    limiter.decide(new RateLimitRequest(2));

    clock.advance(Duration.ofMillis(1));
    RateLimitDecision decision = limiter.decide(new RateLimitRequest(2));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isZero();
    assertThat(limiter.snapshot().windowStart()).isEqualTo(Instant.parse("2026-07-24T12:00:01Z"));
  }

  @Test
  void exposesTheIntentionalBoundaryBurst() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-24T12:00:00.999Z"));
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            new FixedWindowPolicy(POLICY_ID, VERSION, 5, WINDOW), clock);

    RateLimitDecision before = limiter.decide(new RateLimitRequest(5));
    clock.advance(Duration.ofMillis(1));
    RateLimitDecision after = limiter.decide(new RateLimitRequest(5));

    assertThat(before.allowed()).isTrue();
    assertThat(after.allowed()).isTrue();
  }

  @Test
  void computesResetAndRetryFromTheWindowBoundary() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-24T12:00:00.250Z"));
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            new FixedWindowPolicy(POLICY_ID, VERSION, 2, WINDOW), clock);
    limiter.decide(new RateLimitRequest(2));

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(1));

    assertThat(rejected.retryAfter()).contains(Duration.ofMillis(750));
    assertThat(rejected.resetAt()).contains(Instant.parse("2026-07-24T12:00:01Z"));
  }

  @Test
  void clampsClockRollbackToTheLastObservedTime() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-24T12:00:00.500Z"));
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            new FixedWindowPolicy(POLICY_ID, VERSION, 1, WINDOW), clock);
    limiter.decide(new RateLimitRequest(1));

    clock.set(Instant.parse("2026-07-24T11:59:59Z"));
    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(1));

    assertThat(rejected.allowed()).isFalse();
    assertThat(limiter.snapshot().observedAt())
        .isEqualTo(Instant.parse("2026-07-24T12:00:00.500Z"));
  }
}
