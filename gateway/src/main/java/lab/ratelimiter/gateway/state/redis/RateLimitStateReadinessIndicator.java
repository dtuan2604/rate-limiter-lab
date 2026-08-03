package lab.ratelimiter.gateway.state.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.application.StateBackend;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

public final class RateLimitStateReadinessIndicator implements ReactiveHealthIndicator {

  private final Supplier<Mono<String>> redisPing;
  private final StateBackend stateBackend;
  private final FailureMode failureMode;
  private final Duration timeout;

  public RateLimitStateReadinessIndicator(
      ReactiveStringRedisTemplate redis,
      StateBackend stateBackend,
      FailureMode failureMode,
      Duration timeout) {
    this(
        () -> redis.execute(connection -> connection.ping()).single(),
        stateBackend,
        failureMode,
        timeout);
  }

  RateLimitStateReadinessIndicator(
      Supplier<Mono<String>> redisPing,
      StateBackend stateBackend,
      FailureMode failureMode,
      Duration timeout) {
    this.redisPing = Objects.requireNonNull(redisPing, "redisPing");
    this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
    this.failureMode = Objects.requireNonNull(failureMode, "failureMode");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public Mono<Health> health() {
    if (stateBackend == StateBackend.IN_MEMORY) {
      return Mono.just(Health.up().withDetail("stateBackend", stateBackend.name()).build());
    }
    return redisPing
        .get()
        .timeout(timeout)
        .filter("PONG"::equalsIgnoreCase)
        .switchIfEmpty(Mono.error(new IllegalStateException("unexpected Redis ping response")))
        .map(ignored -> available())
        .onErrorResume(ignored -> Mono.just(unavailable()));
  }

  private Health available() {
    return Health.up()
        .withDetail("stateBackend", stateBackend.name())
        .withDetail("failureMode", failureMode.name())
        .withDetail("degraded", false)
        .build();
  }

  private Health unavailable() {
    Health.Builder builder = failureMode == FailureMode.FAIL_OPEN ? Health.up() : Health.down();
    return builder
        .withDetail("stateBackend", stateBackend.name())
        .withDetail("failureMode", failureMode.name())
        .withDetail("degraded", true)
        .withDetail("redisOutcome", "UNAVAILABLE")
        .build();
  }
}
