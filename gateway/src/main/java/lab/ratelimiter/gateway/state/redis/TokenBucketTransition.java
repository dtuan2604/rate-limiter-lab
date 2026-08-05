package lab.ratelimiter.gateway.state.redis;

import java.util.Objects;

public record TokenBucketTransition(
    boolean allowed,
    TokenBucketState state,
    long retryAfterMilliseconds,
    long resetAfterMilliseconds,
    boolean reconstructed) {

  public TokenBucketTransition {
    Objects.requireNonNull(state, "state");
    if (retryAfterMilliseconds < 0 || resetAfterMilliseconds < 0) {
      throw new IllegalArgumentException("Token Bucket timing must be nonnegative");
    }
  }
}
