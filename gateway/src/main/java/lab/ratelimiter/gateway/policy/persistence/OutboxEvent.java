package lab.ratelimiter.gateway.policy.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxEvent(
    UUID eventId,
    int eventVersion,
    String eventType,
    String policyId,
    long version,
    long policySetRevision,
    Instant occurredAt,
    int attemptCount) {

  public OutboxEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
