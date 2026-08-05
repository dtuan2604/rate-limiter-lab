package lab.ratelimiter.gateway.state.redis;

import java.time.Instant;
import java.util.Objects;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;

public record TokenBucketParameters(
    long capacityScaled,
    long initialTokensScaled,
    long refillTokensScaled,
    long refillPeriodMilliseconds,
    long requestCostScaled,
    long activationMilliseconds) {

  public static final long SCALE = 1_000;
  public static final long MAXIMUM_SCALED_TOKENS = 100_000_000;
  public static final long MAXIMUM_REFILL_PERIOD_MILLISECONDS = 86_400_000;
  public static final long MAXIMUM_EMPTY_TO_FULL_MILLISECONDS = 2_592_000_000L;
  public static final long MAXIMUM_CLOCK_ROLLBACK_MILLISECONDS = 300_000;

  public TokenBucketParameters {
    if (capacityScaled < SCALE || capacityScaled > MAXIMUM_SCALED_TOKENS) {
      throw new IllegalArgumentException("scaled capacity is outside supported bounds");
    }
    if (initialTokensScaled < 0 || initialTokensScaled > capacityScaled) {
      throw new IllegalArgumentException("scaled initial tokens are outside capacity");
    }
    if (refillTokensScaled < SCALE || refillTokensScaled > MAXIMUM_SCALED_TOKENS) {
      throw new IllegalArgumentException("scaled refill tokens are outside supported bounds");
    }
    if (refillPeriodMilliseconds < 1
        || refillPeriodMilliseconds > MAXIMUM_REFILL_PERIOD_MILLISECONDS) {
      throw new IllegalArgumentException("refill period is outside supported bounds");
    }
    if (requestCostScaled < SCALE || requestCostScaled > capacityScaled) {
      throw new IllegalArgumentException("scaled request cost is outside capacity");
    }
    if (activationMilliseconds < 0) {
      throw new IllegalArgumentException("activation timestamp must be nonnegative");
    }
    long periodsToFull = Math.floorDiv(capacityScaled - 1, refillTokensScaled) + 1;
    if (periodsToFull
        > Math.floorDiv(MAXIMUM_EMPTY_TO_FULL_MILLISECONDS, refillPeriodMilliseconds)) {
      throw new IllegalArgumentException("empty-to-full interval exceeds 30 days");
    }
  }

  public static TokenBucketParameters from(
      TokenBucketAlgorithmDefinition definition, Instant activationTime) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(activationTime, "activationTime");
    return ofTokens(
        definition.capacity(),
        definition.initialTokens(),
        definition.refillTokens(),
        definition.refillPeriod().toMilliseconds(),
        definition.requestCost(),
        activationTime.toEpochMilli());
  }

  public static TokenBucketParameters ofTokens(
      long capacity,
      long initialTokens,
      long refillTokens,
      long refillPeriodMilliseconds,
      long requestCost,
      long activationMilliseconds) {
    try {
      return new TokenBucketParameters(
          Math.multiplyExact(capacity, SCALE),
          Math.multiplyExact(initialTokens, SCALE),
          Math.multiplyExact(refillTokens, SCALE),
          refillPeriodMilliseconds,
          Math.multiplyExact(requestCost, SCALE),
          activationMilliseconds);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("token scaling overflow", exception);
    }
  }
}
