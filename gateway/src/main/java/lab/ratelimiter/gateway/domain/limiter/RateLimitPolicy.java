package lab.ratelimiter.gateway.domain.limiter;

public sealed interface RateLimitPolicy
    permits FixedWindowPolicy,
        SlidingWindowLogPolicy,
        SlidingWindowCounterPolicy,
        TokenBucketPolicy,
        LeakyBucketPolicy {

  PolicyId policyId();

  PolicyVersion policyVersion();

  AlgorithmType algorithm();

  long limit();
}
