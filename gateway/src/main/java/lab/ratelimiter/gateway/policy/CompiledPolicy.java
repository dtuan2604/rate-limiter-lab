package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;

public record CompiledPolicy(String routeId, String path, String method, FixedWindowPolicy policy) {

  public CompiledPolicy {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(policy, "policy");
  }
}
