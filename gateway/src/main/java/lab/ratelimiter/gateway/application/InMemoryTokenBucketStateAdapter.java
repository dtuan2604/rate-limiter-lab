package lab.ratelimiter.gateway.application;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lab.ratelimiter.gateway.domain.limiter.InMemoryTokenBucketRateLimiter;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.state.redis.TokenBucketParameters;
import reactor.core.publisher.Mono;

public final class InMemoryTokenBucketStateAdapter implements TokenBucketStateAdapter {

  private final Clock clock;
  private final ConcurrentMap<StateKey, InMemoryTokenBucketRateLimiter> limiters =
      new ConcurrentHashMap<>();

  public InMemoryTokenBucketStateAdapter(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Mono<TokenBucketStateResult> decide(
      TokenBucketPolicy policy,
      long requestCost,
      Instant activationTime,
      LimiterIdentity identity) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(activationTime, "activationTime");
    Objects.requireNonNull(identity, "identity");
    TokenBucketParameters.ofTokens(
        policy.capacity(),
        policy.initialTokens(),
        policy.refillTokens(),
        policy.refillPeriod().toMillis(),
        requestCost,
        activationTime.toEpochMilli());
    StateKey key =
        new StateKey(policy.policyId().value(), policy.policyVersion().value(), identity.digest());
    InMemoryTokenBucketRateLimiter limiter =
        limiters.computeIfAbsent(key, ignored -> new InMemoryTokenBucketRateLimiter(policy, clock));
    RateLimitDecision decision = limiter.decide(new RateLimitRequest(requestCost));
    long remainingScaled =
        limiter
            .snapshot()
            .scaledTokens()
            .multiply(BigInteger.valueOf(TokenBucketParameters.SCALE))
            .divide(BigInteger.valueOf(policy.refillPeriod().toMillis()))
            .longValueExact();
    Instant now = clock.instant();
    Duration resetAfter =
        decision
            .resetAt()
            .map(
                reset ->
                    Duration.between(now, reset).isNegative()
                        ? Duration.ZERO
                        : Duration.between(now, reset))
            .orElse(Duration.ZERO);
    Duration retryAfter = decision.retryAfter().orElse(Duration.ZERO);
    return Mono.just(
        new TokenBucketStateResult(
            decision,
            remainingScaled,
            Math.multiplyExact(requestCost, TokenBucketParameters.SCALE),
            Math.multiplyExact(policy.refillTokens(), TokenBucketParameters.SCALE),
            policy.refillPeriod(),
            retryAfter,
            resetAfter,
            now,
            resetAfter,
            0,
            false,
            StateBackend.IN_MEMORY,
            RedisOutcome.NOT_APPLICABLE));
  }

  private record StateKey(String policyId, long policyVersion, String identityDigest) {}
}
