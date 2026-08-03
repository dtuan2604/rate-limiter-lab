package lab.ratelimiter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.state.redis.RedisStateException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RateLimitFailureModeTest {

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
  private static final LimiterIdentity IDENTITY =
      new ClientIdentityExtractor().extract("client-a", POLICY.routeId()).orElseThrow();

  @Test
  void failOpenConvertsEveryRedisFailureToDegradedAllowWithoutLocalState() {
    for (RedisOutcome outcome : failureOutcomes()) {
      AtomicInteger calls = new AtomicInteger();
      FixedWindowStateAdapter failing =
          (policy, identity, request) -> {
            calls.incrementAndGet();
            return Mono.error(new RedisStateException(outcome, "sanitized"));
          };

      RateLimitEvaluation evaluation =
          new RateLimitService(failing, FailureMode.FAIL_OPEN).evaluate(POLICY, IDENTITY).block();

      assertThat(evaluation.outcome()).isEqualTo(RateLimitOutcome.DEGRADED_ALLOW);
      assertThat(evaluation.rateLimitDecision()).isEmpty();
      assertThat(evaluation.resetAfter()).isEmpty();
      assertThat(evaluation.stateBackend()).isEqualTo(StateBackend.REDIS);
      assertThat(evaluation.redisOutcome()).isEqualTo(outcome);
      assertThat(evaluation.degraded()).isTrue();
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void failClosedConvertsEveryRedisFailureToUnavailableWithoutLocalState() {
    for (RedisOutcome outcome : failureOutcomes()) {
      AtomicInteger calls = new AtomicInteger();
      FixedWindowStateAdapter failing =
          (policy, identity, request) -> {
            calls.incrementAndGet();
            return Mono.error(new RedisStateException(outcome, "sanitized"));
          };

      RateLimitEvaluation evaluation =
          new RateLimitService(failing, FailureMode.FAIL_CLOSED).evaluate(POLICY, IDENTITY).block();

      assertThat(evaluation.outcome()).isEqualTo(RateLimitOutcome.STATE_UNAVAILABLE);
      assertThat(evaluation.rateLimitDecision()).isEmpty();
      assertThat(evaluation.resetAfter()).isEmpty();
      assertThat(evaluation.stateBackend()).isEqualTo(StateBackend.REDIS);
      assertThat(evaluation.redisOutcome()).isEqualTo(outcome);
      assertThat(evaluation.degraded()).isTrue();
      assertThat(calls).hasValue(1);
    }
  }

  private static RedisOutcome[] failureOutcomes() {
    return new RedisOutcome[] {
      RedisOutcome.TIMEOUT,
      RedisOutcome.CONNECTION_FAILURE,
      RedisOutcome.SCRIPT_ERROR,
      RedisOutcome.MALFORMED_STATE,
      RedisOutcome.MALFORMED_RESPONSE,
      RedisOutcome.WINDOW_MISMATCH_EXHAUSTED
    };
  }
}
