package lab.ratelimiter.gateway.application;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lab.ratelimiter.gateway.domain.limiter.InMemoryFixedWindowRateLimiter;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;

public final class RateLimitService {

  private final Clock clock;
  private final ConcurrentMap<LimiterKey, InMemoryFixedWindowRateLimiter> limiters =
      new ConcurrentHashMap<>();

  public RateLimitService(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public RateLimitDecision evaluate(CompiledPolicy compiledPolicy, LimiterIdentity identity) {
    Objects.requireNonNull(compiledPolicy, "compiledPolicy");
    Objects.requireNonNull(identity, "identity");
    LimiterKey key =
        new LimiterKey(
            compiledPolicy.policy().policyId(), compiledPolicy.policy().policyVersion(), identity);
    InMemoryFixedWindowRateLimiter limiter =
        limiters.computeIfAbsent(
            key, ignored -> new InMemoryFixedWindowRateLimiter(compiledPolicy.policy(), clock));
    return limiter.decide(new RateLimitRequest(1));
  }

  private record LimiterKey(
      PolicyId policyId, PolicyVersion policyVersion, LimiterIdentity identity) {}
}
