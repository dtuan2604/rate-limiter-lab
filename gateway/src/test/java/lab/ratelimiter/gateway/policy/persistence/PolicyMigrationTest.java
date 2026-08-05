package lab.ratelimiter.gateway.policy.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PolicyMigrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.6-alpine"));

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @Test
  void emptyDatabaseMigratesToTheCompletePolicyControlPlaneSchema() throws SQLException {
    Set<String> expected =
        Set.of(
            "fixed_window_configurations",
            "token_bucket_configurations",
            "policies",
            "policy_audit",
            "policy_event_outbox",
            "policy_set_state",
            "policy_version_identity_components",
            "policy_version_methods",
            "policy_versions");

    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name LIKE 'polic%' "
                    + "OR table_schema = 'public' AND table_name IN "
                    + "('fixed_window_configurations', 'token_bucket_configurations')")) {
      Set<String> actual = new TreeSet<>();
      while (result.next()) {
        actual.add(result.getString(1));
      }
      assertThat(actual).containsAll(expected);
    }
  }

  @Test
  void databaseRejectsDuplicateVersionsAndMultipleActiveVersions() throws SQLException {
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      insertPolicy(statement, "migration-constraints");
      insertVersion(statement, "migration-constraints", 1, "ACTIVE", true);

      assertThatThrownBy(() -> insertVersion(statement, "migration-constraints", 1, "DRAFT", false))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> insertVersion(statement, "migration-constraints", 2, "ACTIVE", true))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void activatedDefinitionCannotBeMutated() throws SQLException {
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      insertPolicy(statement, "migration-immutable");
      insertVersion(statement, "migration-immutable", 1, "DISABLED", true);

      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE policy_versions SET route_path = '/proxy/catalog/changed' "
                          + "WHERE policy_id = 'migration-immutable' AND version = 1"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("activated policy definition is immutable");
    }
  }

  @Test
  void activatedSubtypeConfigurationCannotBeMutated() throws SQLException {
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      insertPolicy(statement, "migration-child-immutable");
      insertVersion(statement, "migration-child-immutable", 1, "DRAFT", false);
      statement.executeUpdate(
          "INSERT INTO fixed_window_configurations(policy_id, version, request_limit, "
              + "window_milliseconds) VALUES ('migration-child-immutable', 1, 5, 10000)");
      statement.executeUpdate(
          "UPDATE policy_versions SET lifecycle_status = 'ACTIVE', "
              + "activated_at = CURRENT_TIMESTAMP, activated_by = 'test' "
              + "WHERE policy_id = 'migration-child-immutable' AND version = 1");

      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE fixed_window_configurations SET request_limit = 6 "
                          + "WHERE policy_id = 'migration-child-immutable' AND version = 1"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("activated policy child definition is immutable");
    }
  }

  @Test
  void tokenBucketSubtypeConstraintsDiscriminatorAndImmutabilityAreEnforced() throws SQLException {
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      insertPolicy(statement, "migration-token");
      insertTokenVersion(statement, "migration-token", 1, "DRAFT", false);
      statement.executeUpdate(
          "INSERT INTO token_bucket_configurations(policy_id, version, algorithm_type, capacity, "
              + "initial_tokens, refill_tokens, refill_period_amount, refill_period_unit, "
              + "request_cost) VALUES ('migration-token', 1, 'TOKEN_BUCKET', 10, 4, 2, 1, 's', 3)");

      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "INSERT INTO fixed_window_configurations(policy_id, version, algorithm_type, "
                          + "request_limit, window_milliseconds) VALUES "
                          + "('migration-token', 1, 'FIXED_WINDOW', 5, 1000)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE token_bucket_configurations SET request_cost = 11 "
                          + "WHERE policy_id = 'migration-token' AND version = 1"))
          .isInstanceOf(SQLException.class);

      statement.executeUpdate(
          "UPDATE policy_versions SET lifecycle_status = 'ACTIVE', "
              + "activated_at = CURRENT_TIMESTAMP, activated_by = 'test' "
              + "WHERE policy_id = 'migration-token' AND version = 1");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE token_bucket_configurations SET capacity = 11 "
                          + "WHERE policy_id = 'migration-token' AND version = 1"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("activated policy child definition is immutable");
    }
  }

  @Test
  void validPhaseFourDatabaseUpgradesToV2WithoutChangingFixedWindowData() throws SQLException {
    String schema = "phase4_upgrade";
    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE SCHEMA " + schema);
    }
    Flyway phaseOne =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .target("1")
            .load();
    assertThat(phaseOne.migrate().migrationsExecuted).isEqualTo(1);

    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema);
      insertPolicy(statement, "phase4-fixed");
      insertVersion(statement, "phase4-fixed", 1, "DRAFT", false);
      statement.executeUpdate(
          "INSERT INTO fixed_window_configurations(policy_id, version, request_limit, "
              + "window_milliseconds) VALUES ('phase4-fixed', 1, 5, 10000)");
      statement.executeUpdate(
          "UPDATE policy_versions SET lifecycle_status = 'ACTIVE', "
              + "activated_at = CURRENT_TIMESTAMP, activated_by = 'test' "
              + "WHERE policy_id = 'phase4-fixed' AND version = 1");
    }

    Flyway upgraded =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .load();
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);

    try (Connection connection = POSTGRES.createConnection("");
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema);
      try (ResultSet result =
          statement.executeQuery(
              "SELECT pv.algorithm_type, fw.algorithm_type, fw.request_limit, "
                  + "fw.window_milliseconds FROM policy_versions pv "
                  + "JOIN fixed_window_configurations fw USING (policy_id, version) "
                  + "WHERE pv.policy_id = 'phase4-fixed'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("FIXED_WINDOW");
        assertThat(result.getString(2)).isEqualTo("FIXED_WINDOW");
        assertThat(result.getLong(3)).isEqualTo(5);
        assertThat(result.getLong(4)).isEqualTo(10_000);
      }
    }
  }

  private static void insertPolicy(Statement statement, String id) throws SQLException {
    statement.executeUpdate(
        "INSERT INTO policies(policy_id, name, created_by) VALUES ('"
            + id
            + "', 'Migration test', 'test')");
  }

  private static void insertVersion(
      Statement statement, String id, long version, String status, boolean activated)
      throws SQLException {
    String activatedFields = activated ? ", activated_at, activated_by" : "";
    String activatedValues = activated ? ", CURRENT_TIMESTAMP, 'test'" : "";
    statement.executeUpdate(
        "INSERT INTO policy_versions("
            + "policy_id, version, lifecycle_status, description, route_id, route_path, "
            + "algorithm_type, failure_mode, priority, created_by"
            + activatedFields
            + ") VALUES ('"
            + id
            + "', "
            + version
            + ", '"
            + status
            + "', NULL, 'catalog.items', '/proxy/catalog/items', "
            + "'FIXED_WINDOW', 'FAIL_CLOSED', 100, 'test'"
            + activatedValues
            + ")");
  }

  private static void insertTokenVersion(
      Statement statement, String id, long version, String status, boolean activated)
      throws SQLException {
    String activatedFields = activated ? ", activated_at, activated_by" : "";
    String activatedValues = activated ? ", CURRENT_TIMESTAMP, 'test'" : "";
    statement.executeUpdate(
        "INSERT INTO policy_versions("
            + "policy_id, version, lifecycle_status, description, route_id, route_path, "
            + "algorithm_type, failure_mode, priority, created_by"
            + activatedFields
            + ") VALUES ('"
            + id
            + "', "
            + version
            + ", '"
            + status
            + "', NULL, 'catalog.items', '/proxy/catalog/items', "
            + "'TOKEN_BUCKET', 'FAIL_CLOSED', 100, 'test'"
            + activatedValues
            + ")");
  }
}
