package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.application.StateBackend;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;

class RateLimitStateReadinessIndicatorTest {

  @Test
  void inMemoryModeIsReadyWithoutContactingRedis() {
    RateLimitStateReadinessIndicator indicator =
        new RateLimitStateReadinessIndicator(
            () -> Mono.error(new AssertionError("Redis must not be contacted")),
            StateBackend.IN_MEMORY,
            FailureMode.FAIL_CLOSED,
            Duration.ofMillis(50));

    assertThat(indicator.health().block())
        .satisfies(
            health -> {
              assertThat(health.getStatus()).isEqualTo(Status.UP);
              assertThat(health.getDetails()).containsEntry("stateBackend", "IN_MEMORY");
            });
  }

  @Test
  void redisSuccessIsReadyForBothFailureModes() {
    for (FailureMode mode : FailureMode.values()) {
      RateLimitStateReadinessIndicator indicator =
          new RateLimitStateReadinessIndicator(
              () -> Mono.just("PONG"), StateBackend.REDIS, mode, Duration.ofMillis(50));

      assertThat(indicator.health().block())
          .satisfies(
              health -> {
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails())
                    .containsEntry("stateBackend", "REDIS")
                    .containsEntry("degraded", false);
              });
    }
  }

  @Test
  void failClosedRedisFailureIsDownAndSanitized() {
    RateLimitStateReadinessIndicator indicator =
        new RateLimitStateReadinessIndicator(
            () -> Mono.error(new IllegalStateException("secret endpoint")),
            StateBackend.REDIS,
            FailureMode.FAIL_CLOSED,
            Duration.ofMillis(50));

    assertThat(indicator.health().block())
        .satisfies(
            health -> {
              assertThat(health.getStatus()).isEqualTo(Status.DOWN);
              assertThat(health.getDetails())
                  .containsEntry("stateBackend", "REDIS")
                  .containsEntry("degraded", true)
                  .doesNotContainValue("secret endpoint");
            });
  }

  @Test
  void failOpenRedisFailureAllowsDocumentedDegradedReadiness() {
    RateLimitStateReadinessIndicator indicator =
        new RateLimitStateReadinessIndicator(
            () -> Mono.never(), StateBackend.REDIS, FailureMode.FAIL_OPEN, Duration.ofMillis(10));

    assertThat(indicator.health().block())
        .satisfies(
            health -> {
              assertThat(health.getStatus()).isEqualTo(Status.UP);
              assertThat(health.getDetails())
                  .containsEntry("stateBackend", "REDIS")
                  .containsEntry("degraded", true)
                  .containsEntry("failureMode", "FAIL_OPEN");
            });
  }

  @Test
  void redisReadinessUsesTheCurrentPolicySnapshotFailureSemantics() {
    AtomicReference<FailureMode> currentMode = new AtomicReference<>(FailureMode.FAIL_OPEN);
    RateLimitStateReadinessIndicator indicator =
        new RateLimitStateReadinessIndicator(
            () -> Mono.error(new IllegalStateException("Redis unavailable")),
            StateBackend.REDIS,
            currentMode::get,
            Duration.ofMillis(50));

    assertThat(indicator.health().block().getStatus()).isEqualTo(Status.UP);
    currentMode.set(FailureMode.FAIL_CLOSED);
    assertThat(indicator.health().block().getStatus()).isEqualTo(Status.DOWN);
  }
}
