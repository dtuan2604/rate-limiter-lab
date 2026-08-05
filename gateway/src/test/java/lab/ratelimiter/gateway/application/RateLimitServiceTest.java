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
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.CompiledTokenBucketAlgorithm;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

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

  @Test
  void tokenBucketDispatchesToItsTypedAdapterWithConfiguredCostAndAnchor() {
    Instant activation = START.minusSeconds(2);
    CompiledPolicy tokenPolicy =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new CompiledTokenBucketAlgorithm(
                new TokenBucketPolicy(
                    new PolicyId("catalog-token"),
                    new PolicyVersion(2),
                    10,
                    10,
                    2,
                    Duration.ofSeconds(1)),
                3,
                activation),
            FailureMode.FAIL_CLOSED,
            100);
    LimiterIdentity identity =
        identityExtractor.extract("client-token", tokenPolicy.routeId()).orElseThrow();
    FixedWindowStateAdapter fixed =
        (policy, ignoredIdentity, request) ->
            Mono.error(new AssertionError("Fixed Window adapter must not be called"));
    TokenBucketStateAdapter token =
        (policy, cost, activatedAt, ignoredIdentity) -> {
          assertThat(policy.policyId().value()).isEqualTo("catalog-token");
          assertThat(cost).isEqualTo(3);
          assertThat(activatedAt).isEqualTo(activation);
          RateLimitDecision decision =
              new RateLimitDecision(
                  true,
                  10,
                  7,
                  java.util.Optional.empty(),
                  java.util.Optional.of(START.plusSeconds(2)),
                  policy.policyId(),
                  policy.policyVersion(),
                  policy.algorithm());
          return Mono.just(
              new TokenBucketStateResult(
                  decision,
                  7_000,
                  3_000,
                  2_000,
                  Duration.ofSeconds(1),
                  Duration.ZERO,
                  Duration.ofSeconds(2),
                  START,
                  Duration.ofSeconds(2),
                  0,
                  false,
                  StateBackend.REDIS,
                  RedisOutcome.ALLOWED));
        };

    RateLimitEvaluation evaluation =
        new RateLimitService(fixed, token).evaluate(tokenPolicy, identity).block();

    assertThat(evaluation.outcome()).isEqualTo(RateLimitOutcome.ALLOW);
    assertThat(evaluation.tokenBucketResult()).isPresent();
    assertThat(evaluation.rateLimitDecision().orElseThrow().remaining()).isEqualTo(7);
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
