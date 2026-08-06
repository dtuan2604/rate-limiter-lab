package lab.ratelimiter.gateway.observability;

import java.util.Objects;
import lab.ratelimiter.gateway.application.RateLimitEvaluation;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

public final class RateLimitDecisionLogger {

  private static final int LOG_IDENTITY_HASH_LENGTH = 16;
  private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitDecisionLogger.class);

  private final String gatewayInstance;

  public RateLimitDecisionLogger(String gatewayInstance) {
    this.gatewayInstance = Objects.requireNonNull(gatewayInstance, "gatewayInstance");
  }

  public void log(
      String correlationId,
      CompiledPolicy policy,
      LimiterIdentity identity,
      RateLimitEvaluation evaluation) {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(evaluation, "evaluation");
    LoggingEventBuilder event =
        LOGGER
            .atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("gatewayInstance", gatewayInstance)
            .addKeyValue("policyId", policy.policy().policyId().value())
            .addKeyValue("policyVersion", policy.policy().policyVersion().value())
            .addKeyValue("algorithm", policy.policy().algorithm().name())
            .addKeyValue("stateBackend", evaluation.stateBackend().name())
            .addKeyValue(
                "rateLimitIdentityHash", identity.digest().substring(0, LOG_IDENTITY_HASH_LENGTH))
            .addKeyValue("decision", evaluation.outcome().name())
            .addKeyValue("degraded", evaluation.degraded())
            .addKeyValue("failureMode", evaluation.failureMode().name())
            .addKeyValue("redisOutcome", evaluation.redisOutcome().name());
    evaluation
        .tokenBucketResult()
        .ifPresent(
            tokenBucket ->
                event
                    .addKeyValue("capacity", tokenBucket.decision().limit())
                    .addKeyValue("requestCost", tokenBucket.requestCostScaled() / 1_000)
                    .addKeyValue("remainingScaledTokens", tokenBucket.remainingScaledTokens())
                    .addKeyValue("refillTokens", tokenBucket.refillTokensScaled() / 1_000)
                    .addKeyValue("refillPeriod", tokenBucket.refillPeriod())
                    .addKeyValue("retryAfter", tokenBucket.retryAfter())
                    .addKeyValue("tokenBucketOutcome", tokenBucket.redisOutcome().name())
                    .addKeyValue("stateReconstructed", tokenBucket.stateReconstructed()));
    evaluation
        .slidingWindowCounterResult()
        .ifPresent(
            slidingCounter ->
                event
                    .addKeyValue("currentWindowId", slidingCounter.currentWindowId())
                    .addKeyValue("currentWindowCount", slidingCounter.currentWindowCount())
                    .addKeyValue("previousWindowCount", slidingCounter.previousWindowCount())
                    .addKeyValue("windowElapsed", slidingCounter.windowElapsed())
                    .addKeyValue("weightedNumerator", slidingCounter.weightedNumerator())
                    .addKeyValue("weightedEstimate", slidingCounter.weightedEstimate())
                    .addKeyValue("requestCost", slidingCounter.requestCost())
                    .addKeyValue("remainingCapacity", slidingCounter.remainingCapacity())
                    .addKeyValue("retryAfter", slidingCounter.retryAfter())
                    .addKeyValue("windowRotation", slidingCounter.rotation().name())
                    .addKeyValue("slidingCounterOutcome", slidingCounter.redisOutcome().name()));
    event.log("rate limit decision");
  }
}
