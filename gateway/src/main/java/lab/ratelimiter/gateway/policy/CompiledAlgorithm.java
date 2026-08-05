package lab.ratelimiter.gateway.policy;

import lab.ratelimiter.gateway.domain.limiter.RateLimitPolicy;

public sealed interface CompiledAlgorithm
    permits CompiledFixedWindowAlgorithm, CompiledTokenBucketAlgorithm {

  RateLimitPolicy policy();
}
