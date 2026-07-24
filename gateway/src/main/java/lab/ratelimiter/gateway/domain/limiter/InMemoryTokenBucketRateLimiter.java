package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class InMemoryTokenBucketRateLimiter
    extends AbstractInMemoryRateLimiter<TokenBucketPolicy, TokenBucketState> {

  public InMemoryTokenBucketRateLimiter(TokenBucketPolicy policy, Clock clock) {
    this(policy, clock, TimeMath.now(clock));
  }

  private InMemoryTokenBucketRateLimiter(
      TokenBucketPolicy policy, Clock clock, Instant initialTime) {
    super(
        policy,
        clock,
        new TokenBucketState(
            ScaledRateMath.scale(policy.initialTokens(), policy.refillPeriod().toMillis()),
            initialTime));
  }

  @Override
  StateTransition<TokenBucketState> transition(
      TokenBucketState current, RateLimitRequest request, Instant now) {
    TokenBucketPolicy policy = policy();
    long periodMilliseconds = policy.refillPeriod().toMillis();
    BigInteger capacity = ScaledRateMath.scale(policy.capacity(), periodMilliseconds);
    long elapsed = Duration.between(current.observedAt(), now).toMillis();
    BigInteger refilled =
        current
            .scaledTokens()
            .add(BigInteger.valueOf(elapsed).multiply(BigInteger.valueOf(policy.refillTokens())))
            .min(capacity);
    long cost = request.cost().units();
    BigInteger scaledCost = ScaledRateMath.scale(cost, periodMilliseconds);
    boolean allowed = scaledCost.compareTo(refilled) <= 0;
    BigInteger updatedTokens = allowed ? refilled.subtract(scaledCost) : refilled;
    long remaining = updatedTokens.divide(BigInteger.valueOf(periodMilliseconds)).longValueExact();
    TokenBucketState updatedState = new TokenBucketState(updatedTokens, now);
    Optional<Duration> retryAfter =
        retryAfter(
            allowed, cost, policy.capacity(), scaledCost, updatedTokens, policy.refillTokens());
    long resetMilliseconds =
        ScaledRateMath.ceilDivide(capacity.subtract(updatedTokens), policy.refillTokens());
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
      long capacity,
      BigInteger scaledCost,
      BigInteger available,
      long refillTokens) {
    if (allowed || cost > capacity) {
      return Optional.empty();
    }
    long waitMilliseconds = ScaledRateMath.ceilDivide(scaledCost.subtract(available), refillTokens);
    return Optional.of(Duration.ofMillis(waitMilliseconds));
  }
}
