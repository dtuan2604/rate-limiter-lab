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
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateAdapter;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateResult;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.application.TokenBucketStateAdapter;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.CompiledSlidingWindowCounterAlgorithm;
import lab.ratelimiter.gateway.policy.PolicySnapshot;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.proxy.CatalogBackendClient;
import lab.ratelimiter.gateway.proxy.CatalogBackendRequest;
import lab.ratelimiter.gateway.proxy.CatalogBackendResponse;
import lab.ratelimiter.gateway.state.redis.RedisStateException;
import lab.ratelimiter.gateway.state.redis.SlidingCounterRotation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class SlidingWindowCounterHttpContractTest {

  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
  private static final SlidingWindowCounterPolicy POLICY =
      new SlidingWindowCounterPolicy(
          new PolicyId("catalog-sliding"), new PolicyVersion(3), 10, Duration.ofSeconds(10));

  @Test
  void weightedAllowedAndRejectedResponsesUseSlidingHeaderSemanticsAndShortCircuitBackend() {
    RecordingBackend backend = new RecordingBackend();
    AtomicInteger calls = new AtomicInteger();
    SlidingWindowCounterStateAdapter sliding =
        (policy, requestCost, identity) ->
            Mono.just(calls.getAndIncrement() == 0 ? allowed(policy) : rejected(policy));
    WebTestClient client = client(FailureMode.FAIL_CLOSED, sliding, backend);

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
        .valueEquals("RateLimit-Reset", "15")
        .expectHeader()
        .valueEquals("X-RateLimit-Policy", "catalog-sliding");

    client
        .get()
        .uri("/proxy/catalog/items")
        .header("X-Client-Id", "cost-three")
        .header("X-Correlation-Id", "sliding-rejected")
        .exchange()
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals("RateLimit-Limit", "10")
        .expectHeader()
        .valueEquals("RateLimit-Remaining", "2")
        .expectHeader()
        .valueEquals("RateLimit-Reset", "15")
        .expectHeader()
        .valueEquals("Retry-After", "1")
        .expectHeader()
        .valueEquals("X-RateLimit-Policy-Version", "3")
        .expectBody()
        .json(
            """
            {
              "status": 429,
              "error": "RATE_LIMIT_EXCEEDED",
              "message": "Request limit exceeded",
              "policy": "catalog-sliding",
              "policyVersion": 3,
              "retryAfterMilliseconds": 750,
              "correlationId": "sliding-rejected"
            }
            """,
            JsonCompareMode.STRICT);

    assertThat(backend.requests).hasSize(1);
  }

  @Test
  void everySlidingRedisFailureUsesFailOpenOrFailClosedWithoutFabricatedCapacity() {
    for (RedisOutcome failure :
        List.of(
            RedisOutcome.TIMEOUT,
            RedisOutcome.CONNECTION_FAILURE,
            RedisOutcome.SCRIPT_ERROR,
            RedisOutcome.MALFORMED_STATE,
            RedisOutcome.MALFORMED_RESPONSE,
            RedisOutcome.CLOCK_ROLLBACK)) {
      SlidingWindowCounterStateAdapter failing =
          (policy, requestCost, identity) ->
              Mono.error(new RedisStateException(failure, "sanitized"));
      RecordingBackend failOpenBackend = new RecordingBackend();
      client(FailureMode.FAIL_OPEN, failing, failOpenBackend)
          .get()
          .uri("/proxy/catalog/items")
          .header("X-Client-Id", "fail-open-" + failure)
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
          .header("X-Client-Id", "fail-closed-" + failure)
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("RATE_LIMIT_STATE_UNAVAILABLE");
      assertThat(failClosedBackend.requests).isEmpty();
    }
  }

  private static WebTestClient client(
      FailureMode failureMode, SlidingWindowCounterStateAdapter sliding, RecordingBackend backend) {
    CompiledPolicy compiled =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new CompiledSlidingWindowCounterAlgorithm(POLICY, 3),
            failureMode,
            100);
    FixedWindowStateAdapter fixed =
        (policy, identity, request) ->
            Mono.error(new AssertionError("Fixed Window adapter must not be called"));
    TokenBucketStateAdapter token =
        (policy, cost, activation, identity) ->
            Mono.error(new AssertionError("Token Bucket adapter must not be called"));
    GatewayHttpHandler handler =
        new GatewayHttpHandler(
            new PolicySnapshotStore(new PolicySnapshot(3, NOW, List.of(compiled))),
            new ClientIdentityExtractor(),
            new RateLimitService(fixed, token, sliding),
            backend,
            "gateway-sliding-test",
            true);
    return WebTestClient.bindToRouterFunction(GatewayRoutes.routes(handler)).build();
  }

  private static SlidingWindowCounterStateResult allowed(SlidingWindowCounterPolicy policy) {
    return result(policy, true, 7, Duration.ZERO, Duration.ofMillis(14_500));
  }

  private static SlidingWindowCounterStateResult rejected(SlidingWindowCounterPolicy policy) {
    return result(policy, false, 2, Duration.ofMillis(750), Duration.ofMillis(14_500));
  }

  private static SlidingWindowCounterStateResult result(
      SlidingWindowCounterPolicy policy,
      boolean allowed,
      long remaining,
      Duration retry,
      Duration reset) {
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            policy.limit(),
            remaining,
            allowed ? Optional.empty() : Optional.of(retry),
            Optional.of(NOW.plus(reset)),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new SlidingWindowCounterStateResult(
        decision,
        42,
        allowed ? 3 : 6,
        4,
        Duration.ofSeconds(5),
        allowed ? 30 : 80,
        allowed ? 3 : 8,
        3,
        remaining,
        retry,
        reset,
        NOW,
        reset,
        SlidingCounterRotation.SAME,
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
