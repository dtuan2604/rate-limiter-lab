package lab.ratelimiter.gateway.policy.control;

public sealed interface PolicyAlgorithmDefinition
    permits FixedWindowAlgorithmDefinition, TokenBucketAlgorithmDefinition {

  PolicyAlgorithmType type();
}
