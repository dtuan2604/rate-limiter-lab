package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;

public record CompiledPolicy(
    String routeId,
    String path,
    String method,
    FixedWindowPolicy policy,
    FailureMode failureMode,
    int priority) {

  public CompiledPolicy {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(failureMode, "failureMode");
    if (priority < 0 || priority > 1000) {
      throw new IllegalArgumentException("priority must be between 0 and 1000");
    }
  }

  public CompiledPolicy(String routeId, String path, String method, FixedWindowPolicy policy) {
    this(routeId, path, method, policy, FailureMode.FAIL_CLOSED, 0);
  }
}
