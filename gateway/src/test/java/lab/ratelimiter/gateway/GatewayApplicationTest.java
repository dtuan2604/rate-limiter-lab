package lab.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTest {

  @LocalServerPort private int port;

  @Test
  void exposesOnlyAHealthyFoundationProcess() {
    WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

    client
        .get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");

    client.get().uri("/").exchange().expectStatus().isNotFound();
    assertThat(port).isPositive();
  }
}
