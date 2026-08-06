package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
import lab.ratelimiter.gateway.policy.control.RefillPeriod;
import lab.ratelimiter.gateway.policy.control.SlidingWindowCounterAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.WindowDuration;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class PolicySnapshotRefreshTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

  @Mock private PostgresPolicyRepository repository;

  private PolicySnapshotStore store;
  private PolicySnapshotRefreshCoordinator coordinator;

  @BeforeEach
  void setUp() {
    store = new PolicySnapshotStore(new PolicySnapshot(0, Instant.EPOCH, List.of()));
    coordinator =
        new PolicySnapshotRefreshCoordinator(
            repository, new PolicySnapshotCompiler(), store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void newerDatabaseStateCompilesAndInstallsAsOneSnapshot() {
    when(repository.loadActiveSet())
        .thenReturn(Mono.just(new ActivePolicySet(3, List.of(stored(2)))));

    PolicyRefreshResult result = coordinator.refresh(3, ReloadTrigger.POLICY_EVENT).block();

    assertThat(result.outcome()).isEqualTo(ReloadOutcome.INSTALLED);
    assertThat(result.installedRevision()).isEqualTo(3);
    assertThat(store.current().loadedAt()).isEqualTo(NOW);
    assertThat(store.current().match("GET", "/proxy/catalog/items"))
        .get()
        .satisfies(
            policy -> {
              assertThat(policy.policy().policyVersion().value()).isEqualTo(2);
              assertThat(policy.failureMode()).isEqualTo(FailureMode.FAIL_OPEN);
              assertThat(policy.priority()).isEqualTo(200);
            });
  }

  @Test
  void noChangeAvoidsReplacementAndLoadFailurePreservesPreviousSnapshot() {
    when(repository.loadActiveSet())
        .thenReturn(Mono.just(new ActivePolicySet(0, List.of())))
        .thenReturn(Mono.error(new IllegalStateException("database unavailable")));

    PolicySnapshot original = store.current();
    assertThat(coordinator.refresh(0, ReloadTrigger.RECONCILIATION).block().outcome())
        .isEqualTo(ReloadOutcome.NO_CHANGE);
    assertThat(store.current()).isSameAs(original);
    assertThat(coordinator.refresh(1, ReloadTrigger.RECONCILIATION).onErrorComplete().block())
        .isNull();
    assertThat(store.current()).isSameAs(original);
  }

  @Test
  void eventArrivingDuringRefreshRequestsAnotherAuthoritativeLoadWithoutOverlap() {
    ActivePolicySet first = new ActivePolicySet(1, List.of(stored(1)));
    ActivePolicySet second = new ActivePolicySet(2, List.of(stored(2)));
    Sinks.One<ActivePolicySet> pendingFirstLoad = Sinks.one();
    when(repository.loadActiveSet())
        .thenAnswer(
            new org.mockito.stubbing.Answer<Mono<ActivePolicySet>>() {
              private int invocation;

              @Override
              public Mono<ActivePolicySet> answer(org.mockito.invocation.InvocationOnMock ignored) {
                invocation++;
                return invocation == 1 ? pendingFirstLoad.asMono() : Mono.just(second);
              }
            });

    coordinator.refresh(1, ReloadTrigger.POLICY_EVENT).subscribe();
    Mono<PolicyRefreshResult> secondRefresh = coordinator.refresh(2, ReloadTrigger.POLICY_EVENT);
    pendingFirstLoad.tryEmitValue(first).orThrow();
    secondRefresh.block();

    assertThat(store.current().revision()).isEqualTo(2);
    verify(repository, times(2)).loadActiveSet();
    assertThat(coordinator.maximumConcurrentLoads()).isEqualTo(1);
  }

  @Test
  void rejectsInvalidRefreshRequestsBeforeLoadingTheDatabase() {
    assertThatThrownBy(() -> coordinator.refresh(-1, ReloadTrigger.POLICY_EVENT).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> coordinator.refresh(1, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void olderAuthoritativeRevisionCannotReplaceANewerLocalSnapshot() {
    store.install(new PolicySnapshot(5, NOW, List.of()));
    when(repository.loadActiveSet()).thenReturn(Mono.just(new ActivePolicySet(4, List.of())));

    PolicyRefreshResult result = coordinator.refresh(4, ReloadTrigger.RECONCILIATION).block();

    assertThat(result.outcome()).isEqualTo(ReloadOutcome.OLDER_IGNORED);
    assertThat(store.current().revision()).isEqualTo(5);
  }

  @Test
  void dynamicallySwitchesFixedToTokenAndBackWhileIgnoringOlderAlgorithmEvents() {
    when(repository.loadActiveSet())
        .thenReturn(Mono.just(new ActivePolicySet(1, List.of(stored(1)))))
        .thenReturn(Mono.just(new ActivePolicySet(2, List.of(tokenStored(2, NOW)))))
        .thenReturn(Mono.just(new ActivePolicySet(1, List.of(stored(1)))))
        .thenReturn(Mono.just(new ActivePolicySet(2, List.of(tokenStored(2, NOW)))))
        .thenReturn(Mono.just(new ActivePolicySet(3, List.of(stored(3)))));

    coordinator.refresh(1, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledFixedWindowAlgorithm.class);
    coordinator.refresh(2, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledTokenBucketAlgorithm.class);
    coordinator.refresh(2, ReloadTrigger.RECONCILIATION).block();
    assertThat(store.current().revision()).isEqualTo(2);
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledTokenBucketAlgorithm.class);
    coordinator.refresh(3, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().revision()).isEqualTo(3);
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledFixedWindowAlgorithm.class);
  }

  @Test
  void failedTokenBucketCandidateCompilationPreservesPriorCompleteSnapshot() {
    store.install(new PolicySnapshot(1, NOW, List.of()));
    PolicySnapshot prior = store.current();
    when(repository.loadActiveSet())
        .thenReturn(Mono.just(new ActivePolicySet(2, List.of(tokenStored(2, null)))));

    assertThat(coordinator.refresh(2, ReloadTrigger.RECONCILIATION).onErrorComplete().block())
        .isNull();

    assertThat(store.current()).isSameAs(prior);
    assertThat(store.current().revision()).isEqualTo(1);
  }

  @Test
  void dynamicallySwitchesAcrossAllThreeAlgorithmsAndReconciliationNeverRegresses() {
    when(repository.loadActiveSet())
        .thenReturn(Mono.just(new ActivePolicySet(1, List.of(stored(1)))))
        .thenReturn(Mono.just(new ActivePolicySet(2, List.of(slidingStored(2, 5)))))
        .thenReturn(Mono.just(new ActivePolicySet(3, List.of(tokenStored(3, NOW)))))
        .thenReturn(Mono.just(new ActivePolicySet(2, List.of(slidingStored(2, 5)))))
        .thenReturn(Mono.just(new ActivePolicySet(4, List.of(slidingStored(4, 9)))));

    coordinator.refresh(1, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledFixedWindowAlgorithm.class);
    coordinator.refresh(2, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledSlidingWindowCounterAlgorithm.class);
    coordinator.refresh(3, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOf(CompiledTokenBucketAlgorithm.class);
    coordinator.refresh(2, ReloadTrigger.POLICY_EVENT).block();
    assertThat(store.current().revision()).isEqualTo(4);
    assertThat(store.current().policies().getFirst().compiledAlgorithm())
        .isInstanceOfSatisfying(
            CompiledSlidingWindowCounterAlgorithm.class,
            sliding -> assertThat(sliding.policy().limit()).isEqualTo(9));
  }

  private static StoredPolicyVersion stored(long version) {
    return new StoredPolicyVersion(
        "catalog",
        "Catalog",
        version,
        PolicyLifecycle.ACTIVE,
        0,
        new PolicyDefinition(
            null,
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            version == 1 ? 5 : 2,
            Duration.ofSeconds(10),
            FailureMode.FAIL_OPEN,
            200),
        NOW,
        "admin",
        NOW,
        "admin");
  }

  private static StoredPolicyVersion tokenStored(long version, Instant activatedAt) {
    return new StoredPolicyVersion(
        "catalog",
        "Catalog",
        version,
        PolicyLifecycle.ACTIVE,
        0,
        new PolicyDefinition(
            null,
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            new TokenBucketAlgorithmDefinition(10, 10, 2, RefillPeriod.parse("1s"), 1),
            FailureMode.FAIL_OPEN,
            200),
        NOW.minusSeconds(30),
        "admin",
        activatedAt,
        activatedAt == null ? null : "admin");
  }

  private static StoredPolicyVersion slidingStored(long version, long limit) {
    return new StoredPolicyVersion(
        "catalog",
        "Catalog",
        version,
        PolicyLifecycle.ACTIVE,
        0,
        new PolicyDefinition(
            null,
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            new SlidingWindowCounterAlgorithmDefinition(limit, WindowDuration.parse("10s"), 1),
            FailureMode.FAIL_OPEN,
            200),
        NOW.minusSeconds(30),
        "admin",
        NOW,
        "admin");
  }
}
