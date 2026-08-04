package lab.ratelimiter.gateway.policy;

import java.time.Clock;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;

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
        new FixedWindowPolicy(
            new PolicyId(stored.policyId()),
            new PolicyVersion(stored.version()),
            definition.limit(),
            definition.window()),
        definition.failureMode(),
        definition.priority());
  }
}
