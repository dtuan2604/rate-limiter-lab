package lab.ratelimiter.gateway.domain.limiter;

import java.util.Objects;

record StateTransition<S extends RateLimitState>(S state, RateLimitDecision decision) {

  StateTransition {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(decision, "decision");
  }
}
