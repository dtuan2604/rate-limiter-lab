package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class RateLimiterProperties {

  private static final PolicyId POLICY_ID = new PolicyId("property-policy");
  private static final PolicyVersion VERSION = new PolicyVersion(11);
  private static final Instant START = Instant.parse("2026-07-24T12:00:00Z");
  private static final Duration WINDOW = Duration.ofSeconds(1);

  @Property(tries = 200, seed = "1010101")
  void fixedWindowNeverChargesRejectedCost(
      @ForAll @Size(max = 80) List<@IntRange(min = 1, max = 5) Integer> costs) {
    MutableClock clock = new MutableClock(START);
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            new FixedWindowPolicy(POLICY_ID, VERSION, 25, WINDOW), clock);
    long accepted = 0;

    for (int cost : costs) {
      RateLimitDecision decision = limiter.decide(new RateLimitRequest(cost));
      if (decision.allowed()) {
        accepted += cost;
      }
      assertThat(accepted).isBetween(0L, 25L);
      assertThat(decision.remaining()).isEqualTo(25 - accepted);
      assertThat(limiter.snapshot().used()).isEqualTo(accepted);
    }
  }

  @Property(tries = 200, seed = "2020202")
  void slidingLogContainsOnlyBoundedActiveAcceptedUsage(
      @ForAll @Size(max = 80) List<@IntRange(min = 1, max = 4) Integer> costs) {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowLogRateLimiter limiter =
        new InMemorySlidingWindowLogRateLimiter(
            new SlidingWindowLogPolicy(POLICY_ID, VERSION, 25, WINDOW, 25), clock);

    for (int cost : costs) {
      clock.advance(Duration.ofMillis(cost * 37L));
      RateLimitDecision decision = limiter.decide(new RateLimitRequest(cost));
      SlidingWindowLogState state = limiter.snapshot();
      long used = state.entries().stream().mapToLong(entry -> entry.cost().units()).sum();
      Instant cutoff = state.observedAt().minus(WINDOW);

      assertThat(state.entries()).allMatch(entry -> entry.timestamp().isAfter(cutoff));
      assertThat(state.entries()).hasSizeLessThanOrEqualTo(25);
      assertThat(used).isBetween(0L, 25L);
      assertThat(decision.remaining()).isEqualTo(25 - used);
    }
  }

  @Property(tries = 200, seed = "3030303")
  void slidingCounterCountsAndRemainingStayBounded(
      @ForAll @Size(max = 80) List<@IntRange(min = 1, max = 4) Integer> costs) {
    MutableClock clock = new MutableClock(START);
    InMemorySlidingWindowCounterRateLimiter limiter =
        new InMemorySlidingWindowCounterRateLimiter(
            new SlidingWindowCounterPolicy(POLICY_ID, VERSION, 25, WINDOW), clock);

    for (int cost : costs) {
      clock.advance(Duration.ofMillis(cost * 41L));
      RateLimitDecision decision = limiter.decide(new RateLimitRequest(cost));
      SlidingWindowCounterState state = limiter.snapshot();

      assertThat(state.previousCount()).isBetween(0L, 25L);
      assertThat(state.currentCount()).isBetween(0L, 25L);
      assertThat(decision.remaining()).isBetween(0L, 25L);
    }
  }

  @Property(tries = 200, seed = "4040404")
  void tokenBalanceAlwaysStaysWithinCapacity(
      @ForAll @Size(max = 80) List<@IntRange(min = 1, max = 6) Integer> costs) {
    MutableClock clock = new MutableClock(START);
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            new TokenBucketPolicy(POLICY_ID, VERSION, 25, 7, 9, WINDOW), clock);
    BigInteger scaledCapacity = BigInteger.valueOf(25000);

    for (int cost : costs) {
      clock.advance(Duration.ofMillis(cost * 43L));
      RateLimitDecision decision = limiter.decide(new RateLimitRequest(cost));
      BigInteger balance = limiter.snapshot().scaledTokens();

      assertThat(balance).isBetween(BigInteger.ZERO, scaledCapacity);
      assertThat(decision.remaining())
          .isEqualTo(balance.divide(BigInteger.valueOf(1000)).longValue());
    }
  }

  @Property(tries = 200, seed = "5050505")
  void leakyBacklogAlwaysStaysWithinCapacity(
      @ForAll @Size(max = 80) List<@IntRange(min = 1, max = 6) Integer> costs) {
    MutableClock clock = new MutableClock(START);
    InMemoryLeakyBucketRateLimiter limiter =
        new InMemoryLeakyBucketRateLimiter(
            new LeakyBucketPolicy(POLICY_ID, VERSION, 25, 9, WINDOW), clock);
    BigInteger scaledCapacity = BigInteger.valueOf(25000);

    for (int cost : costs) {
      clock.advance(Duration.ofMillis(cost * 47L));
      RateLimitDecision decision = limiter.decide(new RateLimitRequest(cost));
      BigInteger level = limiter.snapshot().scaledLevel();

      assertThat(level).isBetween(BigInteger.ZERO, scaledCapacity);
      assertThat(decision.remaining())
          .isEqualTo(scaledCapacity.subtract(level).divide(BigInteger.valueOf(1000)).longValue());
    }
  }
}
