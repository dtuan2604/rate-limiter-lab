package lab.ratelimiter.gateway.domain.limiter;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

final class TimeMath {

  private TimeMath() {}

  static Instant now(Clock clock) {
    Objects.requireNonNull(clock, "clock");
    return Instant.ofEpochMilli(clock.instant().toEpochMilli());
  }

  static Instant effectiveNow(Clock clock, Instant lastObserved) {
    Instant current = now(clock);
    return current.isBefore(lastObserved) ? lastObserved : current;
  }

  static Instant alignedWindowStart(Instant instant, long windowMilliseconds) {
    long index = Math.floorDiv(instant.toEpochMilli(), windowMilliseconds);
    return Instant.ofEpochMilli(Math.multiplyExact(index, windowMilliseconds));
  }
}
