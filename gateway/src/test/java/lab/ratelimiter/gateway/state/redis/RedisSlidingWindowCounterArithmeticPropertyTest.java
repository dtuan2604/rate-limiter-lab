package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class RedisSlidingWindowCounterArithmeticPropertyTest {

  @Property(tries = 1_000)
  void boundedArithmeticAndRetryMatchBigIntegerAndBruteForceReferences(
      @ForAll @IntRange(min = 1, max = 1_000) int limit,
      @ForAll @IntRange(min = 1, max = 10_000) int window,
      @ForAll @IntRange(min = 1, max = 1_000) int costCandidate,
      @ForAll @IntRange(min = 0, max = 1_000) int currentCandidate,
      @ForAll @IntRange(min = 0, max = 1_000) int previousCandidate,
      @ForAll @IntRange(min = 0, max = 9_999) int elapsedCandidate) {
    long cost = Math.min(costCandidate, limit);
    long current = Math.min(currentCandidate, limit);
    long previous = Math.min(previousCandidate, limit);
    long elapsed = Math.min(elapsedCandidate, window - 1L);
    SlidingCounterParameters parameters = new SlidingCounterParameters(limit, window, cost);
    SlidingCounterState state = new SlidingCounterState(0, current, previous);

    SlidingCounterTransition actual =
        RedisSlidingWindowCounterArithmetic.decide(parameters, state, elapsed);
    Reference reference = reference(limit, window, cost, current, previous, elapsed);

    assertThat(actual.allowed()).isEqualTo(reference.allowed());
    assertThat(actual.weightedNumerator()).isEqualTo(reference.postNumerator());
    assertThat(actual.weightedEstimate()).isEqualTo(reference.estimate());
    assertThat(actual.remainingCapacity()).isEqualTo(reference.remaining());
    if (!actual.allowed()) {
      assertThat(actual.retryAfterMilliseconds())
          .isEqualTo(bruteForceRetry(limit, window, cost, state, elapsed));
    }
  }

  private static Reference reference(
      long limit, long window, long cost, long current, long previous, long elapsed) {
    BigInteger bigWindow = BigInteger.valueOf(window);
    BigInteger numerator =
        BigInteger.valueOf(current)
            .multiply(bigWindow)
            .add(BigInteger.valueOf(previous).multiply(BigInteger.valueOf(window - elapsed)));
    BigInteger scaledCost = BigInteger.valueOf(cost).multiply(bigWindow);
    BigInteger scaledLimit = BigInteger.valueOf(limit).multiply(bigWindow);
    boolean allowed = numerator.add(scaledCost).compareTo(scaledLimit) <= 0;
    BigInteger post = allowed ? numerator.add(scaledCost) : numerator;
    long estimate = ceiling(post, bigWindow).longValueExact();
    long remaining =
        post.compareTo(scaledLimit) >= 0
            ? 0
            : scaledLimit.subtract(post).divide(bigWindow).longValueExact();
    return new Reference(allowed, post.longValueExact(), estimate, remaining);
  }

  private static long bruteForceRetry(
      long limit, long window, long cost, SlidingCounterState stored, long rejectedAt) {
    for (long delay = 1; delay <= 2 * window; delay++) {
      long now = rejectedAt + delay;
      long id = now / window;
      long elapsed = now % window;
      long current;
      long previous;
      if (id == stored.windowId()) {
        current = stored.currentCount();
        previous = stored.previousCount();
      } else if (id == stored.windowId() + 1) {
        current = 0;
        previous = stored.currentCount();
      } else {
        current = 0;
        previous = 0;
      }
      if (reference(limit, window, cost, current, previous, elapsed).allowed()) {
        return delay;
      }
    }
    throw new AssertionError("bounded Sliding Counter retry was not found");
  }

  private static BigInteger ceiling(BigInteger numerator, BigInteger denominator) {
    if (numerator.signum() == 0) {
      return BigInteger.ZERO;
    }
    return numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator);
  }

  private record Reference(boolean allowed, long postNumerator, long estimate, long remaining) {}
}
