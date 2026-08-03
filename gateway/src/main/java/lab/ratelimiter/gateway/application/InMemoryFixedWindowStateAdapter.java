package lab.ratelimiter.gateway.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.InMemoryFixedWindowRateLimiter;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import reactor.core.publisher.Mono;

public final class InMemoryFixedWindowStateAdapter implements FixedWindowStateAdapter {

  private final Clock clock;
  private final ConcurrentMap<LimiterKey, InMemoryFixedWindowRateLimiter> limiters =
      new ConcurrentHashMap<>();

  public InMemoryFixedWindowStateAdapter(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Mono<FixedWindowStateResult> decide(
      FixedWindowPolicy policy, LimiterIdentity identity, RateLimitRequest request) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(request, "request");
    return Mono.fromSupplier(
        () -> {
          LimiterKey key = new LimiterKey(policy.policyId(), policy.policyVersion(), identity);
          InMemoryFixedWindowRateLimiter limiter =
              limiters.computeIfAbsent(
                  key, ignored -> new InMemoryFixedWindowRateLimiter(policy, clock));
          RateLimitDecision decision = limiter.decide(request);
          Instant now = clock.instant();
          Duration resetAfter =
              decision
                  .resetAt()
                  .map(reset -> reset.isAfter(now) ? Duration.between(now, reset) : Duration.ZERO)
                  .orElse(Duration.ZERO);
          return new FixedWindowStateResult(
              decision,
              policy.limit() - decision.remaining(),
              resetAfter,
              StateBackend.IN_MEMORY,
              RedisOutcome.NOT_APPLICABLE);
        });
  }

  private record LimiterKey(
      PolicyId policyId, PolicyVersion policyVersion, LimiterIdentity identity) {}
}
