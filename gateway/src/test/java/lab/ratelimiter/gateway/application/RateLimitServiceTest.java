package lab.ratelimiter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

  private static final Instant START = Instant.parse("2026-07-26T12:00:00Z");
  private static final CompiledPolicy POLICY =
      new CompiledPolicy(
          "catalog.items",
          "/proxy/catalog/items",
          "GET",
          new FixedWindowPolicy(
              new PolicyId("catalog-client-fixed-window"),
              new PolicyVersion(1),
              5,
              Duration.ofSeconds(10)));
  private final ClientIdentityExtractor identityExtractor = new ClientIdentityExtractor();

  @Test
  void firstFiveRequestsAreAllowedAndSixthIsRejected() {
    MutableClock clock = new MutableClock(START);
    RateLimitService service =
        new RateLimitService(new InMemoryFixedWindowStateAdapter(clock), FailureMode.FAIL_CLOSED);
    LimiterIdentity identity =
        identityExtractor.extract("client-a", POLICY.routeId()).orElseThrow();

    for (int request = 1; request <= 5; request++) {
      RateLimitDecision decision =
          service.evaluate(POLICY, identity).block().rateLimitDecision().orElseThrow();
      assertThat(decision.allowed()).isTrue();
      assertThat(decision.remaining()).isEqualTo(5 - request);
    }

    RateLimitEvaluation evaluation = service.evaluate(POLICY, identity).block();
    RateLimitDecision rejected = evaluation.rateLimitDecision().orElseThrow();
    assertThat(evaluation.outcome()).isEqualTo(RateLimitOutcome.REJECT);
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.remaining()).isZero();
    assertThat(rejected.retryAfter()).contains(Duration.ofSeconds(10));
  }

  @Test
  void differentClientsAndRoutesHaveIndependentLimits() {
    MutableClock clock = new MutableClock(START);
    RateLimitService service =
        new RateLimitService(new InMemoryFixedWindowStateAdapter(clock), FailureMode.FAIL_CLOSED);
    LimiterIdentity first = identityExtractor.extract("client-a", POLICY.routeId()).orElseThrow();
    LimiterIdentity second = identityExtractor.extract("client-b", POLICY.routeId()).orElseThrow();
    LimiterIdentity otherRoute =
        identityExtractor.extract("client-a", "catalog.details").orElseThrow();

    for (int request = 0; request < 5; request++) {
      assertThat(
              service.evaluate(POLICY, first).block().rateLimitDecision().orElseThrow().allowed())
          .isTrue();
    }

    assertThat(service.evaluate(POLICY, first).block().outcome())
        .isEqualTo(RateLimitOutcome.REJECT);
    assertThat(service.evaluate(POLICY, second).block().outcome())
        .isEqualTo(RateLimitOutcome.ALLOW);
    assertThat(service.evaluate(POLICY, otherRoute).block().outcome())
        .isEqualTo(RateLimitOutcome.ALLOW);
  }

  @Test
  void requestsAreAllowedAfterInjectedClockEntersTheNextWindow() {
    MutableClock clock = new MutableClock(START);
    RateLimitService service =
        new RateLimitService(new InMemoryFixedWindowStateAdapter(clock), FailureMode.FAIL_CLOSED);
    LimiterIdentity identity =
        identityExtractor.extract("client-a", POLICY.routeId()).orElseThrow();
    for (int request = 0; request < 5; request++) {
      service.evaluate(POLICY, identity).block();
    }
    assertThat(service.evaluate(POLICY, identity).block().outcome())
        .isEqualTo(RateLimitOutcome.REJECT);

    clock.advance(Duration.ofSeconds(10));

    RateLimitDecision nextWindow =
        service.evaluate(POLICY, identity).block().rateLimitDecision().orElseThrow();
    assertThat(nextWindow.allowed()).isTrue();
    assertThat(nextWindow.remaining()).isEqualTo(4);
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
      return ZoneId.of("UTC");
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
