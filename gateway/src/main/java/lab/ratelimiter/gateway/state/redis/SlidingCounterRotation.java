package lab.ratelimiter.gateway.state.redis;

public enum SlidingCounterRotation {
  MISSING(0),
  SAME(1),
  ADVANCE_ONE(2),
  ADVANCE_MANY(3);

  private final int code;

  SlidingCounterRotation(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }
}
