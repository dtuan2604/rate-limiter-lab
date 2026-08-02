package lab.ratelimiter.gateway.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class StaticPolicySnapshot {

  private final Map<RouteKey, CompiledPolicy> policies;

  public StaticPolicySnapshot(List<CompiledPolicy> policies) {
    Objects.requireNonNull(policies, "policies");
    Map<RouteKey, CompiledPolicy> indexed = new LinkedHashMap<>();
    for (CompiledPolicy policy : policies) {
      CompiledPolicy previous = indexed.put(new RouteKey(policy.method(), policy.path()), policy);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate static route: " + policy.method() + " " + policy.path());
      }
    }
    this.policies = Map.copyOf(indexed);
  }

  public Optional<CompiledPolicy> match(String method, String path) {
    return Optional.ofNullable(policies.get(new RouteKey(method, path)));
  }

  private record RouteKey(String method, String path) {
    private RouteKey {
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(path, "path");
    }
  }
}
