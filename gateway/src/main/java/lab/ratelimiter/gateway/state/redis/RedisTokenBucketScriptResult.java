package lab.ratelimiter.gateway.state.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.application.TokenBucketStateResult;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;

public record RedisTokenBucketScriptResult(
    RedisOutcome outcome,
    long capacityScaled,
    long remainingScaledTokens,
    long requestCostScaled,
    long refillTokensScaled,
    long refillPeriodMilliseconds,
    long retryAfterMilliseconds,
    long resetAfterMilliseconds,
    long redisNowMilliseconds,
    long ttlMilliseconds,
    long refillRemainder,
    boolean stateReconstructed) {

  private static final int CONTRACT_VERSION = 1;
  private static final int RESULT_SIZE = 13;

  public RedisTokenBucketScriptResult {
    Objects.requireNonNull(outcome, "outcome");
  }

  public static RedisTokenBucketScriptResult decode(
      List<?> tuple, TokenBucketParameters parameters) {
    Objects.requireNonNull(parameters, "parameters");
    if (tuple == null || tuple.size() != RESULT_SIZE) {
      throw malformed("script result must contain exactly " + RESULT_SIZE + " integers");
    }
    long[] values = new long[RESULT_SIZE];
    for (int index = 0; index < RESULT_SIZE; index++) {
      Object value = tuple.get(index);
      if (!(value instanceof Number number)
          || number instanceof Float
          || number instanceof Double
          || number.longValue() != number.doubleValue()) {
        throw malformed("script result element " + index + " must be an integer");
      }
      values[index] = number.longValue();
    }
    if (values[0] != CONTRACT_VERSION) {
      throw malformed("unsupported script result contract version");
    }
    RedisOutcome outcome =
        switch ((int) values[1]) {
          case 0 -> RedisOutcome.REJECTED;
          case 1 -> RedisOutcome.ALLOWED;
          default -> throw malformed("unknown script outcome");
        };
    if (values[2] != parameters.capacityScaled()
        || values[4] != parameters.requestCostScaled()
        || values[5] != parameters.refillTokensScaled()
        || values[6] != parameters.refillPeriodMilliseconds()) {
      throw malformed("script policy fields do not match the selected policy");
    }
    long remaining = values[3];
    long retry = values[7];
    long reset = values[8];
    long now = values[9];
    long ttl = values[10];
    long remainder = values[11];
    long reconstructed = values[12];
    if (remaining < 0
        || remaining > parameters.capacityScaled()
        || retry < 0
        || reset <= 0
        || now < 0
        || ttl != reset
        || remainder < 0
        || remainder >= parameters.refillPeriodMilliseconds()
        || reconstructed < 0
        || reconstructed > 1) {
      throw malformed("script balance, timing, remainder, or reconstruction field is invalid");
    }
    if (outcome == RedisOutcome.ALLOWED && retry != 0) {
      throw malformed("allowed script result must have zero retry duration");
    }
    if (outcome == RedisOutcome.REJECTED && (retry <= 0 || retry > reset)) {
      throw malformed("rejected script retry duration is invalid");
    }
    return new RedisTokenBucketScriptResult(
        outcome,
        values[2],
        remaining,
        values[4],
        values[5],
        values[6],
        retry,
        reset,
        now,
        ttl,
        remainder,
        reconstructed == 1);
  }

  public TokenBucketStateResult toStateResult(TokenBucketPolicy policy) {
    Objects.requireNonNull(policy, "policy");
    boolean allowed = outcome == RedisOutcome.ALLOWED;
    Duration retry = Duration.ofMillis(retryAfterMilliseconds);
    Duration reset = Duration.ofMillis(resetAfterMilliseconds);
    Instant now = Instant.ofEpochMilli(redisNowMilliseconds);
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            capacityScaled / TokenBucketParameters.SCALE,
            remainingScaledTokens / TokenBucketParameters.SCALE,
            allowed ? Optional.empty() : Optional.of(retry),
            Optional.of(now.plus(reset)),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new TokenBucketStateResult(
        decision,
        remainingScaledTokens,
        requestCostScaled,
        refillTokensScaled,
        Duration.ofMillis(refillPeriodMilliseconds),
        retry,
        reset,
        now,
        Duration.ofMillis(ttlMilliseconds),
        refillRemainder,
        stateReconstructed,
        StateBackend.REDIS,
        outcome);
  }

  private static RedisStateException malformed(String message) {
    return new RedisStateException(RedisOutcome.MALFORMED_RESPONSE, message);
  }
}
