package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class PolicyReconciliationTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

  @Mock private PostgresPolicyRepository repository;
  @Mock private PolicySnapshotRefreshCoordinator coordinator;

  private PolicySnapshotStore store;
  private PolicyReconciler reconciler;

  @BeforeEach
  void setUp() {
    store = new PolicySnapshotStore(new PolicySnapshot(1, NOW, List.of()));
    reconciler =
        new PolicyReconciler(
            repository, coordinator, store, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
  }

  @Test
  void noChangeUsesLightweightRevisionAndAvoidsFullReload() {
    when(repository.currentPolicySetRevision()).thenReturn(Mono.just(1L));

    assertThat(reconciler.reconcile().block().outcome()).isEqualTo(ReconciliationOutcome.NO_CHANGE);
    assertThat(reconciler.lastSuccessfulReconciliation()).isEqualTo(NOW.plusSeconds(30));
    verify(coordinator, never()).refresh(1, ReloadTrigger.RECONCILIATION);
  }

  @Test
  void missedEventIsDiscoveredAndRefreshesThroughSharedCoordinator() {
    when(repository.currentPolicySetRevision()).thenReturn(Mono.just(2L));
    when(coordinator.refresh(2, ReloadTrigger.RECONCILIATION))
        .thenReturn(
            Mono.just(
                new PolicyRefreshResult(
                    ReloadTrigger.RECONCILIATION, ReloadOutcome.INSTALLED, 2, 2, 2)));

    assertThat(reconciler.reconcile().block().outcome()).isEqualTo(ReconciliationOutcome.REFRESHED);
    verify(coordinator).refresh(2, ReloadTrigger.RECONCILIATION);
  }

  @Test
  void concurrentRunsDoNotOverlapAndDatabaseRecoverySucceeds() {
    Sinks.One<Long> pending = Sinks.one();
    when(repository.currentPolicySetRevision())
        .thenReturn(pending.asMono())
        .thenReturn(Mono.error(new IllegalStateException("database down")))
        .thenReturn(Mono.just(1L));

    reconciler.reconcile().subscribe();
    assertThat(reconciler.reconcile().block().outcome())
        .isEqualTo(ReconciliationOutcome.ALREADY_RUNNING);
    pending.tryEmitValue(1L).orThrow();
    assertThat(reconciler.reconcile().onErrorComplete().block()).isNull();
    assertThat(reconciler.degraded()).isTrue();
    assertThat(reconciler.reconcile().block().outcome()).isEqualTo(ReconciliationOutcome.NO_CHANGE);
    assertThat(reconciler.degraded()).isFalse();
  }
}
