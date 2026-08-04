package lab.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "rate-limiter.gateway.catalog-base-url=http://127.0.0.1:1",
      "rate-limiter.policy-control.enabled=false",
      "rate-limiter.gateway.policies[0].id=catalog-client-fixed-window",
      "rate-limiter.gateway.policies[0].version=1",
      "rate-limiter.gateway.policies[0].route-id=catalog.items",
      "rate-limiter.gateway.policies[0].path=/proxy/catalog/items",
      "rate-limiter.gateway.policies[0].method=GET",
      "rate-limiter.gateway.policies[0].algorithm=FIXED_WINDOW",
      "rate-limiter.gateway.policies[0].limit=5",
      "rate-limiter.gateway.policies[0].window=10s",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    })
class GatewayApplicationTest {

  @LocalServerPort private int port;

  @Test
  void exposesLivenessButNotReadinessWhenCatalogIsUnavailable() {
    WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

    client
        .get()
        .uri("/actuator/health/liveness")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");

    client
        .get()
        .uri("/actuator/health/readiness")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("DOWN");

    client.get().uri("/").exchange().expectStatus().isNotFound();
    assertThat(port).isPositive();
  }
}
