package lab.ratelimiter.gateway.domain.limiter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InMemorySlidingWindowLogRateLimiter
    extends AbstractInMemoryRateLimiter<SlidingWindowLogPolicy, SlidingWindowLogState> {

  public InMemorySlidingWindowLogRateLimiter(SlidingWindowLogPolicy policy, Clock clock) {
    this(policy, clock, TimeMath.now(clock));
  }

  private InMemorySlidingWindowLogRateLimiter(
      SlidingWindowLogPolicy policy, Clock clock, Instant initialTime) {
    super(policy, clock, new SlidingWindowLogState(List.of(), initialTime));
  }

  @Override
  StateTransition<SlidingWindowLogState> transition(
      SlidingWindowLogState current, RateLimitRequest request, Instant now) {
    SlidingWindowLogPolicy policy = policy();
    Instant cutoff = now.minus(policy.window());
    List<SlidingWindowLogEntry> activeEntries =
        current.entries().stream()
            .filter(entry -> entry.timestamp().isAfter(cutoff))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    long used = activeEntries.stream().mapToLong(entry -> entry.cost().units()).sum();
    long cost = request.cost().units();
    boolean allowed = cost <= policy.limit() - used;
    if (allowed) {
      activeEntries.add(new SlidingWindowLogEntry(now, request.cost()));
      used += cost;
    }

    SlidingWindowLogState updatedState = new SlidingWindowLogState(activeEntries, now);
    Optional<Duration> retryAfter =
        retryAfter(activeEntries, used, cost, policy.limit(), policy.window(), now, allowed);
    Instant resetAt =
        activeEntries.isEmpty() ? now : activeEntries.getLast().timestamp().plus(policy.window());
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            policy.limit(),
            policy.limit() - used,
            retryAfter,
            Optional.of(resetAt),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new StateTransition<>(updatedState, decision);
  }

  private static Optional<Duration> retryAfter(
      List<SlidingWindowLogEntry> activeEntries,
      long used,
      long cost,
      long limit,
      Duration window,
      Instant now,
      boolean allowed) {
    if (allowed || cost > limit) {
      return Optional.empty();
    }
    long needed = cost - (limit - used);
    long released = 0;
    for (SlidingWindowLogEntry entry : activeEntries) {
      released += entry.cost().units();
      if (released >= needed) {
        return Optional.of(Duration.between(now, entry.timestamp().plus(window)));
      }
    }
    throw new IllegalStateException("active log cannot satisfy retry calculation");
  }
}
