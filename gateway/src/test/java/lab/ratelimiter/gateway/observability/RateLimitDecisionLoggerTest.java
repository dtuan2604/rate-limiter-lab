package lab.ratelimiter.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.application.RateLimitEvaluation;
import lab.ratelimiter.gateway.application.RateLimitOutcome;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.application.TokenBucketStateResult;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RateLimitDecisionLoggerTest {

  @Test
  void logsEveryRequiredSanitizedDecisionField(CapturedOutput output) {
    CompiledPolicy policy =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new FixedWindowPolicy(
                new PolicyId("catalog-client-fixed-window"),
                new PolicyVersion(1),
                5,
                Duration.ofSeconds(10)));
    LimiterIdentity identity =
        new ClientIdentityExtractor().extract("secret-client", policy.routeId()).orElseThrow();
    RateLimitEvaluation evaluation =
        new RateLimitEvaluation(
            RateLimitOutcome.DEGRADED_ALLOW,
            Optional.empty(),
            Optional.empty(),
            StateBackend.REDIS,
            RedisOutcome.TIMEOUT,
            FailureMode.FAIL_OPEN);

    new RateLimitDecisionLogger("gateway-2").log("correlation-1", policy, identity, evaluation);

    String log = output.getAll();
    assertField(log, "correlationId", "correlation-1");
    assertField(log, "gatewayInstance", "gateway-2");
    assertField(log, "policyId", "catalog-client-fixed-window");
    assertField(log, "policyVersion", "1");
    assertField(log, "algorithm", "FIXED_WINDOW");
    assertField(log, "stateBackend", "REDIS");
    assertField(log, "rateLimitIdentityHash", identity.digest().substring(0, 16));
    assertField(log, "decision", "DEGRADED_ALLOW");
    assertField(log, "degraded", "true");
    assertField(log, "failureMode", "FAIL_OPEN");
    assertField(log, "redisOutcome", "TIMEOUT");
    assertThat(log).doesNotContain("secret-client").doesNotContain(identity.digest());
  }

  @Test
  void logsTokenBucketFieldsWithoutRawIdentity(CapturedOutput output) {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    TokenBucketPolicy tokenPolicy =
        new TokenBucketPolicy(
            new PolicyId("catalog-token"), new PolicyVersion(2), 10, 10, 2, Duration.ofSeconds(1));
    CompiledPolicy policy =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new CompiledTokenBucketAlgorithm(tokenPolicy, 3, now.minusSeconds(5)),
            FailureMode.FAIL_CLOSED,
            100);
    LimiterIdentity identity =
        new ClientIdentityExtractor()
            .extract("secret-token-client", policy.routeId())
            .orElseThrow();
    RateLimitDecision decision =
        new RateLimitDecision(
            false,
            10,
            1,
            Optional.of(Duration.ofMillis(750)),
            Optional.of(now.plusMillis(4_500)),
            tokenPolicy.policyId(),
            tokenPolicy.policyVersion(),
            tokenPolicy.algorithm());
    TokenBucketStateResult tokenResult =
        new TokenBucketStateResult(
            decision,
            1_500,
            3_000,
            2_000,
            Duration.ofSeconds(1),
            Duration.ofMillis(750),
            Duration.ofMillis(4_500),
            now,
            Duration.ofMillis(4_500),
            250,
            true,
            StateBackend.REDIS,
            RedisOutcome.REJECTED);
    RateLimitEvaluation evaluation =
        new RateLimitEvaluation(
            RateLimitOutcome.REJECT,
            Optional.of(decision),
            Optional.of(tokenResult.resetAfter()),
            Optional.of(tokenResult),
            StateBackend.REDIS,
            RedisOutcome.REJECTED,
            FailureMode.FAIL_CLOSED);

    new RateLimitDecisionLogger("gateway-3").log("correlation-2", policy, identity, evaluation);

    String log = output.getAll();
    assertField(log, "capacity", "10");
    assertField(log, "requestCost", "3");
    assertField(log, "remainingScaledTokens", "1500");
    assertField(log, "refillTokens", "2");
    assertField(log, "refillPeriod", "PT1S");
    assertField(log, "retryAfter", "PT0.75S");
    assertField(log, "tokenBucketOutcome", "REJECTED");
    assertField(log, "stateReconstructed", "true");
    assertThat(log).doesNotContain("secret-token-client").doesNotContain(identity.digest());
  }

  private static void assertField(String log, String name, String value) {
    assertThat(log)
        .containsPattern(Pattern.quote(name) + "(?:\"?:|=)\"?" + Pattern.quote(value) + "\"?");
  }
}
