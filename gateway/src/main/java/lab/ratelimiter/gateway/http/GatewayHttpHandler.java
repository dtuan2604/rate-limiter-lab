package lab.ratelimiter.gateway.http;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lab.ratelimiter.gateway.application.RateLimitService;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.StaticPolicySnapshot;
import lab.ratelimiter.gateway.proxy.CatalogBackendClient;
import lab.ratelimiter.gateway.proxy.CatalogBackendRequest;
import lab.ratelimiter.gateway.proxy.CatalogBackendResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public final class GatewayHttpHandler {

  private static final String CLIENT_ID = "X-Client-Id";
  private static final String CORRELATION_ID = "X-Correlation-Id";
  private final StaticPolicySnapshot policies;
  private final ClientIdentityExtractor identityExtractor;
  private final RateLimitService rateLimitService;
  private final CatalogBackendClient backendClient;
  private final Clock clock;

  public GatewayHttpHandler(
      StaticPolicySnapshot policies,
      ClientIdentityExtractor identityExtractor,
      RateLimitService rateLimitService,
      CatalogBackendClient backendClient,
      Clock clock) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.identityExtractor = Objects.requireNonNull(identityExtractor, "identityExtractor");
    this.rateLimitService = Objects.requireNonNull(rateLimitService, "rateLimitService");
    this.backendClient = Objects.requireNonNull(backendClient, "backendClient");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Mono<ServerResponse> proxyCatalogItems(ServerRequest request) {
    String correlationId = correlationId(request);
    Optional<CompiledPolicy> matched = policies.match(request.method().name(), request.path());
    if (matched.isEmpty()) {
      return routeNotFound(correlationId);
    }
    CompiledPolicy policy = matched.orElseThrow();
    String clientId = request.headers().firstHeader(CLIENT_ID);
    Optional<LimiterIdentity> identity;
    try {
      identity = identityExtractor.extract(clientId, policy.routeId());
    } catch (IllegalArgumentException exception) {
      identity = Optional.empty();
    }
    if (identity.isEmpty()) {
      return missingClientId(correlationId);
    }

    RateLimitDecision decision = rateLimitService.evaluate(policy, identity.orElseThrow());
    if (!decision.allowed()) {
      return rateLimitExceeded(decision, correlationId);
    }

    CatalogBackendRequest backendRequest =
        new CatalogBackendRequest(
            request.uri().getRawQuery(),
            clientId,
            correlationId,
            request.headers().firstHeader(HttpHeaders.ACCEPT) == null
                ? MediaType.APPLICATION_JSON_VALUE
                : request.headers().firstHeader(HttpHeaders.ACCEPT));
    return backendClient
        .forward(backendRequest)
        .flatMap(response -> allowed(response, decision, correlationId))
        .onErrorResume(ignored -> backendUnavailable(decision, correlationId));
  }

  private Mono<ServerResponse> allowed(
      CatalogBackendResponse backendResponse, RateLimitDecision decision, String correlationId) {
    return ServerResponse.status(backendResponse.status())
        .contentType(backendResponse.contentType())
        .headers(headers -> RateLimitHeaders.apply(headers, decision, correlationId, clock, false))
        .bodyValue(backendResponse.body());
  }

  private Mono<ServerResponse> rateLimitExceeded(RateLimitDecision decision, String correlationId) {
    Duration retryAfter = decision.retryAfter().orElseThrow();
    RateLimitErrorResponse body =
        new RateLimitErrorResponse(
            429,
            "RATE_LIMIT_EXCEEDED",
            "Request limit exceeded",
            decision.policyId().value(),
            retryAfter.toMillis(),
            correlationId);
    return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> RateLimitHeaders.apply(headers, decision, correlationId, clock, true))
        .bodyValue(body);
  }

  private Mono<ServerResponse> backendUnavailable(
      RateLimitDecision decision, String correlationId) {
    GatewayErrorResponse body =
        new GatewayErrorResponse(
            502, "BACKEND_UNAVAILABLE", "Catalog backend is unavailable", correlationId);
    return ServerResponse.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> RateLimitHeaders.apply(headers, decision, correlationId, clock, false))
        .bodyValue(body);
  }

  private static Mono<ServerResponse> missingClientId(String correlationId) {
    return error(
        HttpStatus.BAD_REQUEST,
        new GatewayErrorResponse(
            400, "MISSING_CLIENT_ID", "X-Client-Id header is required", correlationId));
  }

  private static Mono<ServerResponse> routeNotFound(String correlationId) {
    return error(
        HttpStatus.NOT_FOUND,
        new GatewayErrorResponse(
            404, "ROUTE_NOT_FOUND", "No proxy route matches the request", correlationId));
  }

  private static Mono<ServerResponse> error(HttpStatus status, GatewayErrorResponse body) {
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(CORRELATION_ID, body.correlationId())
        .bodyValue(body);
  }

  private static String correlationId(ServerRequest request) {
    String existing = request.headers().firstHeader(CORRELATION_ID);
    return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
  }
}
