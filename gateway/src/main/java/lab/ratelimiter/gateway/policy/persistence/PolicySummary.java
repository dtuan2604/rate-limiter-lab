package lab.ratelimiter.gateway.policy.persistence;

import java.util.Objects;

public record PolicySummary(String policyId, String name, long latestVersion, Long activeVersion) {
  public PolicySummary {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(name, "name");
  }
}
