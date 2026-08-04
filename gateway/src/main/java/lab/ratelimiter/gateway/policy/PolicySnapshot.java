package lab.ratelimiter.gateway.policy;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PolicySnapshot {

  private static final Comparator<CompiledPolicy> MATCH_ORDER =
      Comparator.comparingInt(CompiledPolicy::priority)
          .reversed()
          .thenComparing(policy -> policy.policy().policyId().value());

  private final long revision;
  private final Instant loadedAt;
  private final List<CompiledPolicy> policies;
  private final Map<RouteKey, CompiledPolicy> matches;
  private final Map<String, Long> activeVersions;

  public PolicySnapshot(long revision, Instant loadedAt, List<CompiledPolicy> policies) {
    if (revision < 0) {
      throw new IllegalArgumentException("snapshot revision must be nonnegative");
    }
    this.revision = revision;
    this.loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
    this.policies =
        Objects.requireNonNull(policies, "policies").stream().sorted(MATCH_ORDER).toList();
    Map<RouteKey, CompiledPolicy> indexed = new LinkedHashMap<>();
    Map<String, Long> versions = new LinkedHashMap<>();
    for (CompiledPolicy policy : this.policies) {
      indexed.putIfAbsent(new RouteKey(policy.method(), policy.path()), policy);
      Long previous =
          versions.put(policy.policy().policyId().value(), policy.policy().policyVersion().value());
      if (previous != null) {
        throw new IllegalArgumentException("snapshot contains duplicate stable policy ID");
      }
    }
    matches = Map.copyOf(indexed);
    activeVersions = Map.copyOf(versions);
  }

  public long revision() {
    return revision;
  }

  public Instant loadedAt() {
    return loadedAt;
  }

  public List<CompiledPolicy> policies() {
    return policies;
  }

  public Map<String, Long> activeVersions() {
    return activeVersions;
  }

  public Optional<CompiledPolicy> match(String method, String path) {
    return Optional.ofNullable(matches.get(new RouteKey(method, path)));
  }

  private record RouteKey(String method, String path) {
    private RouteKey {
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(path, "path");
    }
  }
}
