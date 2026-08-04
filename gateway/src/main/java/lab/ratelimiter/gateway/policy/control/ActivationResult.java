package lab.ratelimiter.gateway.policy.control;

import java.util.Objects;

public record ActivationResult(
    StoredPolicyVersion policy, long policySetRevision, PolicyEvent event) {

  public ActivationResult {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(event, "event");
  }
}
