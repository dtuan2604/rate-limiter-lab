package lab.ratelimiter.gateway.domain.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DomainModelTest {

  private static final PolicyId POLICY_ID = new PolicyId("orders");
  private static final PolicyVersion POLICY_VERSION = new PolicyVersion(3);
  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void valueObjectsRejectInvalidValues() {
    assertThatThrownBy(() -> new PolicyId(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PolicyVersion(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RequestCost(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void policiesRejectInvalidConfiguration() {
    assertThatThrownBy(
            () -> new FixedWindowPolicy(POLICY_ID, POLICY_VERSION, 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SlidingWindowLogPolicy(POLICY_ID, POLICY_VERSION, 10, Duration.ofSeconds(1), 9))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SlidingWindowCounterPolicy(POLICY_ID, POLICY_VERSION, 10, Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TokenBucketPolicy(POLICY_ID, POLICY_VERSION, 10, 11, 1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new LeakyBucketPolicy(POLICY_ID, POLICY_VERSION, 10, 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void policiesExposeTheirAlgorithmAndConfiguredLimit() {
    List<RateLimitPolicy> policies =
        List.of(
            new FixedWindowPolicy(POLICY_ID, POLICY_VERSION, 10, Duration.ofSeconds(1)),
            new SlidingWindowLogPolicy(POLICY_ID, POLICY_VERSION, 10, Duration.ofSeconds(1), 10),
            new SlidingWindowCounterPolicy(POLICY_ID, POLICY_VERSION, 10, Duration.ofSeconds(1)),
            new TokenBucketPolicy(POLICY_ID, POLICY_VERSION, 10, 5, 2, Duration.ofSeconds(1)),
            new LeakyBucketPolicy(POLICY_ID, POLICY_VERSION, 10, 2, Duration.ofSeconds(1)));

    assertThat(policies)
        .extracting(RateLimitPolicy::algorithm)
        .containsExactly(AlgorithmType.values());
    assertThat(policies).extracting(RateLimitPolicy::limit).containsOnly(10L);
  }

  @Test
  void slidingLogStateDefensivelyCopiesEntries() {
    List<SlidingWindowLogEntry> source =
        new ArrayList<>(List.of(new SlidingWindowLogEntry(NOW, new RequestCost(2))));

    SlidingWindowLogState state = new SlidingWindowLogState(source, NOW);
    source.clear();

    assertThat(state.entries()).hasSize(1);
    assertThatThrownBy(
            () -> state.entries().add(new SlidingWindowLogEntry(NOW, new RequestCost(1))))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void decisionContainsStableImmutableMetadata() {
    RateLimitDecision decision =
        new RateLimitDecision(
            true,
            10,
            8,
            Optional.empty(),
            Optional.of(NOW.plusSeconds(1)),
            POLICY_ID,
            POLICY_VERSION,
            AlgorithmType.TOKEN_BUCKET);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(8);
    assertThat(decision.retryAfter()).isEmpty();
    assertThat(decision.resetAt()).contains(NOW.plusSeconds(1));
  }

  @Test
  void statesRejectOutOfRangeValues() {
    assertThatThrownBy(() -> new FixedWindowState(NOW, -1, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SlidingWindowCounterState(NOW, -1, 0, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketState(BigInteger.valueOf(-1), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LeakyBucketState(BigInteger.valueOf(-1), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
