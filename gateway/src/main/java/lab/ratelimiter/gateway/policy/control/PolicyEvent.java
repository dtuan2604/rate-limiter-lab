package lab.ratelimiter.gateway.policy.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PolicyEvent(
    int eventVersion,
    String eventType,
    String policyId,
    long version,
    long policySetRevision,
    UUID eventId,
    Instant occurredAt) {

  public PolicyEvent {
    if (eventVersion != 1) {
      throw new IllegalArgumentException("unsupported event version");
    }
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
