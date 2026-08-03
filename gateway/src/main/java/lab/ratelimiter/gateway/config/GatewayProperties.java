package lab.ratelimiter.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.application.StateBackend;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limiter.gateway")
public record GatewayProperties(
    URI catalogBaseUrl,
    Duration backendTimeout,
    StateBackend stateBackend,
    FailureMode failureMode,
    String instanceId,
    boolean exposeInstanceHeader,
    Duration redisCommandTimeout,
    List<PolicyProperties> policies) {

  public GatewayProperties {
    policies = policies == null ? List.of() : List.copyOf(policies);
  }

  public record PolicyProperties(
      String id,
      Long version,
      String routeId,
      String path,
      String method,
      String algorithm,
      Long limit,
      Duration window) {}
}
