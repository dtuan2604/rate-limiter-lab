package lab.ratelimiter.gateway.domain.limiter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SlidingWindowLogState(List<SlidingWindowLogEntry> entries, Instant observedAt)
    implements RateLimitState {

  public SlidingWindowLogState {
    entries = List.copyOf(entries);
    Objects.requireNonNull(observedAt, "observedAt");
  }
}
