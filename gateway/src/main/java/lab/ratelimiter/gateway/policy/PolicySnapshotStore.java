package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PolicySnapshotStore {

  private final AtomicReference<PolicySnapshot> current;

  public PolicySnapshotStore(PolicySnapshot initial) {
    current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
  }

  public PolicySnapshot current() {
    return current.get();
  }

  public boolean install(PolicySnapshot candidate) {
    Objects.requireNonNull(candidate, "candidate");
    while (true) {
      PolicySnapshot observed = current.get();
      if (candidate.revision() <= observed.revision()) {
        return false;
      }
      if (current.compareAndSet(observed, candidate)) {
        return true;
      }
    }
  }
}
