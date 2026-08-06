package lab.ratelimiter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import org.junit.jupiter.api.Test;

class InMemorySlidingWindowCounterStateAdapterTest {

  @Test
  void usesTheSameTransitionSemanticsForTeachingAndLocalDevelopment() {
    MutableClock clock = new MutableClock(Instant.ofEpochMilli(10_000));
    InMemorySlidingWindowCounterStateAdapter adapter =
        new InMemorySlidingWindowCounterStateAdapter(clock);
    SlidingWindowCounterPolicy policy =
        new SlidingWindowCounterPolicy(
            new PolicyId("teaching"), new PolicyVersion(1), 2, Duration.ofSeconds(1));
    var identity = new ClientIdentityExtractor().extract("client", "catalog.items").orElseThrow();

    assertThat(adapter.decide(policy, 1, identity).block().decision().allowed()).isTrue();
    assertThat(adapter.decide(policy, 1, identity).block().decision().allowed()).isTrue();
    assertThat(adapter.decide(policy, 1, identity).block().decision().allowed()).isFalse();
    clock.advance(Duration.ofSeconds(2));
    SlidingWindowCounterStateResult reconstructed = adapter.decide(policy, 1, identity).block();
    assertThat(reconstructed.decision().allowed()).isTrue();
    assertThat(reconstructed.stateBackend()).isEqualTo(StateBackend.IN_MEMORY);
  }

  private static final class MutableClock extends java.time.Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public java.time.Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
