package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lab.ratelimiter.gateway.application.RedisOutcome;
import org.junit.jupiter.api.Test;

class RedisSlidingWindowCounterScriptResultTest {

  private static final SlidingCounterParameters PARAMETERS =
      new SlidingCounterParameters(10, 1_000, 3);

  @Test
  void decodesStrictAllowedAndRejectedVersionedTuples() {
    RedisSlidingWindowCounterScriptResult allowed =
        RedisSlidingWindowCounterScriptResult.decode(allowedTuple(), PARAMETERS);
    assertThat(allowed.outcome()).isEqualTo(RedisOutcome.ALLOWED);
    assertThat(allowed.currentWindowCount()).isEqualTo(4);
    assertThat(allowed.previousWindowCount()).isEqualTo(2);
    assertThat(allowed.weightedNumerator()).isEqualTo(5_000);
    assertThat(allowed.rotation()).isEqualTo(SlidingCounterRotation.SAME);

    RedisSlidingWindowCounterScriptResult rejected =
        RedisSlidingWindowCounterScriptResult.decode(
            List.of(
                1L, 0L, 10L, 1_000L, 3L, 10L, 10_000L, 500L, 8L, 4L, 10_000L, 10L, 0L, 625L, 1_500L,
                10_500L, 1_500L, 1L),
            PARAMETERS);
    assertThat(rejected.outcome()).isEqualTo(RedisOutcome.REJECTED);
    assertThat(rejected.retryAfterMilliseconds()).isEqualTo(625);
  }

  @Test
  void rejectsWrongShapeTypesPolicyAndInconsistentDerivedValues() {
    List<List<?>> invalid = new ArrayList<>();
    invalid.add(List.of());
    invalid.add(changed(0, 2L));
    invalid.add(changed(1, 9L));
    invalid.add(changed(2, 11L));
    invalid.add(changed(3, 999L));
    invalid.add(changed(4, 2L));
    invalid.add(changed(6, 10_001L));
    invalid.add(changed(7, 1_000L));
    invalid.add(changed(8, 11L));
    invalid.add(changed(9, 11L));
    invalid.add(changed(10, 5_001L));
    invalid.add(changed(11, 6L));
    invalid.add(changed(12, 4L));
    invalid.add(changed(13, 1L));
    invalid.add(changed(14, 0L));
    invalid.add(changed(15, 10_499L));
    invalid.add(changed(16, 1_499L));
    invalid.add(changed(17, 4L));
    invalid.add(changed(8, "4"));
    invalid.add(changed(8, 4F));
    invalid.add(changed(8, 4D));
    invalid.add(changed(8, new BigDecimal("4.1")));

    assertMalformed(null);
    for (List<?> tuple : invalid) {
      assertMalformed(tuple);
    }
  }

  private static List<Long> allowedTuple() {
    return List.of(
        1L, 1L, 10L, 1_000L, 3L, 10L, 10_000L, 500L, 4L, 2L, 5_000L, 5L, 5L, 0L, 1_500L, 10_500L,
        1_500L, 1L);
  }

  private static List<Object> changed(int index, Object value) {
    List<Object> tuple = new ArrayList<>(allowedTuple());
    tuple.set(index, value);
    return tuple;
  }

  private static void assertMalformed(List<?> tuple) {
    assertThatThrownBy(() -> RedisSlidingWindowCounterScriptResult.decode(tuple, PARAMETERS))
        .isInstanceOf(RedisStateException.class)
        .satisfies(
            error ->
                assertThat(((RedisStateException) error).outcome())
                    .isEqualTo(RedisOutcome.MALFORMED_RESPONSE));
  }
}
