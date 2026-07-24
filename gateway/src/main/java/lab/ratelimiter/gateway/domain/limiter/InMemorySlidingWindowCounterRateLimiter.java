package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class InMemorySlidingWindowCounterRateLimiter
    extends AbstractInMemoryRateLimiter<SlidingWindowCounterPolicy, SlidingWindowCounterState> {

  public InMemorySlidingWindowCounterRateLimiter(SlidingWindowCounterPolicy policy, Clock clock) {
    this(policy, clock, TimeMath.now(clock));
  }

  private InMemorySlidingWindowCounterRateLimiter(
      SlidingWindowCounterPolicy policy, Clock clock, Instant initialTime) {
    super(
        policy,
        clock,
        new SlidingWindowCounterState(
            TimeMath.alignedWindowStart(initialTime, policy.window().toMillis()),
            0,
            0,
            initialTime));
  }

  @Override
  StateTransition<SlidingWindowCounterState> transition(
      SlidingWindowCounterState current, RateLimitRequest request, Instant now) {
    SlidingWindowCounterPolicy policy = policy();
    long windowMilliseconds = policy.window().toMillis();
    Instant windowStart = TimeMath.alignedWindowStart(now, windowMilliseconds);
    WindowCounts counts = rotate(current, windowStart, windowMilliseconds);
    long elapsed = Duration.between(windowStart, now).toMillis();
    BigInteger window = BigInteger.valueOf(windowMilliseconds);
    BigInteger estimated =
        estimatedUsage(counts.previous(), counts.current(), windowMilliseconds, elapsed);
    BigInteger capacity = BigInteger.valueOf(policy.limit()).multiply(window);
    long cost = request.cost().units();
    BigInteger scaledCost = BigInteger.valueOf(cost).multiply(window);
    boolean allowed = cost <= policy.limit() && estimated.add(scaledCost).compareTo(capacity) <= 0;
    long currentCount = allowed ? counts.current() + cost : counts.current();
    BigInteger updatedEstimate = allowed ? estimated.add(scaledCost) : estimated;
    long remaining = capacity.subtract(updatedEstimate).divide(window).longValueExact();
    SlidingWindowCounterState updatedState =
        new SlidingWindowCounterState(windowStart, counts.previous(), currentCount, now);
    Optional<Duration> retryAfter =
        retryAfter(
            allowed,
            cost,
            policy.limit(),
            counts.previous(),
            currentCount,
            windowMilliseconds,
            elapsed);
    Instant resetAt = resetAt(windowStart, policy.window(), counts.previous(), currentCount, now);
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            policy.limit(),
            remaining,
            retryAfter,
            Optional.of(resetAt),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new StateTransition<>(updatedState, decision);
  }

  private static WindowCounts rotate(
      SlidingWindowCounterState state, Instant newWindowStart, long windowMilliseconds) {
    if (newWindowStart.equals(state.currentWindowStart())) {
      return new WindowCounts(state.previousCount(), state.currentCount());
    }
    long elapsedWindows =
        Duration.between(state.currentWindowStart(), newWindowStart).toMillis()
            / windowMilliseconds;
    return elapsedWindows == 1 ? new WindowCounts(state.currentCount(), 0) : new WindowCounts(0, 0);
  }

  private static BigInteger estimatedUsage(
      long previous, long current, long windowMilliseconds, long elapsed) {
    BigInteger window = BigInteger.valueOf(windowMilliseconds);
    BigInteger currentPart = BigInteger.valueOf(current).multiply(window);
    BigInteger previousPart =
        BigInteger.valueOf(previous).multiply(BigInteger.valueOf(windowMilliseconds - elapsed));
    return currentPart.add(previousPart);
  }

  private static Optional<Duration> retryAfter(
      boolean allowed,
      long cost,
      long limit,
      long previous,
      long current,
      long windowMilliseconds,
      long elapsed) {
    if (allowed || cost > limit) {
      return Optional.empty();
    }
    BigInteger threshold =
        BigInteger.valueOf(limit - cost).multiply(BigInteger.valueOf(windowMilliseconds));
    BigInteger currentPart =
        BigInteger.valueOf(current).multiply(BigInteger.valueOf(windowMilliseconds));
    if (previous > 0 && currentPart.compareTo(threshold) <= 0) {
      long maximumPreviousWeight =
          threshold.subtract(currentPart).divide(BigInteger.valueOf(previous)).longValueExact();
      long wait = windowMilliseconds - elapsed - maximumPreviousWeight;
      if (wait <= windowMilliseconds - elapsed) {
        return Optional.of(Duration.ofMillis(Math.max(0, wait)));
      }
    }

    long toBoundary = windowMilliseconds - elapsed;
    if (current <= limit - cost) {
      return Optional.of(Duration.ofMillis(toBoundary));
    }
    long maximumWeight = threshold.divide(BigInteger.valueOf(current)).longValueExact();
    long nextWindowWait = windowMilliseconds - maximumWeight;
    return Optional.of(Duration.ofMillis(Math.addExact(toBoundary, nextWindowWait)));
  }

  private static Instant resetAt(
      Instant windowStart, Duration window, long previous, long current, Instant now) {
    if (current > 0) {
      return windowStart.plus(window).plus(window);
    }
    if (previous > 0) {
      return windowStart.plus(window);
    }
    return now;
  }

  private record WindowCounts(long previous, long current) {}
}
