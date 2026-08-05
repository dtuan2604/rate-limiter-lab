package lab.ratelimiter.gateway.state.redis;

import lab.ratelimiter.gateway.application.RedisOutcome;

public record TokenBucketState(long tokensScaled, long lastMilliseconds, long refillRemainder) {

  public TokenBucketState {
    if (tokensScaled < 0 || lastMilliseconds < 0 || refillRemainder < 0) {
      throw new RedisStateException(
          RedisOutcome.MALFORMED_STATE, "Token Bucket state contains a negative integer");
    }
  }
}
