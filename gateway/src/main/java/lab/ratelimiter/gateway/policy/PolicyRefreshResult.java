package lab.ratelimiter.gateway.policy;

import java.util.Objects;

public record PolicyRefreshResult(
    ReloadTrigger trigger,
    ReloadOutcome outcome,
    long requestedRevision,
    long authoritativeRevision,
    long installedRevision) {

  public PolicyRefreshResult {
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(outcome, "outcome");
  }
}
