package lab.ratelimiter.gateway.http.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.HttpHandlerConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

class AdminAuthenticationTest {

  private AtomicInteger invocations;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    invocations = new AtomicInteger();
    var routes =
        RouterFunctions.route()
            .GET(
                "/admin/api/v1/policies",
                ignored -> {
                  invocations.incrementAndGet();
                  return ServerResponse.ok().build();
                })
            .GET(
                "/internal/policy-snapshot",
                ignored -> {
                  invocations.incrementAndGet();
                  return ServerResponse.ok().build();
                })
            .GET(
                "/proxy/catalog/items",
                ignored -> {
                  invocations.incrementAndGet();
                  return ServerResponse.ok().build();
                })
            .build();
    var handler =
        WebHttpHandlerBuilder.webHandler(RouterFunctions.toWebHandler(routes))
            .filter(new AdminAuthenticationFilter("correct-secret-token"))
            .build();
    client = WebTestClient.bindToServer(new HttpHandlerConnector(handler)).build();
  }

  @Test
  void missingAndIncorrectTokensReturnTheSameUnauthorizedContractBeforeHandlersRun() {
    client
        .get()
        .uri("/admin/api/v1/policies")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("ADMIN_AUTHENTICATION_REQUIRED");

    client
        .get()
        .uri("/internal/policy-snapshot")
        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-secret-token")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

    assertThat(invocations).hasValue(0);
  }

  @Test
  void validTokenIsAcceptedAndPublicProxyDoesNotRequireIt() {
    client
        .get()
        .uri("/admin/api/v1/policies")
        .header(HttpHeaders.AUTHORIZATION, "Bearer correct-secret-token")
        .exchange()
        .expectStatus()
        .isOk();
    client.get().uri("/proxy/catalog/items").exchange().expectStatus().isOk();

    assertThat(invocations).hasValue(2);
  }

  @Test
  void rejectsMissingConfiguredSecretsAndNonBearerCredentials() {
    assertThatThrownBy(() -> new AdminAuthenticationFilter(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdminAuthenticationFilter(" "))
        .isInstanceOf(IllegalArgumentException.class);

    client
        .get()
        .uri("/admin/api/v1/policies")
        .header(HttpHeaders.AUTHORIZATION, "Basic correct-secret-token")
        .header("X-Correlation-Id", "quoted\\\"correlation")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.correlationId")
        .isEqualTo("quoted\\\"correlation");
  }
}
