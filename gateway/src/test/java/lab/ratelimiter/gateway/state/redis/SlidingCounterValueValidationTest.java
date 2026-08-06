package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateResult;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.domain.limiter.AlgorithmType;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import org.junit.jupiter.api.Test;

class SlidingCounterValueValidationTest {

  @Test
  void stateAndTransitionRejectEveryNegativeOrMissingValue() {
    assertThatThrownBy(() -> new SlidingCounterState(-1, 0, 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> new SlidingCounterState(0, -1, 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> new SlidingCounterState(0, 0, -1))
        .isInstanceOf(RedisStateException.class);
    SlidingCounterState state = new SlidingCounterState(0, 0, 0);
    assertThatThrownBy(() -> new SlidingCounterTransition(true, null, 0, 0, 0, 0, 0, 0, 0, null))
        .isInstanceOf(NullPointerException.class);
    for (int field = 0; field < 7; field++) {
      long[] values = new long[7];
      values[field] = -1;
      assertThatThrownBy(
              () ->
                  new SlidingCounterTransition(
                      true,
                      state,
                      values[0],
                      values[1],
                      values[2],
                      values[3],
                      values[4],
                      values[5],
                      values[6],
                      SlidingCounterRotation.SAME))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void applicationResultRejectsEveryNegativeOrMissingValue() {
    RateLimitDecision decision =
        new RateLimitDecision(
            true,
            10,
            9,
            Optional.empty(),
            Optional.of(Instant.EPOCH.plusSeconds(1)),
            new PolicyId("sliding"),
            new PolicyVersion(1),
            AlgorithmType.SLIDING_WINDOW_COUNTER);
    assertThatThrownBy(
            () ->
                new SlidingWindowCounterStateResult(
                    null,
                    0,
                    0,
                    0,
                    Duration.ZERO,
                    0,
                    0,
                    1,
                    0,
                    Duration.ZERO,
                    Duration.ofSeconds(1),
                    Instant.EPOCH,
                    Duration.ofSeconds(1),
                    SlidingCounterRotation.SAME,
                    StateBackend.REDIS,
                    RedisOutcome.ALLOWED))
        .isInstanceOf(NullPointerException.class);
    for (int field = 0; field < 7; field++) {
      long[] values = new long[] {0, 0, 0, 0, 0, 1, 0};
      values[field] = -1;
      assertThatThrownBy(
              () ->
                  new SlidingWindowCounterStateResult(
                      decision,
                      values[0],
                      values[1],
                      values[2],
                      Duration.ZERO,
                      values[3],
                      values[4],
                      values[5],
                      values[6],
                      Duration.ZERO,
                      Duration.ofSeconds(1),
                      Instant.EPOCH,
                      Duration.ofSeconds(1),
                      SlidingCounterRotation.SAME,
                      StateBackend.REDIS,
                      RedisOutcome.ALLOWED))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new SlidingWindowCounterStateResult(
                    decision,
                    0,
                    0,
                    0,
                    Duration.ofMillis(-1),
                    0,
                    0,
                    1,
                    0,
                    Duration.ZERO,
                    Duration.ofSeconds(1),
                    Instant.EPOCH,
                    Duration.ofSeconds(1),
                    SlidingCounterRotation.SAME,
                    StateBackend.REDIS,
                    RedisOutcome.ALLOWED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
