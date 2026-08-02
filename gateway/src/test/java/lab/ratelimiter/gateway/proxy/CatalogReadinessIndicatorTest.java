package lab.ratelimiter.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;

class CatalogReadinessIndicatorTest {

  @Test
  void readinessTracksCatalogHealthAndSanitizesFailures() {
    assertThat(
            new CatalogReadinessIndicator(new HealthOnlyBackend(Mono.just(true))).health().block())
        .extracting(health -> health.getStatus())
        .isEqualTo(Status.UP);
    assertThat(
            new CatalogReadinessIndicator(new HealthOnlyBackend(Mono.just(false))).health().block())
        .extracting(health -> health.getStatus())
        .isEqualTo(Status.DOWN);
    assertThat(
            new CatalogReadinessIndicator(
                    new HealthOnlyBackend(Mono.error(new IllegalStateException("secret"))))
                .health()
                .block())
        .satisfies(
            health -> {
              assertThat(health.getStatus()).isEqualTo(Status.DOWN);
              assertThat(health.getDetails()).doesNotContainValue("secret");
            });
  }

  private record HealthOnlyBackend(Mono<Boolean> health) implements CatalogBackendClient {

    @Override
    public Mono<CatalogBackendResponse> forward(CatalogBackendRequest request) {
      return Mono.error(new UnsupportedOperationException("not used"));
    }

    @Override
    public Mono<Boolean> isHealthy() {
      return health;
    }
  }
}
