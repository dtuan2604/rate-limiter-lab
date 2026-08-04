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
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Testcontainers
class PolicyLifecycleTest {

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
    var connectionFactory =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build());
    var template = new R2dbcEntityTemplate(connectionFactory);
    repository =
        new PostgresPolicyRepository(
            template.getDatabaseClient(),
            TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)),
            Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));
  }

  @BeforeEach
  void clearData() {
    repository.deleteAllForTests().block();
  }

  @Test
  void draftCanBeReplacedWithOptimisticRevisionAndCloned() {
    create(1, 5);

    var updated =
        repository.replaceDraft("catalog", 1, 0, definition(7), "admin", "update").block();
    var cloned = repository.createVersion("catalog", 1, 2, "admin", "clone").block();

    assertThat(updated.revision()).isEqualTo(1);
    assertThat(updated.definition().limit()).isEqualTo(7);
    assertThat(cloned.lifecycle()).isEqualTo(PolicyLifecycle.DRAFT);
    assertThat(cloned.version()).isEqualTo(2);
    assertThat(cloned.definition()).isEqualTo(updated.definition());
    assertThatThrownBy(
            () -> repository.replaceDraft("catalog", 1, 0, definition(8), "admin", "stale").block())
        .hasMessageContaining("revision");
  }

  @Test
  void activationSupersedesPriorVersionAndActivatedDefinitionsStayImmutable() {
    create(1, 5);
    repository.activate("catalog", 1, "admin", "activate-1").block();
    repository.createVersion("catalog", 1, 2, "admin", "clone").block();
    repository.activate("catalog", 2, "admin", "activate-2").block();

    assertThat(repository.findVersion("catalog", 1).block().lifecycle())
        .isEqualTo(PolicyLifecycle.DISABLED);
    assertThat(repository.findVersion("catalog", 2).block().lifecycle())
        .isEqualTo(PolicyLifecycle.ACTIVE);
    assertThatThrownBy(
            () ->
                repository
                    .replaceDraft("catalog", 1, 0, definition(6), "admin", "immutable")
                    .block())
        .hasMessageContaining("transition");
  }

  @Test
  void disableArchiveAndExplicitRestoreFollowTransitionTable() {
    create(1, 5);
    repository.archive("catalog", 1, "admin", "archive-draft").block();
    assertThat(repository.restore("catalog", 1, "admin", "restore-draft").block().lifecycle())
        .isEqualTo(PolicyLifecycle.DRAFT);

    repository.activate("catalog", 1, "admin", "activate").block();
    assertThat(repository.disable("catalog", 1, "admin", "disable").block().policy().lifecycle())
        .isEqualTo(PolicyLifecycle.DISABLED);
    repository.archive("catalog", 1, "admin", "archive-disabled").block();
    assertThat(repository.restore("catalog", 1, "admin", "restore-disabled").block().lifecycle())
        .isEqualTo(PolicyLifecycle.DISABLED);
  }

  @Test
  void rejectsEveryInvalidTransition() {
    create(1, 5);
    assertTransitionFails(() -> repository.disable("catalog", 1, "admin", "draft-disable").block());
    repository.archive("catalog", 1, "admin", "archive").block();
    assertTransitionFails(
        () -> repository.activate("catalog", 1, "admin", "archived-activate").block());
    assertTransitionFails(
        () -> repository.disable("catalog", 1, "admin", "archived-disable").block());
    assertTransitionFails(
        () -> repository.archive("catalog", 1, "admin", "double-archive").block());
    assertTransitionFails(
        () ->
            repository
                .replaceDraft("catalog", 1, 0, definition(6), "admin", "archived-update")
                .block());
    repository.restore("catalog", 1, "admin", "restore").block();
    repository.activate("catalog", 1, "admin", "activate").block();
    assertTransitionFails(
        () -> repository.activate("catalog", 1, "admin", "active-activate").block());
    assertTransitionFails(
        () -> repository.archive("catalog", 1, "admin", "active-archive").block());
    assertTransitionFails(
        () -> repository.restore("catalog", 1, "admin", "active-restore").block());
  }

  @Test
  void concurrentActivationOfVersionsTwoAndThreeDeterministicallyLeavesThreeActive() {
    create(1, 5);
    repository.createVersion("catalog", 1, 2, "admin", "clone-2").block();
    repository.createVersion("catalog", 2, 3, "admin", "clone-3").block();

    Mono.whenDelayError(
            repository
                .activate("catalog", 2, "admin", "activate-2")
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ignored -> Mono.empty()),
            repository
                .activate("catalog", 3, "admin", "activate-3")
                .subscribeOn(Schedulers.boundedElastic()))
        .block();

    assertThat(repository.loadActiveSet().block().policies())
        .singleElement()
        .satisfies(
            policy -> {
              assertThat(policy.version()).isEqualTo(3);
              assertThat(policy.lifecycle()).isEqualTo(PolicyLifecycle.ACTIVE);
            });
    assertThat(repository.highestActivatedVersion("catalog").block()).isEqualTo(3);
  }

  @Test
  void aLowerVersionCannotBeActivatedAfterAnewerVersion() {
    create(1, 5);
    repository.createVersion("catalog", 1, 2, "admin", "clone").block();
    repository.activate("catalog", 2, "admin", "activate-2").block();

    assertTransitionFails(() -> repository.activate("catalog", 1, "admin", "activate-1").block());
    assertThat(repository.loadActiveSet().block().policies())
        .singleElement()
        .extracting(policy -> policy.version())
        .isEqualTo(2L);
  }

  private static void create(long version, long limit) {
    repository
        .createPolicy("catalog", "Catalog", version, definition(limit), "admin", "create")
        .block();
  }

  private static PolicyDefinition definition(long limit) {
    return new PolicyDefinition(
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
        100);
  }

  private static void assertTransitionFails(Runnable operation) {
    assertThatThrownBy(operation::run).isInstanceOf(IllegalStateException.class);
  }
}
