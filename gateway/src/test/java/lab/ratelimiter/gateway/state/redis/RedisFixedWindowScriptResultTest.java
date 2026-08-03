package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import org.junit.jupiter.api.Test;

class RedisFixedWindowScriptResultTest {

  private static final FixedWindowPolicy POLICY =
      new FixedWindowPolicy(
          new PolicyId("policy"), new PolicyVersion(3), 5, Duration.ofSeconds(10));

  @Test
  void decodesAllowedTupleAndMapsTheAuthoritativeMetadata() {
    RedisFixedWindowScriptResult result =
        RedisFixedWindowScriptResult.decode(
            List.of(1L, 1L, 3L, 2L, 5L, 0L, 20_000L, 12_345L, 1L, 7_655L));

    assertThat(result.outcome()).isEqualTo(RedisOutcome.ALLOWED);
    assertThat(result.currentCount()).isEqualTo(3);
    assertThat(result.remaining()).isEqualTo(2);
    assertThat(result.retryAfter()).isZero();
    assertThat(result.resetAt()).isEqualTo(Instant.ofEpochMilli(20_000));
    assertThat(result.redisNow()).isEqualTo(Instant.ofEpochMilli(12_345));
    assertThat(result.windowId()).isEqualTo(1);
    assertThat(result.ttl()).isEqualTo(Duration.ofMillis(7_655));
    assertThat(result.toStateResult(POLICY).decision().allowed()).isTrue();
    assertThat(result.toStateResult(POLICY).resetAfter()).isEqualTo(Duration.ofMillis(7_655));
  }

  @Test
  void decodesRejectedAndWindowMismatchOutcomes() {
    RedisFixedWindowScriptResult rejected =
        RedisFixedWindowScriptResult.decode(
            List.of(1L, 0L, 5L, 0L, 5L, 2_500L, 20_000L, 17_500L, 1L, 2_500L));
    RedisFixedWindowScriptResult mismatch =
        RedisFixedWindowScriptResult.decode(
            List.of(1L, 2L, 0L, 0L, 5L, 0L, 30_000L, 20_000L, 2L, 10_000L));

    assertThat(rejected.outcome()).isEqualTo(RedisOutcome.REJECTED);
    assertThat(rejected.toStateResult(POLICY).decision().allowed()).isFalse();
    assertThat(rejected.toStateResult(POLICY).decision().retryAfter())
        .contains(Duration.ofMillis(2_500));
    assertThat(mismatch.windowMismatch()).isTrue();
    assertThatThrownBy(() -> mismatch.toStateResult(POLICY))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void malformedTuplesFailClosedBeforeCreatingADomainDecision() {
    List<List<?>> malformed =
        List.of(
            List.of(1L, 1L),
            List.of(1L, 1L, 1.5D, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1.0F, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1.0D, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, new BigDecimal("1.5"), 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(2L, 1L, 1L, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 9L, 1L, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 0L, 0L, 0L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1L, 4L, 1_000_001L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, -1L, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 6L, 0L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1L, -1L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1L, 9L, 5L, 0L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1L, 4L, 5L, 0L, 20_000L, 10_000L, -1L, 10_000L),
            List.of(1L, 1L, 1L, 4L, 5L, 1L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 0L, 5L, 0L, 5L, 1L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 2L, 0L, 5L, 5L, 1L, 20_000L, 10_000L, 1L, 10_000L),
            List.of(1L, 1L, 1L, 4L, 5L, 0L, 20_000L, -1L, 1L, 20_001L),
            List.of(1L, 1L, 1L, 4L, 5L, 0L, 10_000L, 10_000L, 1L, 1L),
            List.of(1L, 1L, 1L, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 0L),
            List.of(1L, 1L, 1L, 4L, 5L, 0L, 20_000L, 10_000L, 1L, 9_999L),
            List.of(1L, 1L, 1L, 4L, 5L, 0L, 10_000L, 20_000L, 1L, -1L),
            List.of(1L, 1L, 1L, 4L, "5", 0L, 20_000L, 10_000L, 1L, 10_000L));

    for (List<?> tuple : malformed) {
      assertThatThrownBy(() -> RedisFixedWindowScriptResult.decode(tuple))
          .isInstanceOf(RedisStateException.class)
          .satisfies(
              error ->
                  assertThat(((RedisStateException) error).outcome())
                      .isEqualTo(RedisOutcome.MALFORMED_RESPONSE));
    }

    assertThatThrownBy(() -> RedisFixedWindowScriptResult.decode(null))
        .isInstanceOf(RedisStateException.class);
    RedisFixedWindowScriptResult wrongPolicyLimit =
        RedisFixedWindowScriptResult.decode(
            List.of(1L, 1L, 3L, 2L, 5L, 0L, 20_000L, 12_345L, 1L, 7_655L));
    assertThatThrownBy(
            () ->
                wrongPolicyLimit.toStateResult(
                    new FixedWindowPolicy(
                        new PolicyId("policy"), new PolicyVersion(3), 6, Duration.ofSeconds(10))))
        .isInstanceOf(RedisStateException.class);
  }
}
