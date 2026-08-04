package lab.ratelimiter.gateway.policy.control;

import java.util.List;

public record ActivePolicySet(long revision, List<StoredPolicyVersion> policies) {

  public ActivePolicySet {
    policies = List.copyOf(policies);
  }
}
