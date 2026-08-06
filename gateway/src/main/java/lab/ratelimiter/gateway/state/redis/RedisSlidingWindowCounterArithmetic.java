package lab.ratelimiter.gateway.state.redis;

import java.util.Objects;
import lab.ratelimiter.gateway.application.RedisOutcome;

public final class RedisSlidingWindowCounterArithmetic {

  private RedisSlidingWindowCounterArithmetic() {}

  public static SlidingCounterTransition decide(
      SlidingCounterParameters parameters, SlidingCounterState storedState, long nowMilliseconds) {
    Objects.requireNonNull(parameters, "parameters");
    if (nowMilliseconds < 0) {
      throw new RedisStateException(RedisOutcome.MALFORMED_STATE, "Redis time must be nonnegative");
    }
    long window = parameters.windowMilliseconds();
    long currentWindowId = nowMilliseconds / window;
    long currentWindowStart = Math.multiplyExact(currentWindowId, window);
    long elapsed = nowMilliseconds - currentWindowStart;

    Rotated rotated = rotate(parameters, storedState, currentWindowId);
    long current = rotated.state().currentCount();
    long previous = rotated.state().previousCount();
    long numerator = weightedNumerator(current, previous, window, elapsed);
    boolean allowed =
        Math.addExact(numerator, parameters.scaledRequestCost()) <= parameters.scaledLimit();
    long postCurrent = allowed ? Math.addExact(current, parameters.requestCost()) : current;
    long postNumerator =
        allowed ? Math.addExact(numerator, parameters.scaledRequestCost()) : numerator;
    SlidingCounterState postState = new SlidingCounterState(currentWindowId, postCurrent, previous);
    long retry = allowed ? 0 : retryAfter(parameters, current, previous, elapsed, numerator);
    long reset = resetAfter(window, elapsed, postCurrent, previous);
    return new SlidingCounterTransition(
        allowed,
        postState,
        currentWindowStart,
        elapsed,
        postNumerator,
        ceilDivide(postNumerator, window),
        remainingCapacity(parameters.scaledLimit(), postNumerator, window),
        retry,
        reset,
        rotated.rotation());
  }

  private static Rotated rotate(
      SlidingCounterParameters parameters, SlidingCounterState storedState, long currentWindowId) {
    if (storedState == null) {
      return new Rotated(
          new SlidingCounterState(currentWindowId, 0, 0), SlidingCounterRotation.MISSING);
    }
    validateState(parameters, storedState);
    if (currentWindowId < storedState.windowId()) {
      throw new RedisStateException(
          RedisOutcome.CLOCK_ROLLBACK, "Redis clock moved into an older Sliding Counter window");
    }
    long advancement = currentWindowId - storedState.windowId();
    if (advancement == 0) {
      return new Rotated(storedState, SlidingCounterRotation.SAME);
    }
    if (advancement == 1) {
      return new Rotated(
          new SlidingCounterState(currentWindowId, 0, storedState.currentCount()),
          SlidingCounterRotation.ADVANCE_ONE);
    }
    return new Rotated(
        new SlidingCounterState(currentWindowId, 0, 0), SlidingCounterRotation.ADVANCE_MANY);
  }

  private static void validateState(
      SlidingCounterParameters parameters, SlidingCounterState state) {
    if (state.currentCount() > parameters.limit() || state.previousCount() > parameters.limit()) {
      throw new RedisStateException(
          RedisOutcome.MALFORMED_STATE, "Sliding Counter counts exceed the configured limit");
    }
  }

  private static long weightedNumerator(long current, long previous, long window, long elapsed) {
    return Math.addExact(
        Math.multiplyExact(current, window), Math.multiplyExact(previous, window - elapsed));
  }

  private static long retryAfter(
      SlidingCounterParameters parameters,
      long current,
      long previous,
      long elapsed,
      long numerator) {
    long window = parameters.windowMilliseconds();
    long threshold = Math.multiplyExact(parameters.limit() - parameters.requestCost(), window);
    long currentNumerator = Math.multiplyExact(current, window);
    if (currentNumerator <= threshold) {
      if (previous == 0) {
        throw new IllegalStateException("rejected Sliding Counter state has no decaying usage");
      }
      return ceilDivide(numerator - threshold, previous);
    }
    if (current == 0) {
      throw new IllegalStateException("rejected Sliding Counter state has no current usage");
    }
    return Math.addExact(window - elapsed, ceilDivide(currentNumerator - threshold, current));
  }

  private static long resetAfter(long window, long elapsed, long current, long previous) {
    if (current > 0) {
      return Math.subtractExact(Math.multiplyExact(2, window), elapsed);
    }
    return previous > 0 ? window - elapsed : 0;
  }

  private static long remainingCapacity(long scaledLimit, long numerator, long window) {
    if (numerator >= scaledLimit) {
      return 0;
    }
    return (scaledLimit - numerator) / window;
  }

  private static long ceilDivide(long numerator, long denominator) {
    return numerator == 0 ? 0 : Math.floorDiv(numerator - 1, denominator) + 1;
  }

  private record Rotated(SlidingCounterState state, SlidingCounterRotation rotation) {}
}
