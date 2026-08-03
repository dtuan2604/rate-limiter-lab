package lab.ratelimiter.gateway.http;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lab.ratelimiter.gateway.application.RateLimitEvaluation;
import lab.ratelimiter.gateway.application.RateLimitOutcome;
import lab.ratelimiter.gateway.application.RateLimitService;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.observability.RateLimitDecisionLogger;
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
  private static final String GATEWAY_INSTANCE = "X-Gateway-Instance";
  private static final String RATE_LIMIT_DEGRADED = "X-RateLimit-Degraded";
  private final StaticPolicySnapshot policies;
  private final ClientIdentityExtractor identityExtractor;
  private final RateLimitService rateLimitService;
  private final CatalogBackendClient backendClient;
  private final String instanceId;
  private final boolean exposeInstanceHeader;
  private final RateLimitDecisionLogger decisionLogger;

  public GatewayHttpHandler(
      StaticPolicySnapshot policies,
      ClientIdentityExtractor identityExtractor,
      RateLimitService rateLimitService,
      CatalogBackendClient backendClient,
      String instanceId,
      boolean exposeInstanceHeader) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.identityExtractor = Objects.requireNonNull(identityExtractor, "identityExtractor");
    this.rateLimitService = Objects.requireNonNull(rateLimitService, "rateLimitService");
    this.backendClient = Objects.requireNonNull(backendClient, "backendClient");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.exposeInstanceHeader = exposeInstanceHeader;
    this.decisionLogger = new RateLimitDecisionLogger(instanceId);
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
    LimiterIdentity limiterIdentity = identity.orElseThrow();

    return rateLimitService
        .evaluate(policy, limiterIdentity)
        .flatMap(
            evaluation -> {
              decisionLogger.log(correlationId, policy, limiterIdentity, evaluation);
              if (evaluation.outcome() == RateLimitOutcome.REJECT) {
                return rateLimitExceeded(evaluation, correlationId);
              }
              if (evaluation.outcome() == RateLimitOutcome.STATE_UNAVAILABLE) {
                return rateLimitStateUnavailable(correlationId);
              }
              return forwardAllowed(request, clientId, correlationId, evaluation);
            });
  }

  private Mono<ServerResponse> forwardAllowed(
      ServerRequest request,
      String clientId,
      String correlationId,
      RateLimitEvaluation evaluation) {
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
        .flatMap(response -> allowed(response, evaluation, correlationId))
        .onErrorResume(ignored -> backendUnavailable(evaluation, correlationId));
  }

  private Mono<ServerResponse> allowed(
      CatalogBackendResponse backendResponse,
      RateLimitEvaluation evaluation,
      String correlationId) {
    return ServerResponse.status(backendResponse.status())
        .contentType(backendResponse.contentType())
        .headers(headers -> applyDecisionHeaders(headers, evaluation, correlationId, false))
        .bodyValue(backendResponse.body());
  }

  private Mono<ServerResponse> rateLimitExceeded(
      RateLimitEvaluation evaluation, String correlationId) {
    RateLimitDecision decision = evaluation.rateLimitDecision().orElseThrow();
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
        .headers(
            headers -> {
              RateLimitHeaders.apply(
                  headers, decision, correlationId, evaluation.resetAfter().orElseThrow(), true);
              applyGatewayHeaders(headers, correlationId, evaluation);
            })
        .bodyValue(body);
  }

  private Mono<ServerResponse> backendUnavailable(
      RateLimitEvaluation evaluation, String correlationId) {
    GatewayErrorResponse body =
        new GatewayErrorResponse(
            502, "BACKEND_UNAVAILABLE", "Catalog backend is unavailable", correlationId);
    return ServerResponse.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> applyDecisionHeaders(headers, evaluation, correlationId, false))
        .bodyValue(body);
  }

  private Mono<ServerResponse> rateLimitStateUnavailable(String correlationId) {
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        new GatewayErrorResponse(
            503, "RATE_LIMIT_STATE_UNAVAILABLE", "Rate-limit state is unavailable", correlationId));
  }

  private Mono<ServerResponse> missingClientId(String correlationId) {
    return error(
        HttpStatus.BAD_REQUEST,
        new GatewayErrorResponse(
            400, "MISSING_CLIENT_ID", "X-Client-Id header is required", correlationId));
  }

  private Mono<ServerResponse> routeNotFound(String correlationId) {
    return error(
        HttpStatus.NOT_FOUND,
        new GatewayErrorResponse(
            404, "ROUTE_NOT_FOUND", "No proxy route matches the request", correlationId));
  }

  private Mono<ServerResponse> error(HttpStatus status, GatewayErrorResponse body) {
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> applyGatewayHeaders(headers, body.correlationId(), null))
        .bodyValue(body);
  }

  private void applyDecisionHeaders(
      HttpHeaders headers,
      RateLimitEvaluation evaluation,
      String correlationId,
      boolean includeRetryAfter) {
    evaluation
        .rateLimitDecision()
        .ifPresent(
            decision ->
                RateLimitHeaders.apply(
                    headers,
                    decision,
                    correlationId,
                    evaluation.resetAfter().orElseThrow(),
                    includeRetryAfter));
    applyGatewayHeaders(headers, correlationId, evaluation);
  }

  private void applyGatewayHeaders(
      HttpHeaders headers, String correlationId, RateLimitEvaluation evaluation) {
    headers.set(CORRELATION_ID, correlationId);
    if (exposeInstanceHeader) {
      headers.set(GATEWAY_INSTANCE, instanceId);
    }
    if (evaluation != null && evaluation.outcome() == RateLimitOutcome.DEGRADED_ALLOW) {
      headers.set(RATE_LIMIT_DEGRADED, "true");
    }
  }

  private static String correlationId(ServerRequest request) {
    String existing = request.headers().firstHeader(CORRELATION_ID);
    return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
  }
}
