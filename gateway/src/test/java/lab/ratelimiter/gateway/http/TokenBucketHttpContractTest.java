package lab.ratelimiter.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.application.FixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.RateLimitService;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.application.TokenBucketStateAdapter;
import lab.ratelimiter.gateway.application.TokenBucketStateResult;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.CompiledTokenBucketAlgorithm;
import lab.ratelimiter.gateway.policy.PolicySnapshot;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.proxy.CatalogBackendClient;
import lab.ratelimiter.gateway.proxy.CatalogBackendRequest;
import lab.ratelimiter.gateway.proxy.CatalogBackendResponse;
import lab.ratelimiter.gateway.state.redis.RedisStateException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class TokenBucketHttpContractTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final TokenBucketPolicy TOKEN_POLICY =
      new TokenBucketPolicy(
          new PolicyId("catalog-token"), new PolicyVersion(2), 10, 10, 2, Duration.ofSeconds(1));

  @Test
  void allowedAndRejectedResponsesUseTokenBucketHeaderSemanticsAndRejectBeforeBackend() {
    RecordingBackend backend = new RecordingBackend();
    AtomicInteger calls = new AtomicInteger();
    TokenBucketStateAdapter token =
        (policy, requestCost, activationTime, identity) ->
            Mono.just(calls.getAndIncrement() == 0 ? allowed(policy) : rejected(policy));
    WebTestClient client = client(FailureMode.FAIL_CLOSED, token, backend);

    client
        .get()
        .uri("/proxy/catalog/items")
        .header("X-Client-Id", "cost-three")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("RateLimit-Limit", "10")
        .expectHeader()
        .valueEquals("RateLimit-Remaining", "7")
        .expectHeader()
        .valueEquals("RateLimit-Reset", "2")
        .expectHeader()
        .valueEquals("X-RateLimit-Policy", "catalog-token");

    client
        .get()
        .uri("/proxy/catalog/items")
        .header("X-Client-Id", "cost-three")
        .header("X-Correlation-Id", "token-rejected")
        .exchange()
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals("RateLimit-Limit", "10")
        .expectHeader()
        .valueEquals("RateLimit-Remaining", "1")
        .expectHeader()
        .valueEquals("RateLimit-Reset", "5")
        .expectHeader()
        .valueEquals("Retry-After", "1")
        .expectHeader()
        .valueEquals("X-RateLimit-Policy", "catalog-token")
        .expectHeader()
        .valueEquals("X-RateLimit-Policy-Version", "2")
        .expectBody()
        .json(
            """
            {
              "status": 429,
              "error": "RATE_LIMIT_EXCEEDED",
              "message": "Request limit exceeded",
              "policy": "catalog-token",
              "policyVersion": 2,
              "retryAfterMilliseconds": 750,
              "correlationId": "token-rejected"
            }
            """,
            JsonCompareMode.STRICT);

    assertThat(backend.requests).hasSize(1);
  }

  @Test
  void tokenBucketRedisFailureUsesPolicyFailOpenAndFailClosedWithoutFallbackState() {
    TokenBucketStateAdapter failing =
        (policy, requestCost, activationTime, identity) ->
            Mono.error(new RedisStateException(RedisOutcome.SCRIPT_ERROR, "sanitized"));
    RecordingBackend failOpenBackend = new RecordingBackend();
    client(FailureMode.FAIL_OPEN, failing, failOpenBackend)
        .get()
        .uri("/proxy/catalog/items")
        .header("X-Client-Id", "fail-open")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-RateLimit-Degraded", "true")
        .expectHeader()
        .doesNotExist("RateLimit-Remaining");
    assertThat(failOpenBackend.requests).hasSize(1);

    RecordingBackend failClosedBackend = new RecordingBackend();
    client(FailureMode.FAIL_CLOSED, failing, failClosedBackend)
        .get()
        .uri("/proxy/catalog/items")
        .header("X-Client-Id", "fail-closed")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("RATE_LIMIT_STATE_UNAVAILABLE");
    assertThat(failClosedBackend.requests).isEmpty();
  }

  private static WebTestClient client(
      FailureMode failureMode, TokenBucketStateAdapter token, RecordingBackend backend) {
    CompiledPolicy compiled =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new CompiledTokenBucketAlgorithm(TOKEN_POLICY, 3, NOW.minusSeconds(10)),
            failureMode,
            100);
    FixedWindowStateAdapter fixed =
        (policy, identity, request) ->
            Mono.error(new AssertionError("Fixed Window adapter must not be called"));
    GatewayHttpHandler handler =
        new GatewayHttpHandler(
            new PolicySnapshotStore(new PolicySnapshot(2, NOW, List.of(compiled))),
            new ClientIdentityExtractor(),
            new RateLimitService(fixed, token),
            backend,
            "gateway-token-test",
            true);
    return WebTestClient.bindToRouterFunction(GatewayRoutes.routes(handler)).build();
  }

  private static TokenBucketStateResult allowed(TokenBucketPolicy policy) {
    return result(policy, true, 7_000, Duration.ZERO, Duration.ofMillis(1_500));
  }

  private static TokenBucketStateResult rejected(TokenBucketPolicy policy) {
    return result(policy, false, 1_500, Duration.ofMillis(750), Duration.ofMillis(4_250));
  }

  private static TokenBucketStateResult result(
      TokenBucketPolicy policy,
      boolean allowed,
      long remainingScaled,
      Duration retry,
      Duration reset) {
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            policy.capacity(),
            remainingScaled / 1_000,
            allowed ? Optional.empty() : Optional.of(retry),
            Optional.of(NOW.plus(reset)),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new TokenBucketStateResult(
        decision,
        remainingScaled,
        3_000,
        2_000,
        Duration.ofSeconds(1),
        retry,
        reset,
        NOW,
        reset,
        0,
        false,
        StateBackend.REDIS,
        allowed ? RedisOutcome.ALLOWED : RedisOutcome.REJECTED);
  }

  private static final class RecordingBackend implements CatalogBackendClient {

    private final List<CatalogBackendRequest> requests = new ArrayList<>();

    @Override
    public Mono<CatalogBackendResponse> forward(CatalogBackendRequest request) {
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
}
