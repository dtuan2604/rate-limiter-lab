package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class InMemoryLeakyBucketRateLimiter
    extends AbstractInMemoryRateLimiter<LeakyBucketPolicy, LeakyBucketState> {

  public InMemoryLeakyBucketRateLimiter(LeakyBucketPolicy policy, Clock clock) {
    this(policy, clock, TimeMath.now(clock));
  }

  private InMemoryLeakyBucketRateLimiter(
      LeakyBucketPolicy policy, Clock clock, Instant initialTime) {
    super(policy, clock, new LeakyBucketState(BigInteger.ZERO, initialTime));
  }

  @Override
  StateTransition<LeakyBucketState> transition(
      LeakyBucketState current, RateLimitRequest request, Instant now) {
    LeakyBucketPolicy policy = policy();
    long periodMilliseconds = policy.leakPeriod().toMillis();
    BigInteger capacity = ScaledRateMath.scale(policy.capacity(), periodMilliseconds);
    long elapsed = Duration.between(current.observedAt(), now).toMillis();
    BigInteger drained =
        BigInteger.valueOf(elapsed).multiply(BigInteger.valueOf(policy.leakUnits()));
    BigInteger level = current.scaledLevel().subtract(drained).max(BigInteger.ZERO);
    long cost = request.cost().units();
    BigInteger scaledCost = ScaledRateMath.scale(cost, periodMilliseconds);
    boolean allowed = cost <= policy.capacity() && level.add(scaledCost).compareTo(capacity) <= 0;
    BigInteger updatedLevel = allowed ? level.add(scaledCost) : level;
    long remaining =
        capacity
            .subtract(updatedLevel)
            .divide(BigInteger.valueOf(periodMilliseconds))
            .longValueExact();
    LeakyBucketState updatedState = new LeakyBucketState(updatedLevel, now);
    Optional<Duration> retryAfter =
        retryAfter(
            allowed, cost, policy.capacity(), level, scaledCost, capacity, policy.leakUnits());
    long resetMilliseconds = ScaledRateMath.ceilDivide(updatedLevel, policy.leakUnits());
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            policy.capacity(),
            remaining,
            retryAfter,
            Optional.of(now.plusMillis(resetMilliseconds)),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new StateTransition<>(updatedState, decision);
  }

  private static Optional<Duration> retryAfter(
      boolean allowed,
      long cost,
      long capacityUnits,
      BigInteger level,
      BigInteger scaledCost,
      BigInteger capacity,
      long leakUnits) {
    if (allowed || cost > capacityUnits) {
      return Optional.empty();
    }
    BigInteger spaceNeeded = level.add(scaledCost).subtract(capacity);
    long waitMilliseconds = ScaledRateMath.ceilDivide(spaceNeeded, leakUnits);
    return Optional.of(Duration.ofMillis(waitMilliseconds));
  }
}
