package lab.ratelimiter.gateway.domain.limiter;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

abstract class AbstractInMemoryRateLimiter<P extends RateLimitPolicy, S extends RateLimitState>
    implements RateLimiter<P, S> {

  private final P policy;
  private final Clock clock;
  private S state;

  AbstractInMemoryRateLimiter(P policy, Clock clock, S initialState) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    state = Objects.requireNonNull(initialState, "initialState");
  }

  @Override
  public final P policy() {
    return policy;
  }

  @Override
  public final synchronized S snapshot() {
    return state;
  }

  @Override
  public final synchronized RateLimitDecision decide(RateLimitRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = TimeMath.effectiveNow(clock, state.observedAt());
    StateTransition<S> transition = transition(state, request, now);
    state = transition.state();
    return transition.decision();
  }

  abstract StateTransition<S> transition(S current, RateLimitRequest request, Instant now);
}
