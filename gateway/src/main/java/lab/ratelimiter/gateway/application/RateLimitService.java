package lab.ratelimiter.gateway.application;

import java.util.Objects;
import java.util.Optional;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.state.redis.RedisStateException;
import reactor.core.publisher.Mono;

public final class RateLimitService {

  private final FixedWindowStateAdapter stateAdapter;
  private final FailureMode legacyFailureMode;

  public RateLimitService(FixedWindowStateAdapter stateAdapter) {
    this.stateAdapter = Objects.requireNonNull(stateAdapter, "stateAdapter");
    this.legacyFailureMode = null;
  }

  public RateLimitService(FixedWindowStateAdapter stateAdapter, FailureMode failureMode) {
    this.stateAdapter = Objects.requireNonNull(stateAdapter, "stateAdapter");
    this.legacyFailureMode = Objects.requireNonNull(failureMode, "failureMode");
  }

  public Mono<RateLimitEvaluation> evaluate(
      CompiledPolicy compiledPolicy, LimiterIdentity identity) {
    Objects.requireNonNull(compiledPolicy, "compiledPolicy");
    Objects.requireNonNull(identity, "identity");
    FailureMode effectiveFailureMode =
        legacyFailureMode == null ? compiledPolicy.failureMode() : legacyFailureMode;
    return stateAdapter
        .decide(compiledPolicy.policy(), identity, new RateLimitRequest(1))
        .map(
            result ->
                new RateLimitEvaluation(
                    result.decision().allowed() ? RateLimitOutcome.ALLOW : RateLimitOutcome.REJECT,
                    Optional.of(result.decision()),
                    Optional.of(result.resetAfter()),
                    result.stateBackend(),
                    result.redisOutcome(),
                    effectiveFailureMode))
        .onErrorResume(
            RedisStateException.class, failure -> failureEvaluation(failure, effectiveFailureMode));
  }

  private Mono<RateLimitEvaluation> failureEvaluation(
      RedisStateException failure, FailureMode failureMode) {
    RateLimitOutcome outcome =
        failureMode == FailureMode.FAIL_OPEN
            ? RateLimitOutcome.DEGRADED_ALLOW
            : RateLimitOutcome.STATE_UNAVAILABLE;
    return Mono.just(
        new RateLimitEvaluation(
            outcome,
            Optional.empty(),
            Optional.empty(),
            StateBackend.REDIS,
            failure.outcome(),
            failureMode));
  }
}
