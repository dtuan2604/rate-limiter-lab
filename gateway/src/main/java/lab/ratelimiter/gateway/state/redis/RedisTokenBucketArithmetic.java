package lab.ratelimiter.gateway.state.redis;

import java.util.Objects;
import lab.ratelimiter.gateway.application.RedisOutcome;

public final class RedisTokenBucketArithmetic {

  private RedisTokenBucketArithmetic() {}

  public static TokenBucketTransition decide(
      TokenBucketParameters parameters, TokenBucketState storedState, long nowMilliseconds) {
    Objects.requireNonNull(parameters, "parameters");
    if (nowMilliseconds < 0) {
      throw new RedisStateException(RedisOutcome.MALFORMED_STATE, "Redis time must be nonnegative");
    }
    boolean reconstructed = storedState == null;
    TokenBucketState initial =
        reconstructed
            ? new TokenBucketState(
                parameters.initialTokensScaled(), parameters.activationMilliseconds(), 0)
            : storedState;
    validateState(parameters, initial);

    long elapsed;
    long retainedLast;
    long rollbackDelay;
    if (nowMilliseconds >= initial.lastMilliseconds()) {
      elapsed = nowMilliseconds - initial.lastMilliseconds();
      retainedLast = nowMilliseconds;
      rollbackDelay = 0;
    } else {
      rollbackDelay = initial.lastMilliseconds() - nowMilliseconds;
      if (rollbackDelay > TokenBucketParameters.MAXIMUM_CLOCK_ROLLBACK_MILLISECONDS) {
        throw new RedisStateException(
            RedisOutcome.MALFORMED_STATE, "Redis clock rollback exceeds tolerated bound");
      }
      elapsed = 0;
      retainedLast = initial.lastMilliseconds();
    }

    Refilled refilled = refill(parameters, initial, elapsed);
    boolean allowed = refilled.tokensScaled() >= parameters.requestCostScaled();
    long remaining =
        allowed
            ? refilled.tokensScaled() - parameters.requestCostScaled()
            : refilled.tokensScaled();
    long retry =
        allowed
            ? 0
            : Math.addExact(
                rollbackDelay,
                timeForTokens(
                    parameters.requestCostScaled() - remaining, refilled.remainder(), parameters));
    long reset =
        Math.addExact(
            rollbackDelay,
            timeForTokens(
                parameters.capacityScaled() - remaining, refilled.remainder(), parameters));
    return new TokenBucketTransition(
        allowed,
        new TokenBucketState(remaining, retainedLast, refilled.remainder()),
        retry,
        reset,
        reconstructed);
  }

  private static void validateState(TokenBucketParameters parameters, TokenBucketState state) {
    if (state.tokensScaled() > parameters.capacityScaled()
        || state.refillRemainder() >= parameters.refillPeriodMilliseconds()
        || state.tokensScaled() == parameters.capacityScaled() && state.refillRemainder() != 0) {
      throw new RedisStateException(
          RedisOutcome.MALFORMED_STATE, "Token Bucket state is outside policy bounds");
    }
  }

  private static Refilled refill(
      TokenBucketParameters parameters, TokenBucketState state, long elapsed) {
    if (state.tokensScaled() == parameters.capacityScaled() || elapsed == 0) {
      return new Refilled(state.tokensScaled(), state.refillRemainder());
    }
    long deficit = parameters.capacityScaled() - state.tokensScaled();
    long timeToFull = timeForTokens(deficit, state.refillRemainder(), parameters);
    if (elapsed >= timeToFull) {
      return new Refilled(parameters.capacityScaled(), 0);
    }

    long wholePeriods = elapsed / parameters.refillPeriodMilliseconds();
    long partialMilliseconds = elapsed % parameters.refillPeriodMilliseconds();
    long fullCredit = Math.multiplyExact(wholePeriods, parameters.refillTokensScaled());
    long partialNumerator =
        Math.addExact(
            Math.multiplyExact(partialMilliseconds, parameters.refillTokensScaled()),
            state.refillRemainder());
    long partialCredit = partialNumerator / parameters.refillPeriodMilliseconds();
    long remainder = partialNumerator % parameters.refillPeriodMilliseconds();
    long tokens = Math.addExact(state.tokensScaled(), Math.addExact(fullCredit, partialCredit));
    if (tokens >= parameters.capacityScaled()) {
      return new Refilled(parameters.capacityScaled(), 0);
    }
    return new Refilled(tokens, remainder);
  }

  private static long timeForTokens(
      long neededScaled, long remainder, TokenBucketParameters parameters) {
    if (neededScaled == 0) {
      return 0;
    }
    long numerator =
        Math.subtractExact(
            Math.multiplyExact(neededScaled, parameters.refillPeriodMilliseconds()), remainder);
    return ceilDivide(numerator, parameters.refillTokensScaled());
  }

  private static long ceilDivide(long numerator, long denominator) {
    return Math.floorDiv(numerator - 1, denominator) + 1;
  }

  private record Refilled(long tokensScaled, long remainder) {}
}
