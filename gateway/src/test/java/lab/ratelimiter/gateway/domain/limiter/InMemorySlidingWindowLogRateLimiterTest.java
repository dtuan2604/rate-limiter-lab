package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySlidingWindowLogRateLimiterTest {

  private static final PolicyId POLICY_ID = new PolicyId("sliding-log");
  private static final PolicyVersion VERSION = new PolicyVersion(4);
  private static final Duration WINDOW = Duration.ofSeconds(1);
  private static final Instant START = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void expiresAnEntryAtTheExactTrailingWindowBoundary() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowLogRateLimiter limiter = newLimiter(2, clock);
    limiter.decide(new RateLimitRequest(2));

    clock.advance(Duration.ofMillis(999));
    assertThat(limiter.decide(new RateLimitRequest(1)).allowed()).isFalse();

    clock.advance(Duration.ofMillis(1));
    RateLimitDecision boundary = limiter.decide(new RateLimitRequest(1));

    assertThat(boundary.allowed()).isTrue();
    assertThat(boundary.remaining()).isEqualTo(1);
    assertThat(limiter.snapshot().entries()).hasSize(1);
  }

  @Test
  void computesRetryFromEnoughOldestCostAndResetFromNewestEntry() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowLogRateLimiter limiter = newLimiter(3, clock);
    limiter.decide(new RateLimitRequest(2));
    clock.advance(Duration.ofMillis(100));
    limiter.decide(new RateLimitRequest(1));
    clock.advance(Duration.ofMillis(100));

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(2));

    assertThat(rejected.retryAfter()).contains(Duration.ofMillis(800));
    assertThat(rejected.resetAt()).contains(START.plusMillis(1100));
  }

  @Test
  void rejectedRequestPersistsExpiryCleanupButAddsNoEntry() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowLogRateLimiter limiter = newLimiter(2, clock);
    limiter.decide(new RateLimitRequest(2));
    clock.advance(WINDOW);

    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(3));

    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.remaining()).isEqualTo(2);
    assertThat(limiter.snapshot().entries()).isEmpty();
    assertThat(limiter.snapshot().observedAt()).isEqualTo(START.plus(WINDOW));
  }

  @Test
  void oneEntryRepresentsOneAcceptedRequestCost() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowLogRateLimiter limiter = newLimiter(5, clock);

    limiter.decide(new RateLimitRequest(3));

    assertThat(limiter.snapshot().entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.cost()).isEqualTo(new RequestCost(3)));
  }

  @Test
  void clockRollbackCannotResurrectExpiredUsageOrReorderEntries() {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowLogRateLimiter limiter = newLimiter(2, clock);
    limiter.decide(new RateLimitRequest(1));
    clock.advance(WINDOW);
    limiter.decide(new RateLimitRequest(1));

    clock.set(START.minusSeconds(10));
    limiter.decide(new RateLimitRequest(1));

    assertThat(limiter.snapshot().entries())
        .extracting(SlidingWindowLogEntry::timestamp)
        .containsOnly(START.plus(WINDOW));
    assertThat(limiter.snapshot().entries()).hasSize(2);
  }

  private static InMemorySlidingWindowLogRateLimiter newLimiter(long limit, MutableClock clock) {
    SlidingWindowLogPolicy policy =
        new SlidingWindowLogPolicy(POLICY_ID, VERSION, limit, WINDOW, Math.toIntExact(limit));
    return new InMemorySlidingWindowLogRateLimiter(policy, clock);
  }
}
