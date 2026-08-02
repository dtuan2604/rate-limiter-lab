package lab.ratelimiter.gateway.proxy;

import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

public final class WebClientCatalogBackendClient implements CatalogBackendClient {

  private static final String CLIENT_ID = "X-Client-Id";
  private static final String CORRELATION_ID = "X-Correlation-Id";
  private final WebClient webClient;
  private final Duration timeout;

  public WebClientCatalogBackendClient(WebClient webClient, Duration timeout) {
    this.webClient = Objects.requireNonNull(webClient, "webClient");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public Mono<CatalogBackendResponse> forward(CatalogBackendRequest request) {
    Objects.requireNonNull(request, "request");
    return webClient
        .get()
        .uri(builder -> catalogUri(builder, request.rawQuery()))
        .headers(
            headers -> {
              headers.set(CLIENT_ID, request.clientId());
              headers.set(CORRELATION_ID, request.correlationId());
              if (!request.accept().isBlank()) {
                headers.set(HttpHeaders.ACCEPT, request.accept());
              }
            })
        .exchangeToMono(
            response ->
                response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(
                        body ->
                            new CatalogBackendResponse(
                                response.statusCode(),
                                response
                                    .headers()
                                    .contentType()
                                    .orElse(MediaType.APPLICATION_OCTET_STREAM),
                                body)))
        .timeout(timeout);
  }

  @Override
  public Mono<Boolean> isHealthy() {
    return webClient
        .get()
        .uri("/health")
        .exchangeToMono(
            response -> response.releaseBody().thenReturn(response.statusCode().is2xxSuccessful()))
        .timeout(timeout);
  }

  private static java.net.URI catalogUri(UriBuilder builder, String rawQuery) {
    UriBuilder catalog = builder.path("/catalog/items");
    if (rawQuery != null && !rawQuery.isBlank()) {
      catalog.query(rawQuery);
    }
    return catalog.build();
  }
}
