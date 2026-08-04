package lab.ratelimiter.gateway.policy;

import java.time.Instant;
import java.util.Objects;

public record ProcessedPolicyEvent(
    PolicyInvalidationEvent event, ReloadOutcome reloadOutcome, Instant processedAt) {
  public ProcessedPolicyEvent {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(reloadOutcome, "reloadOutcome");
    Objects.requireNonNull(processedAt, "processedAt");
  }
}
