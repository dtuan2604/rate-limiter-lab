package lab.ratelimiter.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WebClientCatalogBackendClientTest {

  @Test
  void forwardsCatalogPathQueryAndRequiredHeaders() {
    AtomicReference<ClientRequest> observed = new AtomicReference<>();
    WebClient webClient =
        WebClient.builder()
            .baseUrl("http://catalog:8000")
            .exchangeFunction(
                request -> {
                  observed.set(request);
                  return Mono.just(
                      ClientResponse.create(HttpStatus.OK)
                          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                          .body("{\"service\":\"catalog\"}")
                          .build());
                })
            .build();
    WebClientCatalogBackendClient client =
        new WebClientCatalogBackendClient(webClient, Duration.ofSeconds(2));

    CatalogBackendResponse response =
        client
            .forward(
                new CatalogBackendRequest(
                    "page=2&tag=a", "client-a", "correlation-a", "application/json"))
            .block();

    assertThat(response)
        .isEqualTo(
            new CatalogBackendResponse(
                HttpStatus.OK, MediaType.APPLICATION_JSON, "{\"service\":\"catalog\"}"));
    assertThat(observed.get().url().toString())
        .isEqualTo("http://catalog:8000/catalog/items?page=2&tag=a");
    assertThat(observed.get().headers().getFirst("X-Client-Id")).isEqualTo("client-a");
    assertThat(observed.get().headers().getFirst("X-Correlation-Id")).isEqualTo("correlation-a");
    assertThat(observed.get().headers().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
  }

  @Test
  void preservesBackendStatusAndDefaultsMissingContentType() {
    WebClient webClient =
        WebClient.builder()
            .baseUrl("http://catalog:8000")
            .exchangeFunction(
                ignored ->
                    Mono.just(
                        ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("failure")
                            .build()))
            .build();
    WebClientCatalogBackendClient client =
        new WebClientCatalogBackendClient(webClient, Duration.ofSeconds(2));

    CatalogBackendResponse response =
        client.forward(new CatalogBackendRequest("", "client", "correlation", "")).block();

    assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.body()).isEqualTo("failure");
  }

  @Test
  void healthRequiresSuccessfulBackendStatus() {
    WebClient healthyClient =
        WebClient.builder()
            .baseUrl("http://catalog:8000")
            .exchangeFunction(ignored -> Mono.just(ClientResponse.create(HttpStatus.OK).build()))
            .build();
    WebClient unhealthyClient =
        WebClient.builder()
            .baseUrl("http://catalog:8000")
            .exchangeFunction(
                ignored -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()))
            .build();

    assertThat(
            new WebClientCatalogBackendClient(healthyClient, Duration.ofSeconds(2))
                .isHealthy()
                .block())
        .isTrue();
    assertThat(
            new WebClientCatalogBackendClient(unhealthyClient, Duration.ofSeconds(2))
                .isHealthy()
                .block())
        .isFalse();
  }

  @Test
  void transportFailureIsPropagatedForGatewayMappingWithoutRetry() {
    IllegalStateException failure = new IllegalStateException("connection failed");
    WebClient webClient =
        WebClient.builder()
            .baseUrl("http://catalog:8000")
            .exchangeFunction(ignored -> Mono.error(failure))
            .build();
    WebClientCatalogBackendClient client =
        new WebClientCatalogBackendClient(webClient, Duration.ofSeconds(2));

    StepVerifier.create(
            client.forward(
                new CatalogBackendRequest(null, "client", "correlation", "application/json")))
        .expectErrorMatches(error -> error == failure)
        .verify();
  }
}
