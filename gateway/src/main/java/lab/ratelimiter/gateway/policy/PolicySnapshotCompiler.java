package lab.ratelimiter.gateway.policy;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.control.FixedWindowAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;

public final class PolicySnapshotCompiler {

  public PolicySnapshot compile(ActivePolicySet activeSet, Clock clock) {
    Objects.requireNonNull(activeSet, "activeSet");
    Objects.requireNonNull(clock, "clock");
    return new PolicySnapshot(
        activeSet.revision(),
        clock.instant(),
        activeSet.policies().stream().map(PolicySnapshotCompiler::compilePolicy).toList());
  }

  private static CompiledPolicy compilePolicy(StoredPolicyVersion stored) {
    PolicyDefinition definition = stored.definition();
    return new CompiledPolicy(
        definition.routeId(),
        definition.path(),
        "GET",
        compileAlgorithm(stored),
        definition.failureMode(),
        definition.priority());
  }

  private static CompiledAlgorithm compileAlgorithm(StoredPolicyVersion stored) {
    PolicyDefinition definition = stored.definition();
    PolicyId policyId = new PolicyId(stored.policyId());
    PolicyVersion version = new PolicyVersion(stored.version());
    if (definition.algorithm() instanceof FixedWindowAlgorithmDefinition fixedWindow) {
      return new CompiledFixedWindowAlgorithm(
          new FixedWindowPolicy(policyId, version, fixedWindow.limit(), fixedWindow.window()));
    }
    if (definition.algorithm() instanceof TokenBucketAlgorithmDefinition tokenBucket) {
      if (stored.activatedAt() == null) {
        throw new IllegalArgumentException(
            "Token Bucket policy requires a database activation time");
      }
      return new CompiledTokenBucketAlgorithm(
          new TokenBucketPolicy(
              policyId,
              version,
              tokenBucket.capacity(),
              tokenBucket.initialTokens(),
              tokenBucket.refillTokens(),
              Duration.ofMillis(tokenBucket.refillPeriod().toMilliseconds())),
          tokenBucket.requestCost(),
          stored.activatedAt());
    }
    throw new IllegalArgumentException("Unsupported policy algorithm");
  }
}
