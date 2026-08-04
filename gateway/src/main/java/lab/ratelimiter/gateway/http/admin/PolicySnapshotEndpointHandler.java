package lab.ratelimiter.gateway.http.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lab.ratelimiter.gateway.policy.PolicyEventConsumer;
import lab.ratelimiter.gateway.policy.PolicyPropagationStatus;
import lab.ratelimiter.gateway.policy.PolicyReconciler;
import lab.ratelimiter.gateway.policy.PolicySnapshot;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.policy.ProcessedPolicyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public final class PolicySnapshotEndpointHandler {

  private final PolicySnapshotStore store;
  private final PolicyReconciler reconciler;
  private final PolicyEventConsumer eventConsumer;
  private final PolicyPropagationStatus propagationStatus;
  private final String gatewayInstanceId;
  private final boolean acceptanceControlsEnabled;

  public PolicySnapshotEndpointHandler(
      PolicySnapshotStore store,
      PolicyReconciler reconciler,
      PolicyEventConsumer eventConsumer,
      String gatewayInstanceId,
      boolean acceptanceControlsEnabled) {
    this(
        store,
        reconciler,
        eventConsumer,
        PolicyPropagationStatus.available(),
        gatewayInstanceId,
        acceptanceControlsEnabled);
  }

  public PolicySnapshotEndpointHandler(
      PolicySnapshotStore store,
      PolicyReconciler reconciler,
      PolicyEventConsumer eventConsumer,
      PolicyPropagationStatus propagationStatus,
      String gatewayInstanceId,
      boolean acceptanceControlsEnabled) {
    this.store = Objects.requireNonNull(store, "store");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
    this.propagationStatus = Objects.requireNonNull(propagationStatus, "propagationStatus");
    this.gatewayInstanceId = Objects.requireNonNull(gatewayInstanceId, "gatewayInstanceId");
    this.acceptanceControlsEnabled = acceptanceControlsEnabled;
  }

  public Mono<ServerResponse> snapshot(ServerRequest ignored) {
    PolicySnapshot snapshot = store.current();
    ProcessedPolicyEvent processed = eventConsumer.lastProcessedEvent();
    LastEvent lastEvent =
        processed == null
            ? null
            : new LastEvent(
                processed.event().eventId(),
                processed.event().eventType(),
                processed.processedAt().toString());
    List<String> degradationReasons = new ArrayList<>();
    if (!propagationStatus.eventSubscriptionAvailable()) {
      degradationReasons.add("REDIS_POLICY_SUBSCRIPTION_UNAVAILABLE");
    }
    if (reconciler.degraded()) {
      degradationReasons.add("POSTGRES_RECONCILIATION_UNAVAILABLE");
    }
    SnapshotMetadata metadata =
        new SnapshotMetadata(
            gatewayInstanceId,
            snapshot.revision(),
            snapshot.loadedAt().toString(),
            snapshot.activeVersions().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new ActivePolicy(entry.getKey(), entry.getValue()))
                .toList(),
            reconciler.lastSuccessfulReconciliation() == null
                ? null
                : reconciler.lastSuccessfulReconciliation().toString(),
            lastEvent,
            !degradationReasons.isEmpty(),
            degradationReasons);
    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(metadata);
  }

  public Mono<ServerResponse> pauseEvents(ServerRequest ignored) {
    return updateEventPause(true);
  }

  public Mono<ServerResponse> resumeEvents(ServerRequest ignored) {
    return updateEventPause(false);
  }

  private Mono<ServerResponse> updateEventPause(boolean pause) {
    if (!acceptanceControlsEnabled) {
      return ServerResponse.status(HttpStatus.NOT_FOUND).build();
    }
    if (pause) {
      eventConsumer.pause();
    } else {
      eventConsumer.resume();
    }
    return ServerResponse.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new EventConsumptionState(eventConsumer.paused()));
  }

  public record SnapshotMetadata(
      String gatewayInstanceId,
      long snapshotRevision,
      String loadedAt,
      List<ActivePolicy> activePolicies,
      String lastSuccessfulReconciliation,
      LastEvent lastEventProcessed,
      boolean degraded,
      List<String> degradationReasons) {}

  public record ActivePolicy(String policyId, long version) {}

  public record LastEvent(java.util.UUID eventId, String eventType, String processedAt) {}

  public record EventConsumptionState(boolean paused) {}
}
