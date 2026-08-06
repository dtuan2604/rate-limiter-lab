package lab.ratelimiter.gateway.http.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.PolicySnapshot;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.policy.control.ActivationResult;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyEvent;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
import lab.ratelimiter.gateway.policy.control.RefillPeriod;
import lab.ratelimiter.gateway.policy.control.SlidingWindowCounterAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.WindowDuration;
import lab.ratelimiter.gateway.policy.persistence.PolicySummary;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AdminPolicyApiTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

  @Mock private PostgresPolicyRepository repository;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToRouterFunction(
                AdminPolicyRoutes.routes(
                    new AdminPolicyHandler(
                        repository,
                        "local-admin",
                        new PolicySnapshotStore(new PolicySnapshot(0, Instant.EPOCH, List.of())))))
            .build();
  }

  @Test
  void listsStablePoliciesAndVersionsWithBoundedPagination() {
    when(repository.listPolicySummaries(0, 50))
        .thenReturn(Mono.just(List.of(new PolicySummary("catalog", "Catalog", 2, 2L))));
    when(repository.countPolicies()).thenReturn(Mono.just(1L));
    when(repository.listVersions("catalog", 0, 50))
        .thenReturn(Mono.just(List.of(stored(PolicyLifecycle.ACTIVE, 0, 2))));
    when(repository.countVersions("catalog")).thenReturn(Mono.just(1L));

    client
        .get()
        .uri("/admin/api/v1/policies")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].activeVersion")
        .isEqualTo(2)
        .jsonPath("$.total")
        .isEqualTo(1);
    client
        .get()
        .uri("/admin/api/v1/policies/catalog/versions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].status")
        .isEqualTo("ACTIVE");

    client.get().uri("/admin/api/v1/policies?size=101").exchange().expectStatus().isBadRequest();
  }

  @Test
  void matchTestEvaluatesCandidateWithoutRuntimeStateMutation() {
    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"request":{"method":"GET","path":"/proxy/catalog/items","headers":{"X-Client-Id":"client-a"}},"candidate":%s}
            """
                .formatted(definitionJson()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(true)
        .jsonPath("$.explanation[0]")
        .isEqualTo("method matched");

    verify(repository, org.mockito.Mockito.never()).loadActiveSet();
  }

  @Test
  void cloneReadAndLifecycleEndpointsExposeCoherentContracts() {
    var draft = stored(PolicyLifecycle.DRAFT, 0, 5);
    var archived =
        new StoredPolicyVersion(
            draft.policyId(),
            draft.name(),
            draft.version(),
            PolicyLifecycle.ARCHIVED,
            draft.revision(),
            draft.definition(),
            draft.createdAt(),
            draft.createdBy(),
            null,
            null);
    when(repository.createVersion(eq("catalog"), eq(1L), eq(2L), eq("local-admin"), any()))
        .thenReturn(Mono.just(draft));
    when(repository.findVersion("catalog", 1)).thenReturn(Mono.just(draft));
    when(repository.findPolicySummary("catalog"))
        .thenReturn(Mono.just(new PolicySummary("catalog", "Catalog", 2, null)));
    when(repository.archive(eq("catalog"), eq(1L), eq("local-admin"), any()))
        .thenReturn(Mono.just(archived));
    when(repository.restore(eq("catalog"), eq(1L), eq("local-admin"), any()))
        .thenReturn(Mono.just(draft));

    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"version\":2,\"sourceVersion\":1}")
        .exchange()
        .expectStatus()
        .isCreated();
    client
        .get()
        .uri("/admin/api/v1/policies/catalog")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.latestVersion")
        .isEqualTo(2);
    client
        .get()
        .uri("/admin/api/v1/policies/catalog/versions/1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.ETAG, "\"0\"");
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/1/archive")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("ARCHIVED");
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/1/restore")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("DRAFT");
  }

  @Test
  void disableUsesPropagationContractAndMissingResourcesReturnStructured404() {
    UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    var disabled = stored(PolicyLifecycle.DISABLED, 0, 2);
    when(repository.disable(eq("catalog"), eq(2L), eq("local-admin"), any()))
        .thenReturn(
            Mono.just(
                new ActivationResult(
                    disabled,
                    8,
                    new PolicyEvent(1, "POLICY_DISABLED", "catalog", 2, 8, eventId, NOW))));
    when(repository.findVersion("missing", 1)).thenReturn(Mono.empty());
    when(repository.findPolicySummary("missing")).thenReturn(Mono.empty());

    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/2/disable")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("DISABLED");
    client
        .get()
        .uri("/admin/api/v1/policies/missing/versions/1")
        .exchange()
        .expectStatus()
        .isNotFound();
    client.get().uri("/admin/api/v1/policies/missing").exchange().expectStatus().isNotFound();
  }

  @Test
  void malformedParametersValidationTransitionsAndUnexpectedFailuresAreStructured() {
    client.get().uri("/admin/api/v1/policies?page=bad").exchange().expectStatus().isBadRequest();
    client
        .put()
        .uri("/admin/api/v1/policies/catalog/versions/1")
        .header(HttpHeaders.IF_MATCH, "unquoted")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(definitionJson())
        .exchange()
        .expectStatus()
        .isBadRequest();

    when(repository.activate(eq("catalog"), eq(4L), eq("local-admin"), any()))
        .thenReturn(Mono.error(new IllegalStateException("invalid policy transition")));
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/4/activate")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("INVALID_POLICY_TRANSITION");

    when(repository.activate(eq("catalog"), eq(5L), eq("local-admin"), any()))
        .thenReturn(Mono.error(new IllegalArgumentException("limit is invalid")));
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/5/activate")
        .exchange()
        .expectStatus()
        .isEqualTo(422);

    when(repository.activate(eq("catalog"), eq(6L), eq("local-admin"), any()))
        .thenReturn(Mono.error(new RuntimeException("sensitive detail")));
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/6/activate")
        .exchange()
        .expectStatus()
        .isEqualTo(500)
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Administrative operation failed");
  }

  @Test
  void malformedAndNonPositivePathVersionsReturnStructuredValidationErrors() {
    client
        .get()
        .uri("/admin/api/v1/policies/catalog/versions/not-a-version")
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("POLICY_VALIDATION_FAILED");
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/0/activate")
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("POLICY_VALIDATION_FAILED");
    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/-1/archive")
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("POLICY_VALIDATION_FAILED");
  }

  @Test
  void matchTestExplainsMismatchesAndEmptyActiveSnapshot() {
    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"request":{"method":"POST","path":"/proxy/other","headers":{}},"candidate":%s}
            """
                .formatted(definitionJson()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(false)
        .jsonPath("$.explanation[2]")
        .isEqualTo("identity missing");
    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"request\":{\"method\":\"GET\",\"path\":\"/proxy/catalog/items\",\"headers\":{}}}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(false);
  }

  @Test
  void createsAnExplicitDraftAndRejectsUnknownFields() {
    when(repository.createPolicy(
            eq("catalog"), eq("Catalog"), eq(1L), any(), eq("local-admin"), any()))
        .thenReturn(Mono.just(stored(PolicyLifecycle.DRAFT, 0, 5)));

    client
        .post()
        .uri("/admin/api/v1/policies")
        .header("X-Correlation-Id", "create-request")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createJson(""))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectHeader()
        .valueEquals(HttpHeaders.ETAG, "\"0\"")
        .expectBody()
        .jsonPath("$.policyId")
        .isEqualTo("catalog")
        .jsonPath("$.status")
        .isEqualTo("DRAFT")
        .jsonPath("$.algorithm.configuration.limit")
        .isEqualTo(5);

    verify(repository)
        .createPolicy(
            eq("catalog"), eq("Catalog"), eq(1L), any(), eq("local-admin"), eq("create-request"));

    client
        .post()
        .uri("/admin/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createJson(",\"unknown\":true"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("INVALID_REQUEST_BODY");
  }

  @Test
  void createsAndMatchTestsStrictTypedTokenBucketPolicies() {
    when(repository.createPolicy(
            eq("catalog-token"), eq("Catalog token"), eq(1L), any(), eq("local-admin"), any()))
        .thenReturn(Mono.just(tokenBucketStored()));

    client
        .post()
        .uri("/admin/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(tokenBucketCreateJson(tokenBucketAlgorithmJson()))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.algorithm.type")
        .isEqualTo("TOKEN_BUCKET")
        .jsonPath("$.algorithm.configuration.capacity")
        .isEqualTo(10)
        .jsonPath("$.algorithm.configuration.initialTokens")
        .isEqualTo(4)
        .jsonPath("$.algorithm.configuration.refillTokens")
        .isEqualTo(2)
        .jsonPath("$.algorithm.configuration.refillPeriod")
        .isEqualTo("1s")
        .jsonPath("$.algorithm.configuration.requestCost")
        .isEqualTo(3);

    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"request":{"method":"GET","path":"/proxy/catalog/items","headers":{"X-Client-Id":"client-a"}},"candidate":%s}
            """
                .formatted(tokenBucketDefinitionJson(tokenBucketAlgorithmJson())))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(true)
        .jsonPath("$.algorithm")
        .isEqualTo("TOKEN_BUCKET");

    verify(repository, org.mockito.Mockito.never()).loadActiveSet();
  }

  @Test
  void createsAndMatchTestsTypedSlidingCountersWithoutLoadingRuntimeState() {
    when(repository.createPolicy(
            eq("catalog-sliding"), eq("Catalog sliding"), eq(1L), any(), eq("local-admin"), any()))
        .thenReturn(Mono.just(slidingCounterStored()));

    client
        .post()
        .uri("/admin/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(slidingCounterCreateJson(slidingCounterAlgorithmJson()))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.algorithm.type")
        .isEqualTo("SLIDING_WINDOW_COUNTER")
        .jsonPath("$.algorithm.configuration.limit")
        .isEqualTo(100)
        .jsonPath("$.algorithm.configuration.window")
        .isEqualTo("60s")
        .jsonPath("$.algorithm.configuration.requestCost")
        .isEqualTo(3);

    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"request":{"method":"GET","path":"/proxy/catalog/items","headers":{"X-Client-Id":"client-a"}},"candidate":%s}
            """
                .formatted(slidingCounterDefinitionJson(slidingCounterAlgorithmJson())))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(true)
        .jsonPath("$.algorithm")
        .isEqualTo("SLIDING_WINDOW_COUNTER");

    verify(repository, org.mockito.Mockito.never()).loadActiveSet();
  }

  @Test
  void rejectsMalformedAndCrossAlgorithmSlidingCounterFields() {
    for (String algorithm :
        List.of(
            "{\"type\":\"SLIDING_WINDOW_COUNTER\",\"configuration\":{\"limit\":100,\"windowMilliseconds\":60000,\"requestCost\":1}}",
            "{\"type\":\"SLIDING_WINDOW_COUNTER\",\"configuration\":{\"limit\":100.5,\"window\":\"60s\",\"requestCost\":1}}",
            "{\"type\":\"SLIDING_WINDOW_COUNTER\",\"configuration\":{\"limit\":100,\"window\":\"60s\",\"requestCost\":1,\"capacity\":100}}")) {
      client
          .post()
          .uri("/admin/api/v1/policies")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(slidingCounterCreateJson(algorithm))
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("INVALID_REQUEST_BODY");
    }

    client
        .post()
        .uri("/admin/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            slidingCounterCreateJson(
                "{\"type\":\"SLIDING_WINDOW_COUNTER\",\"configuration\":{\"limit\":100,\"window\":\"60s\"}}"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("POLICY_VALIDATION_FAILED");
  }

  @Test
  void rejectsUnknownMissingCrossAlgorithmDecimalAndUnknownTokenBucketFields() {
    for (String algorithm :
        List.of(
            "{\"type\":\"LEAKY_BUCKET\",\"configuration\":{}}",
            "{\"type\":\"TOKEN_BUCKET\",\"configuration\":{\"limit\":5,\"windowMilliseconds\":1000}}",
            "{\"type\":\"TOKEN_BUCKET\",\"configuration\":{\"capacity\":10.5,\"initialTokens\":4,\"refillTokens\":2,\"refillPeriod\":\"1s\",\"requestCost\":1}}",
            "{\"type\":\"TOKEN_BUCKET\",\"configuration\":{\"capacity\":10,\"initialTokens\":4,\"refillTokens\":2,\"refillPeriod\":\"1s\",\"requestCost\":1,\"unknown\":true}}")) {
      client
          .post()
          .uri("/admin/api/v1/policies")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(tokenBucketCreateJson(algorithm))
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("INVALID_REQUEST_BODY");
    }

    client
        .post()
        .uri("/admin/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            tokenBucketCreateJson(
                "{\"type\":\"TOKEN_BUCKET\",\"configuration\":{\"capacity\":10,\"initialTokens\":4,\"refillTokens\":2,\"refillPeriod\":\"1s\"}}"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("POLICY_VALIDATION_FAILED");
  }

  @Test
  void activationReturnsCommittedPropagationMetadataAndFreshStateWarning() {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    var active = stored(PolicyLifecycle.ACTIVE, 0, 2);
    when(repository.activate(eq("catalog"), eq(2L), eq("local-admin"), any()))
        .thenReturn(
            Mono.just(
                new ActivationResult(
                    active,
                    7,
                    new PolicyEvent(1, "POLICY_ACTIVATED", "catalog", 2, 7, eventId, NOW))));

    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/2/activate")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.policySetRevision")
        .isEqualTo(7)
        .jsonPath("$.eventId")
        .isEqualTo(eventId.toString())
        .jsonPath("$.propagationStatus")
        .isEqualTo("PENDING")
        .jsonPath("$.runtimeState")
        .isEqualTo("FRESH_VERSION_STATE");
  }

  @Test
  void missingIfMatchAndRevisionMismatchUseStructuredPreconditionErrors() {
    client
        .put()
        .uri("/admin/api/v1/policies/catalog/versions/1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(definitionJson())
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("PRECONDITION_REQUIRED");

    when(repository.replaceDraft(eq("catalog"), eq(1L), eq(0L), any(), eq("local-admin"), any()))
        .thenReturn(Mono.error(new IllegalStateException("policy revision does not match")));
    client
        .put()
        .uri("/admin/api/v1/policies/catalog/versions/1")
        .header(HttpHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(definitionJson())
        .exchange()
        .expectStatus()
        .isEqualTo(412)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("POLICY_REVISION_MISMATCH");
  }

  @Test
  void constructorAndRequestBoundaryValidationRejectInvalidAdministrativeInput() {
    assertThatThrownBy(() -> new AdminPolicyHandler(repository, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdminPolicyHandler(repository, " "))
        .isInstanceOf(IllegalArgumentException.class);

    client.post().uri("/admin/api/v1/policies").exchange().expectStatus().isEqualTo(422);
    client
        .post()
        .uri("/admin/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createJson("").replace("FIXED_WINDOW", "TOKEN_BUCKET"))
        .exchange()
        .expectStatus()
        .isBadRequest();
    client.get().uri("/admin/api/v1/policies?page=-1").exchange().expectStatus().isBadRequest();
    client.get().uri("/admin/api/v1/policies?size=0").exchange().expectStatus().isBadRequest();

    when(repository.listPolicySummaries(1, 25)).thenReturn(Mono.just(List.of()));
    when(repository.countPolicies()).thenReturn(Mono.just(0L));
    client.get().uri("/admin/api/v1/policies?page=1&size=25").exchange().expectStatus().isOk();
  }

  @Test
  void invalidQuotedRevisionsAreRejectedBeforeRepositoryMutation() {
    client
        .put()
        .uri("/admin/api/v1/policies/catalog/versions/1")
        .header(HttpHeaders.IF_MATCH, "\"not-a-revision\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(definitionJson())
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .put()
        .uri("/admin/api/v1/policies/catalog/versions/1")
        .header(HttpHeaders.IF_MATCH, "\"-1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(definitionJson())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void matchTestCoversMissingBlankAndActiveIdentityPaths() {
    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"request":{"method":"GET","path":"/proxy/catalog/items"},"candidate":%s}
            """
                .formatted(definitionJson()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(false);
    client
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"request":{"method":"GET","path":"/proxy/catalog/items","headers":{"X-Client-Id":""}},"candidate":%s}
            """
                .formatted(definitionJson()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(false);

    CompiledPolicy policy =
        new CompiledPolicy(
            "catalog.items",
            "/proxy/catalog/items",
            "GET",
            new FixedWindowPolicy(
                new PolicyId("catalog"), new PolicyVersion(3), 2, Duration.ofSeconds(10)),
            FailureMode.FAIL_CLOSED,
            100);
    WebTestClient activeClient =
        WebTestClient.bindToRouterFunction(
                AdminPolicyRoutes.routes(
                    new AdminPolicyHandler(
                        repository,
                        "local-admin",
                        new PolicySnapshotStore(new PolicySnapshot(3, NOW, List.of(policy))))))
            .build();
    activeClient
        .post()
        .uri("/admin/api/v1/policies/match-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"request\":{\"method\":\"GET\",\"path\":\"/proxy/catalog/items\",\"headers\":{}}}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.matched")
        .isEqualTo(true)
        .jsonPath("$.version")
        .isEqualTo(3);
  }

  @Test
  void wrappedRevisionConflictIsUnwrappedWithoutLeakingInternalDetails() {
    when(repository.activate(eq("catalog"), eq(7L), eq("local-admin"), any()))
        .thenReturn(
            Mono.error(
                new CompletionException(
                    new IllegalStateException("policy revision does not match"))));

    client
        .post()
        .uri("/admin/api/v1/policies/catalog/versions/7/activate")
        .exchange()
        .expectStatus()
        .isEqualTo(412);
  }

  private static StoredPolicyVersion stored(PolicyLifecycle lifecycle, long revision, long limit) {
    return new StoredPolicyVersion(
        "catalog",
        "Catalog",
        lifecycle == PolicyLifecycle.ACTIVE ? 2 : 1,
        lifecycle,
        revision,
        new PolicyDefinition(
            "Catalog policy",
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            limit,
            Duration.ofSeconds(10),
            FailureMode.FAIL_CLOSED,
            100),
        NOW,
        "local-admin",
        lifecycle == PolicyLifecycle.ACTIVE ? NOW : null,
        lifecycle == PolicyLifecycle.ACTIVE ? "local-admin" : null);
  }

  private static StoredPolicyVersion tokenBucketStored() {
    return new StoredPolicyVersion(
        "catalog-token",
        "Catalog token",
        1,
        PolicyLifecycle.DRAFT,
        0,
        new PolicyDefinition(
            "Catalog token policy",
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            new TokenBucketAlgorithmDefinition(10, 4, 2, RefillPeriod.parse("1s"), 3),
            FailureMode.FAIL_CLOSED,
            100),
        NOW,
        "local-admin",
        null,
        null);
  }

  private static StoredPolicyVersion slidingCounterStored() {
    return new StoredPolicyVersion(
        "catalog-sliding",
        "Catalog sliding",
        1,
        PolicyLifecycle.DRAFT,
        0,
        new PolicyDefinition(
            "Catalog sliding policy",
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            new SlidingWindowCounterAlgorithmDefinition(100, WindowDuration.parse("60s"), 3),
            FailureMode.FAIL_CLOSED,
            100),
        NOW,
        "local-admin",
        null,
        null);
  }

  private static String slidingCounterCreateJson(String algorithmJson) {
    return """
        {"policyId":"catalog-sliding","name":"Catalog sliding","version":1,"definition":%s}
        """
        .formatted(slidingCounterDefinitionJson(algorithmJson));
  }

  private static String slidingCounterDefinitionJson(String algorithmJson) {
    return """
        {"description":"Catalog sliding policy","match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":%s,"failureMode":"FAIL_CLOSED","priority":100}
        """
        .formatted(algorithmJson);
  }

  private static String slidingCounterAlgorithmJson() {
    return """
        {"type":"SLIDING_WINDOW_COUNTER","configuration":{"limit":100,"window":"60s","requestCost":3}}
        """;
  }

  private static String tokenBucketCreateJson(String algorithmJson) {
    return """
        {"policyId":"catalog-token","name":"Catalog token","version":1,"definition":%s}
        """
        .formatted(tokenBucketDefinitionJson(algorithmJson));
  }

  private static String tokenBucketDefinitionJson(String algorithmJson) {
    return """
        {"description":"Catalog token policy","match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":%s,"failureMode":"FAIL_CLOSED","priority":100}
        """
        .formatted(algorithmJson);
  }

  private static String tokenBucketAlgorithmJson() {
    return """
        {"type":"TOKEN_BUCKET","configuration":{"capacity":10,"initialTokens":4,"refillTokens":2,"refillPeriod":"1s","requestCost":3}}
        """;
  }

  private static String createJson(String extra) {
    return """
        {"policyId":"catalog","name":"Catalog","version":1,"definition":%s%s}
        """
        .formatted(definitionJson(), extra);
  }

  private static String definitionJson() {
    return """
        {"description":"Catalog policy","match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":{"type":"FIXED_WINDOW","configuration":{"limit":5,"windowMilliseconds":10000}},"failureMode":"FAIL_CLOSED","priority":100}
        """;
  }
}
