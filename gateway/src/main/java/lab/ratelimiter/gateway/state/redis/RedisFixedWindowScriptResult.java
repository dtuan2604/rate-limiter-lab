package lab.ratelimiter.gateway.state.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lab.ratelimiter.gateway.application.FixedWindowStateResult;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;

public record RedisFixedWindowScriptResult(
    RedisOutcome outcome,
    long currentCount,
    long remaining,
    long limit,
    Duration retryAfter,
    Instant resetAt,
    Instant redisNow,
    long windowId,
    Duration ttl) {

  private static final int CONTRACT_VERSION = 1;
  private static final int RESULT_SIZE = 10;
  private static final long MAXIMUM_LIMIT = 1_000_000;

  public RedisFixedWindowScriptResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(retryAfter, "retryAfter");
    Objects.requireNonNull(resetAt, "resetAt");
    Objects.requireNonNull(redisNow, "redisNow");
    Objects.requireNonNull(ttl, "ttl");
  }

  public static RedisFixedWindowScriptResult decode(List<?> tuple) {
    if (tuple == null || tuple.size() != RESULT_SIZE) {
      throw malformed("script result must contain exactly " + RESULT_SIZE + " integers");
    }
    long[] values = new long[RESULT_SIZE];
    for (int index = 0; index < RESULT_SIZE; index++) {
      Object value = tuple.get(index);
      if (!(value instanceof Number number)
          || number.longValue() != number.doubleValue()
          || number instanceof Float
          || number instanceof Double) {
        throw malformed("script result element " + index + " must be an integer");
      }
      values[index] = number.longValue();
    }
    if (values[0] != CONTRACT_VERSION) {
      throw malformed("unsupported script result contract version");
    }
    RedisOutcome outcome = decodeOutcome(values[1]);
    long current = values[2];
    long remaining = values[3];
    long limit = values[4];
    long retry = values[5];
    long reset = values[6];
    long now = values[7];
    long window = values[8];
    long ttl = values[9];

    if (limit <= 0 || limit > MAXIMUM_LIMIT) {
      throw malformed("script limit is outside the supported range");
    }
    if (current < 0 || current > limit || remaining < 0 || remaining > limit || window < 0) {
      throw malformed("script count, remaining, or window is outside its range");
    }
    if (now < 0 || reset <= now || ttl <= 0 || reset - now != ttl) {
      throw malformed("script time and TTL fields are inconsistent");
    }
    if (outcome != RedisOutcome.WINDOW_MISMATCH_EXHAUSTED) {
      if (remaining != limit - current) {
        throw malformed("script remaining capacity does not match current count");
      }
      if (outcome == RedisOutcome.ALLOWED && retry != 0) {
        throw malformed("allowed script result must have zero retry duration");
      }
      if (outcome == RedisOutcome.REJECTED && retry != ttl) {
        throw malformed("rejected script result retry must equal the boundary TTL");
      }
    } else if (retry != 0) {
      throw malformed("window mismatch must not include a retry duration");
    }

    return new RedisFixedWindowScriptResult(
        outcome,
        current,
        remaining,
        limit,
        Duration.ofMillis(retry),
        Instant.ofEpochMilli(reset),
        Instant.ofEpochMilli(now),
        window,
        Duration.ofMillis(ttl));
  }

  public boolean windowMismatch() {
    return outcome == RedisOutcome.WINDOW_MISMATCH_EXHAUSTED;
  }

  public FixedWindowStateResult toStateResult(FixedWindowPolicy policy) {
    Objects.requireNonNull(policy, "policy");
    if (windowMismatch()) {
      throw new IllegalStateException("a window mismatch is not a rate-limit decision");
    }
    if (policy.limit() != limit) {
      throw malformed("script limit does not match the selected policy");
    }
    boolean allowed = outcome == RedisOutcome.ALLOWED;
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            limit,
            remaining,
            allowed ? Optional.empty() : Optional.of(retryAfter),
            Optional.of(resetAt),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new FixedWindowStateResult(decision, currentCount, ttl, StateBackend.REDIS, outcome);
  }

  private static RedisOutcome decodeOutcome(long value) {
    return switch ((int) value) {
      case 0 -> RedisOutcome.REJECTED;
      case 1 -> RedisOutcome.ALLOWED;
      case 2 -> RedisOutcome.WINDOW_MISMATCH_EXHAUSTED;
      default -> throw malformed("unknown script outcome");
    };
  }

  private static RedisStateException malformed(String message) {
    return new RedisStateException(RedisOutcome.MALFORMED_RESPONSE, message);
  }
}
