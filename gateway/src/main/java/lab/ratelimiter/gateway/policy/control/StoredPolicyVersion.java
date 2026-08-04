package lab.ratelimiter.gateway.policy.control;

import java.time.Instant;
import java.util.Objects;

public record StoredPolicyVersion(
    String policyId,
    String name,
    long version,
    PolicyLifecycle lifecycle,
    long revision,
    PolicyDefinition definition,
    Instant createdAt,
    String createdBy,
    Instant activatedAt,
    String activatedBy) {

  public StoredPolicyVersion {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
  }
}
