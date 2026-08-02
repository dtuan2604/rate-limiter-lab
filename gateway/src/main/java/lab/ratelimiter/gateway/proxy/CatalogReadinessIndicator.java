package lab.ratelimiter.gateway.proxy;

import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

public final class CatalogReadinessIndicator implements ReactiveHealthIndicator {

  private final CatalogBackendClient backendClient;

  public CatalogReadinessIndicator(CatalogBackendClient backendClient) {
    this.backendClient = Objects.requireNonNull(backendClient, "backendClient");
  }

  @Override
  public Mono<Health> health() {
    return backendClient
        .isHealthy()
        .map(healthy -> healthy ? Health.up().build() : Health.down().build())
        .onErrorReturn(Health.down().build());
  }
}
