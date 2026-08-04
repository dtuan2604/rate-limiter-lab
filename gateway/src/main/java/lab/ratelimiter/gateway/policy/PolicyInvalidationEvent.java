package lab.ratelimiter.gateway.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PolicyInvalidationEvent(
    int eventVersion,
    String eventType,
    String policyId,
    long version,
    long policySetRevision,
    UUID eventId,
    Instant occurredAt) {

  public PolicyInvalidationEvent {
    if (eventVersion != 1) {
      throw new IllegalArgumentException("unsupported policy event version");
    }
    if (!("POLICY_ACTIVATED".equals(eventType) || "POLICY_DISABLED".equals(eventType))) {
      throw new IllegalArgumentException("unsupported policy event type");
    }
    if (policyId == null || policyId.isBlank() || version < 1 || policySetRevision < 1) {
      throw new IllegalArgumentException("policy event identifiers must be positive and nonempty");
    }
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
