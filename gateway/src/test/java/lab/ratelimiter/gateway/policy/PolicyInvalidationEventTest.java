package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyInvalidationEventTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void acceptsBothSupportedVersionOneEventTypes() {
    assertThat(event("POLICY_ACTIVATED", "catalog", 1, 1).eventType())
        .isEqualTo("POLICY_ACTIVATED");
    assertThat(event("POLICY_DISABLED", "catalog", 1, 2).eventType()).isEqualTo("POLICY_DISABLED");
  }

  @Test
  void rejectsUnknownVersionsTypesAndInvalidIdentifiers() {
    assertThatThrownBy(
            () ->
                new PolicyInvalidationEvent(2, "POLICY_ACTIVATED", "catalog", 1, 1, EVENT_ID, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("UNKNOWN", "catalog", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event(null, "catalog", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("POLICY_ACTIVATED", null, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("POLICY_ACTIVATED", " ", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("POLICY_ACTIVATED", "catalog", 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("POLICY_ACTIVATED", "catalog", 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingEventAuditFields() {
    assertThatThrownBy(
            () -> new PolicyInvalidationEvent(1, "POLICY_ACTIVATED", "catalog", 1, 1, null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new PolicyInvalidationEvent(1, "POLICY_ACTIVATED", "catalog", 1, 1, EVENT_ID, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static PolicyInvalidationEvent event(
      String type, String policyId, long version, long revision) {
    return new PolicyInvalidationEvent(1, type, policyId, version, revision, EVENT_ID, NOW);
  }
}
