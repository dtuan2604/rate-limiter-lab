package lab.ratelimiter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import org.junit.jupiter.api.Test;

class InMemoryTokenBucketStateAdapterTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final TokenBucketPolicy POLICY =
      new TokenBucketPolicy(
          new PolicyId("teaching-token"), new PolicyVersion(1), 10, 10, 2, Duration.ofSeconds(1));

  @Test
  void teachingModeUsesPhaseOneTokenBucketAndKeepsIdentitiesIndependent() {
    InMemoryTokenBucketStateAdapter adapter =
        new InMemoryTokenBucketStateAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
    var identities = new ClientIdentityExtractor();
    var first = identities.extract("client-a", "catalog.items").orElseThrow();
    var second = identities.extract("client-b", "catalog.items").orElseThrow();

    TokenBucketStateResult firstDecision =
        adapter.decide(POLICY, 3, NOW.minusSeconds(1), first).block();
    adapter.decide(POLICY, 3, NOW.minusSeconds(1), first).block();
    adapter.decide(POLICY, 3, NOW.minusSeconds(1), first).block();
    TokenBucketStateResult rejected = adapter.decide(POLICY, 3, NOW.minusSeconds(1), first).block();
    TokenBucketStateResult independent =
        adapter.decide(POLICY, 3, NOW.minusSeconds(1), second).block();

    assertThat(firstDecision.decision().allowed()).isTrue();
    assertThat(firstDecision.stateBackend()).isEqualTo(StateBackend.IN_MEMORY);
    assertThat(firstDecision.redisOutcome()).isEqualTo(RedisOutcome.NOT_APPLICABLE);
    assertThat(rejected.decision().allowed()).isFalse();
    assertThat(rejected.decision().remaining()).isEqualTo(1);
    assertThat(independent.decision().allowed()).isTrue();
  }

  @Test
  void teachingModeProjectsPhaseOneFractionalBalanceToConservativeMillitokens() {
    MutableClock clock = new MutableClock(NOW);
    InMemoryTokenBucketStateAdapter adapter = new InMemoryTokenBucketStateAdapter(clock);
    TokenBucketPolicy empty =
        new TokenBucketPolicy(
            new PolicyId("teaching-partial"), new PolicyVersion(1), 2, 0, 1, Duration.ofSeconds(1));
    var identity = new ClientIdentityExtractor().extract("partial", "catalog.items").orElseThrow();
    assertThat(adapter.decide(empty, 1, NOW, identity).block().remainingScaledTokens()).isZero();

    clock.advance(Duration.ofMillis(500));

    TokenBucketStateResult partial = adapter.decide(empty, 1, NOW, identity).block();
    assertThat(partial.decision().allowed()).isFalse();
    assertThat(partial.remainingScaledTokens()).isEqualTo(500);
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
