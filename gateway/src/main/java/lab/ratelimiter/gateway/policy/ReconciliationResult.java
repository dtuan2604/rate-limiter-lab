package lab.ratelimiter.gateway.policy;

import java.util.Objects;

public record ReconciliationResult(
    ReconciliationOutcome outcome, long authoritativeRevision, long installedRevision) {

  public ReconciliationResult {
    Objects.requireNonNull(outcome, "outcome");
  }
}
