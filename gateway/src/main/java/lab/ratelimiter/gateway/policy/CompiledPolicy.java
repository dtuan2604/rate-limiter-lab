package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.RateLimitPolicy;

public record CompiledPolicy(
    String routeId,
    String path,
    String method,
    CompiledAlgorithm compiledAlgorithm,
    FailureMode failureMode,
    int priority) {

  public CompiledPolicy {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(compiledAlgorithm, "compiledAlgorithm");
    Objects.requireNonNull(failureMode, "failureMode");
    if (priority < 0 || priority > 1000) {
      throw new IllegalArgumentException("priority must be between 0 and 1000");
    }
  }

  public CompiledPolicy(String routeId, String path, String method, FixedWindowPolicy policy) {
    this(
        routeId,
        path,
        method,
        new CompiledFixedWindowAlgorithm(policy),
        FailureMode.FAIL_CLOSED,
        0);
  }

  public CompiledPolicy(
      String routeId,
      String path,
      String method,
      FixedWindowPolicy policy,
      FailureMode failureMode,
      int priority) {
    this(routeId, path, method, new CompiledFixedWindowAlgorithm(policy), failureMode, priority);
  }

  public RateLimitPolicy policy() {
    return compiledAlgorithm.policy();
  }
}
