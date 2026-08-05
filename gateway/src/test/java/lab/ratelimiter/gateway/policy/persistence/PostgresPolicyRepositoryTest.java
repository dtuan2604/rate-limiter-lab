package lab.ratelimiter.gateway.policy.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.policy.control.ActivationResult;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
import lab.ratelimiter.gateway.policy.control.RefillPeriod;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgresPolicyRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.6-alpine"));

  private static PostgresPolicyRepository repository;

  @BeforeAll
  static void setUpDatabase() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    var options =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
            .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
            .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
            .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
            .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
            .build();
    var connectionFactory = ConnectionFactories.get(options);
    var template = new R2dbcEntityTemplate(connectionFactory);
    repository =
        new PostgresPolicyRepository(
            template.getDatabaseClient(),
            TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @BeforeEach
  void clearData() {
    repository.deleteAllForTests().block();
  }

  @Test
  void policyAndTypedVersionRoundTripWithAuditMetadata() {
    StoredPolicyVersion created =
        repository
            .createPolicy(
                "catalog-client-fixed-window",
                "Catalog client fixed window",
                1,
                definition(5),
                "local-admin",
                "create-correlation")
            .block();

    assertThat(created.policyId()).isEqualTo("catalog-client-fixed-window");
    assertThat(created.name()).isEqualTo("Catalog client fixed window");
    assertThat(created.version()).isEqualTo(1);
    assertThat(created.lifecycle()).isEqualTo(PolicyLifecycle.DRAFT);
    assertThat(created.revision()).isZero();
    assertThat(created.definition()).isEqualTo(definition(5));
    assertThat(created.createdAt()).isEqualTo(NOW);
    assertThat(created.createdBy()).isEqualTo("local-admin");
    assertThat(created.activatedAt()).isNull();
    assertThat(repository.findVersion(created.policyId(), created.version()).block())
        .isEqualTo(created);
    assertThat(repository.auditCount(created.policyId()).block()).isEqualTo(1);
  }

  @Test
  void activationCommitsOneActiveVersionRevisionAuditAndOutboxRecord() {
    repository.createPolicy("catalog", "Catalog", 1, definition(5), "admin", "create").block();

    ActivationResult activated = repository.activate("catalog", 1, "admin", "activate").block();

    assertThat(activated.policy().lifecycle()).isEqualTo(PolicyLifecycle.ACTIVE);
    assertThat(activated.policy().activatedAt()).isNotNull().isNotEqualTo(NOW);
    assertThat(activated.policy().activatedBy()).isEqualTo("admin");
    assertThat(activated.policySetRevision()).isEqualTo(1);
    assertThat(activated.event().eventType()).isEqualTo("POLICY_ACTIVATED");
    assertThat(repository.loadActiveSet().block().policies())
        .singleElement()
        .extracting(StoredPolicyVersion::version)
        .isEqualTo(1L);
    assertThat(repository.pendingOutboxCount().block()).isEqualTo(1);
    assertThat(repository.auditCount("catalog").block()).isEqualTo(2);
  }

  @Test
  void tokenBucketFieldsRoundTripExactlyAndActivationTimeComesFromPostgres() {
    PolicyDefinition tokenBucket = tokenBucketDefinition();
    StoredPolicyVersion created =
        repository
            .createPolicy("catalog-token", "Catalog token", 1, tokenBucket, "admin", "create-token")
            .block();

    assertThat(created.definition()).isEqualTo(tokenBucket);
    assertThat(repository.findVersion("catalog-token", 1).block().definition())
        .isEqualTo(tokenBucket);

    Instant beforeActivation = Instant.now().minusSeconds(1);
    StoredPolicyVersion activated =
        repository.activate("catalog-token", 1, "admin", "activate-token").block().policy();
    Instant afterActivation = Instant.now().plusSeconds(1);

    assertThat(activated.lifecycle()).isEqualTo(PolicyLifecycle.ACTIVE);
    assertThat(activated.activatedAt()).isBetween(beforeActivation, afterActivation);
    assertThat(activated.activatedAt()).isNotEqualTo(NOW);
    assertThat(repository.loadActiveSet().block().policies())
        .singleElement()
        .extracting(StoredPolicyVersion::definition)
        .isEqualTo(tokenBucket);
  }

  @Test
  void algorithmChangesOnlyThroughNewDraftVersionsAndActivatedDefinitionsStayImmutable() {
    repository.createPolicy("catalog", "Catalog", 1, definition(5), "admin", "create").block();
    repository.activate("catalog", 1, "admin", "activate-fixed").block();

    StoredPolicyVersion tokenDraft =
        repository.createVersion("catalog", 1, 2, "admin", "clone-token").block();
    repository
        .replaceDraft(
            "catalog", 2, tokenDraft.revision(), tokenBucketDefinition(), "admin", "replace-token")
        .block();
    repository.activate("catalog", 2, "admin", "activate-token").block();

    assertThat(repository.findVersion("catalog", 1).block().definition().algorithm().type().name())
        .isEqualTo("FIXED_WINDOW");
    assertThat(repository.findVersion("catalog", 2).block().definition())
        .isEqualTo(tokenBucketDefinition());
    assertThatThrownBy(
            () ->
                repository
                    .replaceDraft("catalog", 2, 1, definition(7), "admin", "mutate-active-token")
                    .block())
        .hasMessageContaining("transition");

    StoredPolicyVersion fixedDraft =
        repository.createVersion("catalog", 2, 3, "admin", "clone-fixed").block();
    repository
        .replaceDraft("catalog", 3, fixedDraft.revision(), definition(7), "admin", "replace-fixed")
        .block();
    repository.activate("catalog", 3, "admin", "activate-fixed-again").block();
    assertThat(repository.findVersion("catalog", 3).block().definition()).isEqualTo(definition(7));
  }

  @Test
  void summariesVersionsAndCountsUseStablePaginationOrdering() {
    repository.createPolicy("z-policy", "Zed", 1, definition(5), "admin", "create-z").block();
    repository.createPolicy("a-policy", "Alpha", 1, definition(5), "admin", "create-a").block();
    repository.createVersion("a-policy", 1, 2, "admin", "clone-a").block();
    repository.activate("a-policy", 2, "admin", "activate-a").block();

    assertThat(repository.countPolicies().block()).isEqualTo(2);
    assertThat(repository.listPolicySummaries(0, 1).block())
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.policyId()).isEqualTo("a-policy");
              assertThat(summary.latestVersion()).isEqualTo(2);
              assertThat(summary.activeVersion()).isEqualTo(2);
            });
    assertThat(repository.findPolicySummary("z-policy").block().activeVersion()).isNull();
    assertThat(repository.countVersions("a-policy").block()).isEqualTo(2);
    assertThat(repository.listVersions("a-policy", 0, 50).block())
        .extracting(StoredPolicyVersion::version)
        .containsExactly(2L, 1L);
  }

  @Test
  void leasedOutboxFailureIsRetryableAndPublishedRowsAreNotClaimedAgain() {
    repository.createPolicy("catalog", "Catalog", 1, definition(5), "admin", "create").block();
    ActivationResult activation = repository.activate("catalog", 1, "admin", "activate").block();

    var firstClaim = repository.claimOutbox("gateway-1", NOW, Duration.ofSeconds(10), 10).block();
    assertThat(firstClaim)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.eventId()).isEqualTo(activation.event().eventId());
              assertThat(event.attemptCount()).isEqualTo(1);
            });
    repository
        .markOutboxFailed(activation.event().eventId(), NOW.plusSeconds(1), "REDIS_UNAVAILABLE")
        .block();
    assertThat(repository.claimOutbox("gateway-2", NOW, Duration.ofSeconds(10), 10).block())
        .isEmpty();
    var secondClaim =
        repository.claimOutbox("gateway-2", NOW.plusSeconds(1), Duration.ofSeconds(10), 10).block();
    assertThat(secondClaim).singleElement().extracting(OutboxEvent::attemptCount).isEqualTo(2);
    repository.markOutboxPublished(activation.event().eventId(), NOW.plusSeconds(1)).block();
    assertThat(
            repository
                .claimOutbox("gateway-3", NOW.plusSeconds(30), Duration.ofSeconds(10), 10)
                .block())
        .isEmpty();
  }

  @Test
  void failedActivationRollsBackRevisionAuditAndOutbox() {
    repository.createPolicy("catalog", "Catalog", 1, definition(5), "admin", "create").block();
    repository.archive("catalog", 1, "admin", "archive").block();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.activate("catalog", 1, "admin", "invalid-activate").block())
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.currentPolicySetRevision().block()).isZero();
    assertThat(repository.pendingOutboxCount().block()).isZero();
    assertThat(repository.auditCount("catalog").block()).isEqualTo(2);
  }

  @Test
  void acceptsNullDescriptionsForDraftCreationAndReplacement() {
    PolicyDefinition withoutDescription = definition(5, null);
    StoredPolicyVersion created =
        repository
            .createPolicy(
                "catalog", "Catalog", 1, withoutDescription, "admin", "create-without-description")
            .block();
    PolicyDefinition replacement = definition(2, null);

    StoredPolicyVersion updated =
        repository
            .replaceDraft("catalog", 1, created.revision(), replacement, "admin", "update")
            .block();

    assertThat(updated.definition().description()).isNull();
    assertThat(updated.definition().limit()).isEqualTo(2);
  }

  @Test
  void rejectsRepositoryBoundaryValuesBeforeDatabaseAccess() {
    PolicyDefinition valid = definition(5);
    assertThatThrownBy(
            () ->
                repository.createPolicy(
                    "é".repeat(65), "Catalog", 1, valid, "admin", "correlation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UTF-8");
    assertThatThrownBy(
            () ->
                repository.createPolicy(
                    "catalog", "n".repeat(129), 1, valid, "admin", "correlation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () -> repository.createPolicy("catalog", "Catalog", 0, valid, "admin", "correlation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(
            () -> repository.createPolicy(null, "Catalog", 1, valid, "admin", "correlation"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> repository.createPolicy("catalog", "Catalog", 1, valid, " ", "correlation"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.archive("catalog", 0, "admin", "correlation"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> repository.replaceDraft("catalog", 1, -1, valid, "admin", "correlation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revision");

    assertThatThrownBy(() -> repository.listPolicySummaries(-1, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.listPolicySummaries(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.listPolicySummaries(0, 101))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> repository.claimOutbox("worker", NOW, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.claimOutbox("worker", NOW, Duration.ofSeconds(-1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.claimOutbox("worker", NOW, Duration.ofSeconds(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.claimOutbox("worker", NOW, Duration.ofSeconds(1), 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PolicyDefinition definition(long limit) {
    return definition(limit, "Catalog requests per client and route");
  }

  private static PolicyDefinition definition(long limit, String description) {
    return new PolicyDefinition(
        description,
        "catalog.items",
        "/proxy/catalog/items",
        List.of("GET"),
        List.of(
            new PolicyIdentityComponent("HEADER", "X-Client-Id"),
            new PolicyIdentityComponent("ROUTE", null)),
        limit,
        Duration.ofSeconds(10),
        FailureMode.FAIL_CLOSED,
        100);
  }

  private static PolicyDefinition tokenBucketDefinition() {
    return new PolicyDefinition(
        "Catalog token requests per client and route",
        "catalog.items",
        "/proxy/catalog/items",
        List.of("GET"),
        List.of(
            new PolicyIdentityComponent("HEADER", "X-Client-Id"),
            new PolicyIdentityComponent("ROUTE", null)),
        new TokenBucketAlgorithmDefinition(10, 4, 2, RefillPeriod.parse("1s"), 3),
        FailureMode.FAIL_CLOSED,
        100);
  }
}
