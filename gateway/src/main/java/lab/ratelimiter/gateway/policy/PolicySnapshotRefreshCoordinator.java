package lab.ratelimiter.gateway.policy;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import reactor.core.publisher.Mono;

public final class PolicySnapshotRefreshCoordinator {

  private final Object monitor = new Object();
  private final PostgresPolicyRepository repository;
  private final PolicySnapshotCompiler compiler;
  private final PolicySnapshotStore store;
  private final Clock clock;
  private final AtomicLong highestRequested = new AtomicLong();
  private final AtomicInteger concurrentLoads = new AtomicInteger();
  private final AtomicInteger maximumConcurrentLoads = new AtomicInteger();
  private Mono<PolicyRefreshResult> inFlight;

  public PolicySnapshotRefreshCoordinator(
      PostgresPolicyRepository repository,
      PolicySnapshotCompiler compiler,
      PolicySnapshotStore store,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Mono<PolicyRefreshResult> refresh(long requestedRevision, ReloadTrigger trigger) {
    if (requestedRevision < 0) {
      return Mono.error(new IllegalArgumentException("requested revision must be nonnegative"));
    }
    Objects.requireNonNull(trigger, "trigger");
    highestRequested.accumulateAndGet(requestedRevision, Math::max);
    synchronized (monitor) {
      if (inFlight == null) {
        Mono<PolicyRefreshResult> created =
            refreshUntilCurrent(trigger, -1)
                .doFinally(
                    ignored -> {
                      synchronized (monitor) {
                        inFlight = null;
                      }
                    })
                .cache();
        inFlight = created;
      }
      return inFlight;
    }
  }

  public int maximumConcurrentLoads() {
    return maximumConcurrentLoads.get();
  }

  Long installedPolicyVersion(String policyId) {
    return store.current().activeVersions().get(policyId);
  }

  private Mono<PolicyRefreshResult> refreshUntilCurrent(ReloadTrigger trigger, long priorRevision) {
    return loadActiveSet()
        .flatMap(
            activeSet -> {
              PolicyRefreshResult result = install(activeSet, trigger);
              long requested = highestRequested.get();
              if (requested > activeSet.revision() && activeSet.revision() > priorRevision) {
                return refreshUntilCurrent(trigger, activeSet.revision());
              }
              return Mono.just(result);
            });
  }

  private Mono<ActivePolicySet> loadActiveSet() {
    return Mono.defer(
        () -> {
          int active = concurrentLoads.incrementAndGet();
          maximumConcurrentLoads.accumulateAndGet(active, Math::max);
          AtomicBoolean completed = new AtomicBoolean();
          Runnable complete =
              () -> {
                if (completed.compareAndSet(false, true)) {
                  concurrentLoads.decrementAndGet();
                }
              };
          return repository
              .loadActiveSet()
              .doOnEach(
                  signal -> {
                    if (signal.isOnNext() || signal.isOnError()) {
                      complete.run();
                    }
                  })
              .doOnCancel(complete);
        });
  }

  private PolicyRefreshResult install(ActivePolicySet activeSet, ReloadTrigger trigger) {
    PolicySnapshot current = store.current();
    ReloadOutcome outcome;
    if (activeSet.revision() < current.revision()) {
      outcome = ReloadOutcome.OLDER_IGNORED;
    } else if (activeSet.revision() == current.revision()) {
      outcome = ReloadOutcome.NO_CHANGE;
    } else {
      PolicySnapshot candidate = compiler.compile(activeSet, clock);
      outcome = store.install(candidate) ? ReloadOutcome.INSTALLED : ReloadOutcome.OLDER_IGNORED;
    }
    PolicyRefreshResult result =
        new PolicyRefreshResult(
            trigger,
            outcome,
            highestRequested.get(),
            activeSet.revision(),
            store.current().revision());
    PolicyControlLogger.reload(result);
    return result;
  }
}
