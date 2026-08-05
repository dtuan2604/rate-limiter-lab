package lab.ratelimiter.gateway.policy;

import java.time.Instant;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.state.redis.TokenBucketParameters;

public record CompiledTokenBucketAlgorithm(
    TokenBucketPolicy policy, long requestCost, Instant activationTime)
    implements CompiledAlgorithm {

  public CompiledTokenBucketAlgorithm {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(activationTime, "activationTime");
    TokenBucketParameters.ofTokens(
        policy.capacity(),
        policy.initialTokens(),
        policy.refillTokens(),
        policy.refillPeriod().toMillis(),
        requestCost,
        activationTime.toEpochMilli());
  }
}
