package lab.ratelimiter.gateway.http.admin;

import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.PolicyEventConsumer;
import lab.ratelimiter.gateway.policy.PolicyInvalidationEvent;
import lab.ratelimiter.gateway.policy.PolicyPropagationStatus;
import lab.ratelimiter.gateway.policy.PolicyReconciler;
import lab.ratelimiter.gateway.policy.PolicySnapshot;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.policy.ProcessedPolicyEvent;
import lab.ratelimiter.gateway.policy.ReloadOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class PolicySnapshotEndpointTest {

  @Mock private PolicyReconciler reconciler;
  @Mock private PolicyEventConsumer consumer;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    var policy =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new FixedWindowPolicy(
                new PolicyId("catalog"), new PolicyVersion(2), 2, Duration.ofSeconds(10)),
            FailureMode.FAIL_CLOSED,
            100);
    var store =
        new PolicySnapshotStore(
            new PolicySnapshot(7, Instant.parse("2026-08-03T12:00:00Z"), List.of(policy)));
    client =
        WebTestClient.bindToRouterFunction(
                InternalPolicyRoutes.routes(
                    new PolicySnapshotEndpointHandler(store, reconciler, consumer, "gateway-2")))
            .build();
  }

  @Test
  void returnsOnlySanitizedPerReplicaSnapshotMetadata() {
    when(reconciler.lastSuccessfulReconciliation())
        .thenReturn(Instant.parse("2026-08-03T12:00:30Z"));
    client
        .get()
        .uri("/internal/policy-snapshot")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.gatewayInstanceId")
        .isEqualTo("gateway-2")
        .jsonPath("$.snapshotRevision")
        .isEqualTo(7)
        .jsonPath("$.activePolicies[0].policyId")
        .isEqualTo("catalog")
        .jsonPath("$.activePolicies[0].version")
        .isEqualTo(2)
        .jsonPath("$.activePolicies[0].algorithm")
        .isEqualTo("FIXED_WINDOW")
        .jsonPath("$.lastSuccessfulReconciliation")
        .isEqualTo("2026-08-03T12:00:30Z")
        .jsonPath("$.lastEventProcessed")
        .doesNotExist()
        .jsonPath("$.degraded")
        .isEqualTo(false)
        .jsonPath("$.degradationReasons")
        .isArray();
  }

  @Test
  void snapshotRoutesDoNotRegisterAcceptanceEventControls() {
    client.post().uri("/internal/policy-events/pause").exchange().expectStatus().isNotFound();
    client.post().uri("/internal/policy-events/resume").exchange().expectStatus().isNotFound();
  }

  @Test
  void reportsLastProcessedEventAndReconciliationDegradation() {
    Instant processedAt = Instant.parse("2026-08-03T12:00:45Z");
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(reconciler.degraded()).thenReturn(true);
    when(consumer.lastProcessedEvent())
        .thenReturn(
            new ProcessedPolicyEvent(
                new PolicyInvalidationEvent(
                    1, "POLICY_ACTIVATED", "catalog", 2, 7, eventId, processedAt),
                ReloadOutcome.INSTALLED,
                processedAt));

    client
        .get()
        .uri("/internal/policy-snapshot")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.lastSuccessfulReconciliation")
        .doesNotExist()
        .jsonPath("$.lastEventProcessed.eventId")
        .isEqualTo(eventId.toString())
        .jsonPath("$.degraded")
        .isEqualTo(true)
        .jsonPath("$.degradationReasons[0]")
        .isEqualTo("POSTGRES_RECONCILIATION_UNAVAILABLE");
  }

  @Test
  void reportsUnavailableEventSubscriptionAsDegradedWithoutExposingDetails() {
    var store = new PolicySnapshotStore(new PolicySnapshot(0, Instant.EPOCH, List.of()));
    PolicyPropagationStatus propagation = new PolicyPropagationStatus();
    WebTestClient degradedClient =
        WebTestClient.bindToRouterFunction(
                InternalPolicyRoutes.routes(
                    new PolicySnapshotEndpointHandler(
                        store, reconciler, consumer, propagation, "gateway-1")))
            .build();

    degradedClient
        .get()
        .uri("/internal/policy-snapshot")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.degraded")
        .isEqualTo(true)
        .jsonPath("$.degradationReasons[0]")
        .isEqualTo("REDIS_POLICY_SUBSCRIPTION_UNAVAILABLE");
  }
}
