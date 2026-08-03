package lab.ratelimiter.gateway.state.redis;

import java.util.Objects;
import lab.ratelimiter.gateway.application.RedisOutcome;

public final class RedisStateException extends RuntimeException {

  private static final long serialVersionUID = 1L;
  private final RedisOutcome outcome;

  public RedisStateException(RedisOutcome outcome, String message) {
    super(message);
    this.outcome = Objects.requireNonNull(outcome, "outcome");
  }

  public RedisStateException(RedisOutcome outcome, String message, Throwable cause) {
    super(message, cause);
    this.outcome = Objects.requireNonNull(outcome, "outcome");
  }

  public RedisOutcome outcome() {
    return outcome;
  }
}
