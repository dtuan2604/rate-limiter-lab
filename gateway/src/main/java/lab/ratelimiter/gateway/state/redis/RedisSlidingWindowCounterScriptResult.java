package lab.ratelimiter.gateway.state.redis;

import java.util.List;
import java.util.Objects;
import lab.ratelimiter.gateway.application.RedisOutcome;

public record RedisSlidingWindowCounterScriptResult(
    RedisOutcome outcome,
    long limit,
    long windowMilliseconds,
    long requestCost,
    long currentWindowId,
    long currentWindowStartMilliseconds,
    long elapsedMilliseconds,
    long currentWindowCount,
    long previousWindowCount,
    long weightedNumerator,
    long weightedEstimate,
    long remainingCapacity,
    long retryAfterMilliseconds,
    long resetAfterMilliseconds,
    long redisNowMilliseconds,
    long ttlMilliseconds,
    SlidingCounterRotation rotation) {

  private static final int CONTRACT_VERSION = 1;
  private static final int RESULT_SIZE = 18;

  public RedisSlidingWindowCounterScriptResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(rotation, "rotation");
  }

  public static RedisSlidingWindowCounterScriptResult decode(
      List<?> tuple, SlidingCounterParameters parameters) {
    Objects.requireNonNull(parameters, "parameters");
    if (tuple == null || tuple.size() != RESULT_SIZE) {
      throw malformed("script result must contain exactly " + RESULT_SIZE + " integers");
    }
    long[] values = integers(tuple);
    if (values[0] != CONTRACT_VERSION) {
      throw malformed("unsupported script result contract version");
    }
    RedisOutcome outcome =
        switch (Math.toIntExact(values[1])) {
          case 0 -> RedisOutcome.REJECTED;
          case 1 -> RedisOutcome.ALLOWED;
          default -> throw malformed("unknown script outcome");
        };
    if (values[2] != parameters.limit()
        || values[3] != parameters.windowMilliseconds()
        || values[4] != parameters.requestCost()) {
      throw malformed("script policy fields do not match the selected policy");
    }
    SlidingCounterRotation rotation = rotation(values[17]);
    long windowId = values[5];
    long start = values[6];
    long elapsed = values[7];
    long current = values[8];
    long previous = values[9];
    long numerator = values[10];
    long estimate = values[11];
    long remaining = values[12];
    long retry = values[13];
    long reset = values[14];
    long now = values[15];
    long ttl = values[16];
    if (windowId < 0
        || start < 0
        || elapsed < 0
        || elapsed >= parameters.windowMilliseconds()
        || current < 0
        || current > parameters.limit()
        || previous < 0
        || previous > parameters.limit()
        || numerator < 0
        || remaining < 0
        || remaining > parameters.limit()
        || retry < 0
        || reset <= 0
        || reset > 2 * parameters.windowMilliseconds()
        || now < 0
        || ttl != reset) {
      throw malformed("script state, timing, or capacity field is invalid");
    }
    long expectedStart;
    long expectedNow;
    long expectedNumerator;
    try {
      expectedStart = Math.multiplyExact(windowId, parameters.windowMilliseconds());
      expectedNow = Math.addExact(start, elapsed);
      expectedNumerator =
          Math.addExact(
              Math.multiplyExact(current, parameters.windowMilliseconds()),
              Math.multiplyExact(previous, parameters.windowMilliseconds() - elapsed));
    } catch (ArithmeticException exception) {
      throw malformed("script result arithmetic overflow", exception);
    }
    long expectedEstimate = ceilDivide(expectedNumerator, parameters.windowMilliseconds());
    long expectedRemaining =
        expectedNumerator >= parameters.scaledLimit()
            ? 0
            : (parameters.scaledLimit() - expectedNumerator) / parameters.windowMilliseconds();
    long expectedReset =
        current > 0
            ? 2 * parameters.windowMilliseconds() - elapsed
            : parameters.windowMilliseconds() - elapsed;
    if (start != expectedStart
        || now != expectedNow
        || numerator != expectedNumerator
        || estimate != expectedEstimate
        || remaining != expectedRemaining
        || reset != expectedReset) {
      throw malformed("script derived fields are inconsistent");
    }
    if (outcome == RedisOutcome.ALLOWED && retry != 0) {
      throw malformed("allowed script result must have zero retry duration");
    }
    if (outcome == RedisOutcome.REJECTED) {
      SlidingCounterTransition reference =
          RedisSlidingWindowCounterArithmetic.decide(
              parameters, new SlidingCounterState(windowId, current, previous), now);
      if (reference.allowed() || retry != reference.retryAfterMilliseconds()) {
        throw malformed("rejected script retry duration is inconsistent");
      }
    }
    return new RedisSlidingWindowCounterScriptResult(
        outcome, values[2], values[3], values[4], windowId, start, elapsed, current, previous,
        numerator, estimate, remaining, retry, reset, now, ttl, rotation);
  }

  private static long[] integers(List<?> tuple) {
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
    return values;
  }

  private static SlidingCounterRotation rotation(long code) {
    for (SlidingCounterRotation value : SlidingCounterRotation.values()) {
      if (value.code() == code) {
        return value;
      }
    }
    throw malformed("unknown script rotation");
  }

  private static long ceilDivide(long numerator, long denominator) {
    return numerator == 0 ? 0 : Math.floorDiv(numerator - 1, denominator) + 1;
  }

  private static RedisStateException malformed(String message) {
    return new RedisStateException(RedisOutcome.MALFORMED_RESPONSE, message);
  }

  private static RedisStateException malformed(String message, Throwable cause) {
    return new RedisStateException(RedisOutcome.MALFORMED_RESPONSE, message, cause);
  }
}
