package lab.ratelimiter.gateway.policy.control;

import java.time.Duration;
import java.util.Objects;

public record TokenBucketAlgorithmDefinition(
    long capacity,
    long initialTokens,
    long refillTokens,
    RefillPeriod refillPeriod,
    long requestCost)
    implements PolicyAlgorithmDefinition {

  public static final long MAXIMUM_TOKENS = 100_000;
  public static final long SCALE = 1_000;
  public static final long MAXIMUM_EMPTY_TO_FULL_MILLISECONDS = Duration.ofDays(30).toMillis();

  public TokenBucketAlgorithmDefinition {
    requireTokenBound(capacity, "capacity");
    if (initialTokens < 0 || initialTokens > capacity) {
      throw new IllegalArgumentException("initial tokens must be between 0 and capacity");
    }
    requireTokenBound(refillTokens, "refill tokens");
    Objects.requireNonNull(refillPeriod, "refillPeriod");
    requireTokenBound(requestCost, "request cost");
    if (requestCost > capacity) {
      throw new IllegalArgumentException("request cost must not exceed capacity");
    }
    long periodsToFull = Math.floorDiv(capacity - 1, refillTokens) + 1;
    if (periodsToFull
        > Math.floorDiv(MAXIMUM_EMPTY_TO_FULL_MILLISECONDS, refillPeriod.toMilliseconds())) {
      throw new IllegalArgumentException("empty-to-full interval must not exceed 30 days");
    }
  }

  private static void requireTokenBound(long value, String name) {
    if (value < 1 || value > MAXIMUM_TOKENS) {
      throw new IllegalArgumentException(name + " must be between 1 and 100000");
    }
  }

  @Override
  public PolicyAlgorithmType type() {
    return PolicyAlgorithmType.TOKEN_BUCKET;
  }
}
