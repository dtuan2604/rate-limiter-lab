package lab.ratelimiter.gateway.application;

import java.util.Objects;
import java.util.Optional;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledFixedWindowAlgorithm;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.CompiledTokenBucketAlgorithm;
import lab.ratelimiter.gateway.state.redis.RedisStateException;
import reactor.core.publisher.Mono;

public final class RateLimitService {

  private final FixedWindowStateAdapter fixedWindowStateAdapter;
  private final TokenBucketStateAdapter tokenBucketStateAdapter;
  private final FailureMode legacyFailureMode;

  public RateLimitService(FixedWindowStateAdapter stateAdapter) {
    this(stateAdapter, unsupportedTokenBucketAdapter(), null);
  }

  public RateLimitService(
      FixedWindowStateAdapter fixedWindowStateAdapter,
      TokenBucketStateAdapter tokenBucketStateAdapter) {
    this(fixedWindowStateAdapter, tokenBucketStateAdapter, null);
  }

  private RateLimitService(
      FixedWindowStateAdapter fixedWindowStateAdapter,
      TokenBucketStateAdapter tokenBucketStateAdapter,
      FailureMode failureMode) {
    this.fixedWindowStateAdapter =
        Objects.requireNonNull(fixedWindowStateAdapter, "fixedWindowStateAdapter");
    this.tokenBucketStateAdapter =
        Objects.requireNonNull(tokenBucketStateAdapter, "tokenBucketStateAdapter");
    this.legacyFailureMode = failureMode;
  }

  public RateLimitService(FixedWindowStateAdapter stateAdapter, FailureMode failureMode) {
    this(
        stateAdapter,
        unsupportedTokenBucketAdapter(),
        Objects.requireNonNull(failureMode, "failureMode"));
  }

  public Mono<RateLimitEvaluation> evaluate(
      CompiledPolicy compiledPolicy, LimiterIdentity identity) {
    Objects.requireNonNull(compiledPolicy, "compiledPolicy");
    Objects.requireNonNull(identity, "identity");
    FailureMode effectiveFailureMode =
        legacyFailureMode == null ? compiledPolicy.failureMode() : legacyFailureMode;
    Mono<RateLimitEvaluation> evaluation;
    if (compiledPolicy.compiledAlgorithm() instanceof CompiledFixedWindowAlgorithm fixedWindow) {
      evaluation =
          fixedWindowStateAdapter
              .decide(fixedWindow.policy(), identity, new RateLimitRequest(1))
              .map(result -> fixedWindowEvaluation(result, effectiveFailureMode));
    } else if (compiledPolicy.compiledAlgorithm()
        instanceof CompiledTokenBucketAlgorithm tokenBucket) {
      evaluation =
          tokenBucketStateAdapter
              .decide(
                  tokenBucket.policy(),
                  tokenBucket.requestCost(),
                  tokenBucket.activationTime(),
                  identity)
              .map(result -> tokenBucketEvaluation(result, effectiveFailureMode));
    } else {
      return Mono.error(new IllegalArgumentException("Unsupported compiled policy algorithm"));
    }
    return evaluation.onErrorResume(
        RedisStateException.class, failure -> failureEvaluation(failure, effectiveFailureMode));
  }

  private static RateLimitEvaluation fixedWindowEvaluation(
      FixedWindowStateResult result, FailureMode failureMode) {
    return new RateLimitEvaluation(
        result.decision().allowed() ? RateLimitOutcome.ALLOW : RateLimitOutcome.REJECT,
        Optional.of(result.decision()),
        Optional.of(result.resetAfter()),
        Optional.empty(),
        result.stateBackend(),
        result.redisOutcome(),
        failureMode);
  }

  private static RateLimitEvaluation tokenBucketEvaluation(
      TokenBucketStateResult result, FailureMode failureMode) {
    return new RateLimitEvaluation(
        result.decision().allowed() ? RateLimitOutcome.ALLOW : RateLimitOutcome.REJECT,
        Optional.of(result.decision()),
        Optional.of(result.resetAfter()),
        Optional.of(result),
        result.stateBackend(),
        result.redisOutcome(),
        failureMode);
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
            Optional.empty(),
            StateBackend.REDIS,
            failure.outcome(),
            failureMode));
  }

  private static TokenBucketStateAdapter unsupportedTokenBucketAdapter() {
    return (policy, requestCost, activationTime, identity) ->
        Mono.error(new IllegalStateException("Token Bucket state adapter is not configured"));
  }
}
