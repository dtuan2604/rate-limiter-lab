package lab.ratelimiter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import org.junit.jupiter.api.Test;

class InMemoryFixedWindowStateAdapterTest {

  private static final Instant START = Instant.parse("2026-08-02T12:00:00Z");
  private static final FixedWindowPolicy POLICY =
      new FixedWindowPolicy(
          new PolicyId("catalog-client-fixed-window"),
          new PolicyVersion(1),
          5,
          Duration.ofSeconds(10));
  private static final LimiterIdentity IDENTITY =
      new ClientIdentityExtractor().extract("client-a", "catalog.items").orElseThrow();

  @Test
  void preservesTheReferenceFixedWindowBehaviorBehindTheStatePort() {
    FixedWindowStateAdapter adapter =
        new InMemoryFixedWindowStateAdapter(Clock.fixed(START, ZoneOffset.UTC));

    for (int request = 1; request <= 5; request++) {
      FixedWindowStateResult result =
          adapter.decide(POLICY, IDENTITY, new RateLimitRequest(1)).block();
      assertThat(result).isNotNull();
      assertThat(result.decision().allowed()).isTrue();
      assertThat(result.currentCount()).isEqualTo(request);
      assertThat(result.decision().remaining()).isEqualTo(5 - request);
      assertThat(result.resetAfter()).isEqualTo(Duration.ofSeconds(10));
      assertThat(result.stateBackend()).isEqualTo(StateBackend.IN_MEMORY);
      assertThat(result.redisOutcome()).isEqualTo(RedisOutcome.NOT_APPLICABLE);
    }

    FixedWindowStateResult rejected =
        adapter.decide(POLICY, IDENTITY, new RateLimitRequest(1)).block();

    assertThat(rejected).isNotNull();
    assertThat(rejected.decision().allowed()).isFalse();
    assertThat(rejected.currentCount()).isEqualTo(5);
    assertThat(rejected.decision().retryAfter()).contains(Duration.ofSeconds(10));
  }

  @Test
  void stateResultRejectsInvalidCountsAndResetDurations() {
    RateLimitDecision decision =
        new RateLimitDecision(
            true,
            5,
            4,
            Optional.empty(),
            Optional.of(START.plusSeconds(10)),
            POLICY.policyId(),
            POLICY.policyVersion(),
            POLICY.algorithm());

    assertThatThrownBy(
            () ->
                new FixedWindowStateResult(
                    decision,
                    -1,
                    Duration.ofSeconds(1),
                    StateBackend.IN_MEMORY,
                    RedisOutcome.NOT_APPLICABLE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FixedWindowStateResult(
                    decision,
                    6,
                    Duration.ofSeconds(1),
                    StateBackend.IN_MEMORY,
                    RedisOutcome.NOT_APPLICABLE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FixedWindowStateResult(
                    decision,
                    1,
                    Duration.ofMillis(-1),
                    StateBackend.IN_MEMORY,
                    RedisOutcome.NOT_APPLICABLE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
