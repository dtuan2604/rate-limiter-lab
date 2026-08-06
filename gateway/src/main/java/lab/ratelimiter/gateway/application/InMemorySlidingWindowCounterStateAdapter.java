package lab.ratelimiter.gateway.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.state.redis.RedisSlidingWindowCounterArithmetic;
import lab.ratelimiter.gateway.state.redis.SlidingCounterParameters;
import lab.ratelimiter.gateway.state.redis.SlidingCounterState;
import lab.ratelimiter.gateway.state.redis.SlidingCounterTransition;
import reactor.core.publisher.Mono;

public final class InMemorySlidingWindowCounterStateAdapter
    implements SlidingWindowCounterStateAdapter {

  private final Clock clock;
  private final ConcurrentHashMap<String, SlidingCounterState> states = new ConcurrentHashMap<>();

  public InMemorySlidingWindowCounterStateAdapter(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Mono<SlidingWindowCounterStateResult> decide(
      SlidingWindowCounterPolicy policy, long requestCost, LimiterIdentity identity) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    SlidingCounterParameters parameters =
        new SlidingCounterParameters(policy.limit(), policy.window().toMillis(), requestCost);
    Instant now = clock.instant();
    AtomicReference<SlidingCounterTransition> decided = new AtomicReference<>();
    String key =
        policy.policyId().value() + ':' + policy.policyVersion().value() + ':' + identity.digest();
    states.compute(
        key,
        (ignored, state) -> {
          SlidingCounterTransition transition =
              RedisSlidingWindowCounterArithmetic.decide(parameters, state, now.toEpochMilli());
          decided.set(transition);
          return transition.state();
        });
    SlidingCounterTransition transition = decided.get();
    Duration retry = Duration.ofMillis(transition.retryAfterMilliseconds());
    Duration reset = Duration.ofMillis(transition.resetAfterMilliseconds());
    RateLimitDecision decision =
        new RateLimitDecision(
            transition.allowed(),
            policy.limit(),
            transition.remainingCapacity(),
            transition.allowed() ? Optional.empty() : Optional.of(retry),
            Optional.of(now.plus(reset)),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return Mono.just(
        new SlidingWindowCounterStateResult(
            decision,
            transition.state().windowId(),
            transition.state().currentCount(),
            transition.state().previousCount(),
            Duration.ofMillis(transition.elapsedMilliseconds()),
            transition.weightedNumerator(),
            transition.weightedEstimate(),
            requestCost,
            transition.remainingCapacity(),
            retry,
            reset,
            now,
            reset,
            transition.rotation(),
            StateBackend.IN_MEMORY,
            RedisOutcome.NOT_APPLICABLE));
  }
}
