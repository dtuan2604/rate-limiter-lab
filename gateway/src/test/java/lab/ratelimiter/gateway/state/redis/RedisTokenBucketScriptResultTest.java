package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lab.ratelimiter.gateway.application.RedisOutcome;
import org.junit.jupiter.api.Test;

class RedisTokenBucketScriptResultTest {

  private static final TokenBucketParameters PARAMETERS =
      TokenBucketParameters.ofTokens(10, 4, 2, 1_000, 3, 1_000);

  @Test
  void decodesStrictAllowedAndRejectedVersionedTuples() {
    RedisTokenBucketScriptResult allowed =
        RedisTokenBucketScriptResult.decode(
            List.of(
                1L, 1L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_500L, 0L,
                1L),
            PARAMETERS);
    assertThat(allowed.outcome()).isEqualTo(RedisOutcome.ALLOWED);
    assertThat(allowed.remainingScaledTokens()).isEqualTo(1_000);
    assertThat(allowed.stateReconstructed()).isTrue();

    RedisTokenBucketScriptResult rejected =
        RedisTokenBucketScriptResult.decode(
            List.of(
                1L, 0L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 1_000L, 4_500L, 2_000L, 4_500L, 0L,
                0L),
            PARAMETERS);
    assertThat(rejected.outcome()).isEqualTo(RedisOutcome.REJECTED);
  }

  @Test
  void rejectsWrongShapeTypesVersionOutcomePolicyAndInconsistentTiming() {
    List<List<?>> invalid = new ArrayList<>();
    invalid.add(List.of());
    invalid.add(
        List.of(
            2L, 1L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_500L, 0L, 1L));
    invalid.add(
        List.of(
            1L, 9L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_500L, 0L, 1L));
    invalid.add(
        List.of(
            1L, 1L, 9_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_500L, 0L, 1L));
    invalid.add(
        List.of(
            1L, 1L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 1L, 4_500L, 2_000L, 4_500L, 0L, 1L));
    invalid.add(
        List.of(
            1L, 0L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_499L, 0L, 1L));
    invalid.add(
        List.of(
            1L, 1L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_500L, 0.5, 1L));

    invalid.add(changed(2, 10_001L));
    invalid.add(changed(4, 3_001L));
    invalid.add(changed(5, 2_001L));
    invalid.add(changed(6, 1_001L));
    invalid.add(changed(3, -1L));
    invalid.add(changed(3, 10_001L));
    invalid.add(changed(7, -1L));
    invalid.add(changed(8, 0L));
    invalid.add(changed(9, -1L));
    invalid.add(changed(10, 4_499L));
    invalid.add(changed(11, -1L));
    invalid.add(changed(11, 1_000L));
    invalid.add(changed(12, -1L));
    invalid.add(changed(12, 2L));
    invalid.add(changed(7, 1L));
    List<Object> rejectedPastReset = changed(1, 0L);
    rejectedPastReset.set(7, 4_501L);
    invalid.add(rejectedPastReset);
    invalid.add(changed(3, "1000"));
    invalid.add(changed(3, 1_000F));
    invalid.add(changed(3, 1_000D));
    invalid.add(changed(3, new BigDecimal("1.1")));

    assertMalformed(null);
    for (List<?> tuple : invalid) {
      assertMalformed(tuple);
    }
  }

  private static List<Object> changed(int index, Object value) {
    List<Object> tuple =
        new ArrayList<>(
            List.of(
                1L, 1L, 10_000L, 1_000L, 3_000L, 2_000L, 1_000L, 0L, 4_500L, 2_000L, 4_500L, 0L,
                1L));
    tuple.set(index, value);
    return tuple;
  }

  private static void assertMalformed(List<?> tuple) {
    assertThatThrownBy(() -> RedisTokenBucketScriptResult.decode(tuple, PARAMETERS))
        .isInstanceOf(RedisStateException.class)
        .satisfies(
            error ->
                assertThat(((RedisStateException) error).outcome())
                    .isEqualTo(RedisOutcome.MALFORMED_RESPONSE));
  }
}
