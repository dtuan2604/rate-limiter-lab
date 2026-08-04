package lab.ratelimiter.gateway.policy;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import reactor.core.publisher.Mono;

public final class PolicyReconciler {

  private final PostgresPolicyRepository repository;
  private final PolicySnapshotRefreshCoordinator coordinator;
  private final PolicySnapshotStore store;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean degraded = new AtomicBoolean();
  private final AtomicReference<Instant> lastSuccessful = new AtomicReference<>();

  public PolicyReconciler(
      PostgresPolicyRepository repository,
      PolicySnapshotRefreshCoordinator coordinator,
      PolicySnapshotStore store,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Mono<ReconciliationResult> reconcile() {
    if (!running.compareAndSet(false, true)) {
      long revision = store.current().revision();
      return Mono.just(
          new ReconciliationResult(ReconciliationOutcome.ALREADY_RUNNING, revision, revision));
    }
    return repository
        .currentPolicySetRevision()
        .flatMap(this::refreshWhenChanged)
        .doOnSuccess(
            result -> {
              lastSuccessful.set(clock.instant());
              degraded.set(false);
              PolicyControlLogger.reconciliation(result, "AVAILABLE");
            })
        .doOnError(
            ignored -> {
              degraded.set(true);
              PolicyControlLogger.reconciliationFailed();
            })
        .doFinally(ignored -> running.set(false));
  }

  public Instant lastSuccessfulReconciliation() {
    return lastSuccessful.get();
  }

  public boolean degraded() {
    return degraded.get();
  }

  private Mono<ReconciliationResult> refreshWhenChanged(long authoritativeRevision) {
    long installed = store.current().revision();
    if (authoritativeRevision <= installed) {
      return Mono.just(
          new ReconciliationResult(
              ReconciliationOutcome.NO_CHANGE, authoritativeRevision, installed));
    }
    return coordinator
        .refresh(authoritativeRevision, ReloadTrigger.RECONCILIATION)
        .map(
            result ->
                new ReconciliationResult(
                    ReconciliationOutcome.REFRESHED,
                    authoritativeRevision,
                    result.installedRevision()));
  }
}
