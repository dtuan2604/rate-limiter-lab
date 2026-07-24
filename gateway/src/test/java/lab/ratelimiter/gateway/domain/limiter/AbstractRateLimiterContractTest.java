package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

abstract class AbstractRateLimiterContractTest {

  static final PolicyId POLICY_ID = new PolicyId("contract-policy");
  static final PolicyVersion POLICY_VERSION = new PolicyVersion(7);
  static final Instant START = Instant.parse("2026-07-24T12:00:00Z");

  abstract RateLimiter<? extends RateLimitPolicy, ? extends RateLimitState> newLimiter(
      long limit, MutableClock clock);

  abstract Duration recoveryDuration();

  @Test
  void allowsFirstRequestAndReturnsPolicyMetadata() {
    MutableClock clock = new MutableClock(START);
    RateLimiter<? extends RateLimitPolicy, ? extends RateLimitState> limiter = newLimiter(5, clock);

    RateLimitDecision decision = limiter.decide(new RateLimitRequest(1));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.limit()).isEqualTo(5);
    assertThat(decision.remaining()).isEqualTo(4);
    assertThat(decision.policyId()).isEqualTo(POLICY_ID);
    assertThat(decision.policyVersion()).isEqualTo(POLICY_VERSION);
    assertThat(decision.retryAfter()).isEmpty();
    assertThat(decision.resetAt()).isPresent();
  }

  @Test
  void allowsExactlyTheLimitThenRejectsWithoutNegativeRemaining() {
    MutableClock clock = new MutableClock(START);
    RateLimiter<? extends RateLimitPolicy, ? extends RateLimitState> limiter = newLimiter(5, clock);

    RateLimitDecision boundary = limiter.decide(new RateLimitRequest(5));
    RateLimitState stateBeforeRejection = limiter.snapshot();
    RateLimitDecision rejected = limiter.decide(new RateLimitRequest(1));

    assertThat(boundary.allowed()).isTrue();
    assertThat(boundary.remaining()).isZero();
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.remaining()).isZero();
    assertThat(rejected.retryAfter()).isPresent();
    assertThat(limiter.snapshot()).isEqualTo(stateBeforeRejection);
  }

  @Test
  void supportsCostsGreaterThanOne() {
    MutableClock clock = new MutableClock(START);
    RateLimitDecision decision = newLimiter(5, clock).decide(new RateLimitRequest(3));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(2);
  }

  @Test
  void rejectsPermanentlyOversizeCostWithoutRetryAfter() {
    MutableClock clock = new MutableClock(START);
    RateLimitDecision decision = newLimiter(5, clock).decide(new RateLimitRequest(6));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.remaining()).isEqualTo(5);
    assertThat(decision.retryAfter()).isEmpty();
  }

  @Test
  void recoversUsingInjectedTimeWithoutSleeping() {
    MutableClock clock = new MutableClock(START);
    RateLimiter<? extends RateLimitPolicy, ? extends RateLimitState> limiter = newLimiter(5, clock);
    limiter.decide(new RateLimitRequest(5));
    assertThat(limiter.decide(new RateLimitRequest(1)).allowed()).isFalse();

    clock.advance(recoveryDuration());

    assertThat(limiter.decide(new RateLimitRequest(1)).allowed()).isTrue();
  }

  @Test
  void linearizesConcurrentCallsWithinOneInstance() throws Exception {
    MutableClock clock = new MutableClock(START);
    RateLimiter<? extends RateLimitPolicy, ? extends RateLimitState> limiter =
        newLimiter(50, clock);
    CountDownLatch ready = new CountDownLatch(100);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> calls =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(
                ignored ->
                    (Callable<Boolean>)
                        () -> {
                          ready.countDown();
                          start.await();
                          return limiter.decide(new RateLimitRequest(1)).allowed();
                        })
            .toList();

    try (var executor = Executors.newFixedThreadPool(100)) {
      var futures = calls.stream().map(executor::submit).toList();
      ready.await();
      start.countDown();
      long allowed = 0;
      for (var future : futures) {
        if (future.get()) {
          allowed++;
        }
      }
      assertThat(allowed).isEqualTo(50);
    }
  }
}
