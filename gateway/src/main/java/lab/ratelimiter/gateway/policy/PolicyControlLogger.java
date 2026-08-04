package lab.ratelimiter.gateway.policy;

import java.time.Duration;
import lab.ratelimiter.gateway.policy.persistence.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PolicyControlLogger {

  private static final Logger LOGGER = LoggerFactory.getLogger(PolicyControlLogger.class);

  private PolicyControlLogger() {}

  static void eventRejected(String outcome) {
    LOGGER.atWarn().addKeyValue("reloadOutcome", outcome).log("policy event rejected");
  }

  static void eventProcessed(
      PolicyInvalidationEvent event,
      PolicyRefreshResult result,
      Long installedPolicyVersion,
      Duration convergenceDelay) {
    LOGGER
        .atInfo()
        .addKeyValue("policyEventId", event.eventId())
        .addKeyValue("policyEventType", event.eventType())
        .addKeyValue("requestedPolicyVersion", event.version())
        .addKeyValue("installedPolicyVersion", installedPolicyVersion)
        .addKeyValue("snapshotRevision", result.installedRevision())
        .addKeyValue("reloadTrigger", result.trigger())
        .addKeyValue("reloadOutcome", result.outcome())
        .addKeyValue("convergenceDelay", Math.max(0, convergenceDelay.toMillis()))
        .log("policy event processed");
  }

  static void eventProcessingFailed(PolicyInvalidationEvent event) {
    LOGGER
        .atWarn()
        .addKeyValue("policyEventId", event.eventId())
        .addKeyValue("policyEventType", event.eventType())
        .addKeyValue("requestedPolicyVersion", event.version())
        .addKeyValue("reloadOutcome", "FAILED")
        .log("policy event processing failed");
  }

  static void reload(PolicyRefreshResult result) {
    LOGGER
        .atInfo()
        .addKeyValue("snapshotRevision", result.installedRevision())
        .addKeyValue("reloadTrigger", result.trigger())
        .addKeyValue("reloadOutcome", result.outcome())
        .log("policy snapshot refresh completed");
  }

  static void reconciliation(ReconciliationResult result, String databaseOutcome) {
    LOGGER
        .atInfo()
        .addKeyValue("snapshotRevision", result.installedRevision())
        .addKeyValue("reconciliationOutcome", result.outcome())
        .addKeyValue("databaseOutcome", databaseOutcome)
        .log("policy reconciliation completed");
  }

  static void reconciliationFailed() {
    LOGGER
        .atWarn()
        .addKeyValue("reconciliationOutcome", "FAILED")
        .addKeyValue("databaseOutcome", "UNAVAILABLE")
        .log("policy reconciliation failed");
  }

  static void publication(OutboxEvent event, String outcome) {
    LOGGER
        .atInfo()
        .addKeyValue("policyEventId", event.eventId())
        .addKeyValue("policyEventType", event.eventType())
        .addKeyValue("requestedPolicyVersion", event.version())
        .addKeyValue("snapshotRevision", event.policySetRevision())
        .addKeyValue("publicationOutcome", outcome)
        .log("policy event publication completed");
  }

  static void subscription(String outcome) {
    LOGGER
        .atInfo()
        .addKeyValue("policyEventType", "SUBSCRIPTION")
        .addKeyValue("reloadOutcome", outcome)
        .log("policy event subscription changed");
  }
}
