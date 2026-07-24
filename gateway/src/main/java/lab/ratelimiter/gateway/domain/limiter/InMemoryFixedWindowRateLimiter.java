package lab.ratelimiter.gateway.domain.limiter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class InMemoryFixedWindowRateLimiter
    extends AbstractInMemoryRateLimiter<FixedWindowPolicy, FixedWindowState> {

  public InMemoryFixedWindowRateLimiter(FixedWindowPolicy policy, Clock clock) {
    this(policy, clock, TimeMath.now(clock));
  }

  private InMemoryFixedWindowRateLimiter(
      FixedWindowPolicy policy, Clock clock, Instant initialTime) {
    super(
        policy,
        clock,
        new FixedWindowState(
            TimeMath.alignedWindowStart(initialTime, policy.window().toMillis()), 0, initialTime));
  }

  @Override
  StateTransition<FixedWindowState> transition(
      FixedWindowState current, RateLimitRequest request, Instant now) {
    FixedWindowPolicy policy = policy();
    long windowMilliseconds = policy.window().toMillis();
    Instant activeWindowStart = TimeMath.alignedWindowStart(now, windowMilliseconds);
    long used = activeWindowStart.equals(current.windowStart()) ? current.used() : 0;
    long cost = request.cost().units();
    boolean allowed = cost <= policy.limit() - used;
    long updatedUsed = allowed ? used + cost : used;
    FixedWindowState updatedState = new FixedWindowState(activeWindowStart, updatedUsed, now);
    Instant resetAt = activeWindowStart.plusMillis(windowMilliseconds);
    Optional<Duration> retryAfter =
        allowed || cost > policy.limit()
            ? Optional.empty()
            : Optional.of(Duration.between(now, resetAt));
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            policy.limit(),
            policy.limit() - updatedUsed,
            retryAfter,
            Optional.of(resetAt),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new StateTransition<>(updatedState, decision);
  }
}
