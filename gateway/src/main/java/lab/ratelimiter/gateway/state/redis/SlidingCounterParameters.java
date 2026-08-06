package lab.ratelimiter.gateway.state.redis;

public record SlidingCounterParameters(long limit, long windowMilliseconds, long requestCost) {

  public static final long MAXIMUM_LIMIT = 1_000_000;
  public static final long MAXIMUM_WINDOW_MILLISECONDS = 86_400_000;
  public static final long MAXIMUM_LUA_SAFE_INTEGER = 9_007_199_254_740_991L;

  public SlidingCounterParameters {
    if (limit < 1 || limit > MAXIMUM_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and 1000000");
    }
    if (windowMilliseconds < 1 || windowMilliseconds > MAXIMUM_WINDOW_MILLISECONDS) {
      throw new IllegalArgumentException("window must be between 1 and 86400000 milliseconds");
    }
    if (requestCost < 1 || requestCost > limit) {
      throw new IllegalArgumentException("request cost must be between 1 and limit");
    }
    long scaledLimit = Math.multiplyExact(limit, windowMilliseconds);
    if (Math.multiplyExact(3, scaledLimit) > MAXIMUM_LUA_SAFE_INTEGER) {
      throw new IllegalArgumentException(
          "sliding counter arithmetic exceeds Lua safe integer range");
    }
  }

  public long scaledLimit() {
    return Math.multiplyExact(limit, windowMilliseconds);
  }

  public long scaledRequestCost() {
    return Math.multiplyExact(requestCost, windowMilliseconds);
  }
}
