package lab.ratelimiter.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lab.ratelimiter.gateway.application.RateLimitService;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.StaticPolicySnapshot;
import lab.ratelimiter.gateway.proxy.CatalogBackendClient;
import lab.ratelimiter.gateway.proxy.CatalogBackendRequest;
import lab.ratelimiter.gateway.proxy.CatalogBackendResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class GatewayHttpHandlerTest {

  private static final Instant START = Instant.parse("2026-07-26T12:00:00Z");
  private static final String CLIENT_ID = "X-Client-Id";
  private static final String CORRELATION_ID = "X-Correlation-Id";
  private MutableClock clock;
  private RecordingCatalogBackend backend;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(START);
    backend = new RecordingCatalogBackend();
    CompiledPolicy compiled =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new FixedWindowPolicy(
                new PolicyId("catalog-client-fixed-window"),
                new PolicyVersion(1),
                5,
                Duration.ofSeconds(10)));
    GatewayHttpHandler handler =
        new GatewayHttpHandler(
            new StaticPolicySnapshot(List.of(compiled)),
            new ClientIdentityExtractor(),
            new RateLimitService(clock),
            backend,
            clock);
    client = WebTestClient.bindToRouterFunction(GatewayRoutes.routes(handler)).build();
  }

  @Test
  void missingOrBlankClientIdReturnsStructured400WithoutForwarding() {
    client
        .get()
        .uri("/proxy/catalog/items")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .valueMatches(CORRELATION_ID, ".+")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(400)
        .jsonPath("$.error")
        .isEqualTo("MISSING_CLIENT_ID")
        .jsonPath("$.message")
        .isEqualTo("X-Client-Id header is required")
        .jsonPath("$.correlationId")
        .isNotEmpty();

    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, " ")
        .header(CORRELATION_ID, " ")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .valueMatches(CORRELATION_ID, ".+");

    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "x".repeat(257))
        .exchange()
        .expectStatus()
        .isBadRequest();

    assertThat(backend.requests).isEmpty();
  }

  @Test
  void unmatchedProxyRouteHasExplicitStructured404() {
    client
        .get()
        .uri("/proxy/catalog/missing")
        .header(CLIENT_ID, "client-a")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueMatches(CORRELATION_ID, ".+")
        .expectBody()
        .json(
            """
            {
              "status": 404,
              "error": "ROUTE_NOT_FOUND",
              "message": "No proxy route matches the request"
            }
            """,
            JsonCompareMode.LENIENT);

    assertThat(backend.requests).isEmpty();
  }

  @Test
  void correlationIdIsGeneratedWhenAbsentAndPropagatedWhenPresent() {
    EntityExchangeResult<byte[]> generated =
        client
            .get()
            .uri("/proxy/catalog/items")
            .header(CLIENT_ID, "generated-client")
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches(CORRELATION_ID, ".+")
            .expectBody()
            .returnResult();
    String generatedId = generated.getResponseHeaders().getFirst(CORRELATION_ID);
    assertThat(backend.requests.getFirst().correlationId()).isEqualTo(generatedId);

    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "existing-client")
        .header(CORRELATION_ID, "correlation-from-client")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(CORRELATION_ID, "correlation-from-client");
    assertThat(backend.requests.getLast().correlationId()).isEqualTo("correlation-from-client");
  }

  @Test
  void firstFiveRequestsReachBackendAndSixthIsRejectedWithDecisionMetadata() {
    for (int request = 1; request <= 5; request++) {
      client
          .get()
          .uri("/proxy/catalog/items")
          .header(CLIENT_ID, "limited-client")
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("RateLimit-Limit", "5")
          .expectHeader()
          .valueEquals("RateLimit-Remaining", Integer.toString(5 - request))
          .expectHeader()
          .valueEquals("RateLimit-Reset", "10")
          .expectHeader()
          .valueEquals("X-RateLimit-Policy", "catalog-client-fixed-window")
          .expectHeader()
          .valueMatches(CORRELATION_ID, ".+")
          .expectBody()
          .jsonPath("$.service")
          .isEqualTo("catalog");
    }

    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "limited-client")
        .header(CORRELATION_ID, "rejected-correlation")
        .exchange()
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals("RateLimit-Limit", "5")
        .expectHeader()
        .valueEquals("RateLimit-Remaining", "0")
        .expectHeader()
        .valueEquals("RateLimit-Reset", "10")
        .expectHeader()
        .valueEquals("Retry-After", "10")
        .expectHeader()
        .valueEquals("X-RateLimit-Policy", "catalog-client-fixed-window")
        .expectHeader()
        .valueEquals(CORRELATION_ID, "rejected-correlation")
        .expectBody()
        .json(
            """
            {
              "status": 429,
              "error": "RATE_LIMIT_EXCEEDED",
              "message": "Request limit exceeded",
              "policy": "catalog-client-fixed-window",
              "retryAfterMilliseconds": 10000,
              "correlationId": "rejected-correlation"
            }
            """,
            JsonCompareMode.STRICT);

    assertThat(backend.requests).hasSize(5);
  }

  @Test
  void differentClientsAndAChangedWindowAreAllowed() {
    for (int request = 0; request < 5; request++) {
      client
          .get()
          .uri("/proxy/catalog/items")
          .header(CLIENT_ID, "client-a")
          .exchange()
          .expectStatus()
          .isOk();
    }
    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "client-a")
        .exchange()
        .expectStatus()
        .isEqualTo(429);

    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "client-b")
        .exchange()
        .expectStatus()
        .isOk();

    clock.advance(Duration.ofSeconds(10));
    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "client-a")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void queryAndRequiredHeadersAreForwarded() {
    client
        .get()
        .uri("/proxy/catalog/items?page=2&tag=a&tag=b")
        .header(CLIENT_ID, "query-client")
        .header(CORRELATION_ID, "query-correlation")
        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(backend.requests)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.rawQuery()).isEqualTo("page=2&tag=a&tag=b");
              assertThat(request.clientId()).isEqualTo("query-client");
              assertThat(request.correlationId()).isEqualTo("query-correlation");
              assertThat(request.accept()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
            });
  }

  @Test
  void backendFailureReturnsStructured502WithoutRetrying() {
    backend.failure = new IllegalStateException("backend detail must not leak");

    client
        .get()
        .uri("/proxy/catalog/items")
        .header(CLIENT_ID, "unavailable-client")
        .header(CORRELATION_ID, "unavailable-correlation")
        .exchange()
        .expectStatus()
        .isEqualTo(502)
        .expectHeader()
        .valueEquals("RateLimit-Limit", "5")
        .expectHeader()
        .valueEquals("RateLimit-Remaining", "4")
        .expectHeader()
        .valueEquals(CORRELATION_ID, "unavailable-correlation")
        .expectBody()
        .json(
            """
            {
              "status": 502,
              "error": "BACKEND_UNAVAILABLE",
              "message": "Catalog backend is unavailable",
              "correlationId": "unavailable-correlation"
            }
            """,
            JsonCompareMode.STRICT);

    assertThat(backend.attempts).isEqualTo(1);
  }

  private static final class RecordingCatalogBackend implements CatalogBackendClient {

    private final List<CatalogBackendRequest> requests = new ArrayList<>();
    private int attempts;
    private RuntimeException failure;

    @Override
    public Mono<CatalogBackendResponse> forward(CatalogBackendRequest request) {
      attempts++;
      if (failure != null) {
        return Mono.error(failure);
      }
      requests.add(request);
      return Mono.just(
          new CatalogBackendResponse(
              HttpStatus.OK, MediaType.APPLICATION_JSON, "{\"service\":\"catalog\"}"));
    }

    @Override
    public Mono<Boolean> isHealthy() {
      return Mono.just(true);
    }
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
