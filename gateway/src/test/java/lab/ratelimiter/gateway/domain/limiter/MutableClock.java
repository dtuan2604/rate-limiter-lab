package lab.ratelimiter.gateway.domain.limiter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

final class MutableClock extends Clock {

  private Instant instant;
  private final ZoneId zone;

  MutableClock(Instant instant) {
    this(instant, ZoneId.of("UTC"));
  }

  private MutableClock(Instant instant, ZoneId zone) {
    this.instant = Objects.requireNonNull(instant, "instant");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  synchronized void advance(Duration duration) {
    instant = instant.plus(duration);
  }

  synchronized void set(Instant value) {
    instant = Objects.requireNonNull(value, "value");
  }

  @Override
  public synchronized Instant instant() {
    return instant;
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId requestedZone) {
    return new MutableClock(instant(), requestedZone);
  }
}
