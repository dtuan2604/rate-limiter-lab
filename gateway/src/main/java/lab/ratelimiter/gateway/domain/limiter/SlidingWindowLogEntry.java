package lab.ratelimiter.gateway.domain.limiter;

import java.time.Instant;
import java.util.Objects;

public record SlidingWindowLogEntry(Instant timestamp, RequestCost cost) {

  public SlidingWindowLogEntry {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(cost, "cost");
  }
}
