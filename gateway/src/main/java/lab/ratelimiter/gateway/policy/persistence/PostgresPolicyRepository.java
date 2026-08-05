package lab.ratelimiter.gateway.policy.persistence;

import io.r2dbc.spi.Row;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.policy.control.ActivationResult;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.control.FixedWindowAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyEvent;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
import lab.ratelimiter.gateway.policy.control.RefillPeriod;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class PostgresPolicyRepository {

  private final DatabaseClient database;
  private final TransactionalOperator transactions;
  private final Clock clock;

  public PostgresPolicyRepository(
      DatabaseClient database, TransactionalOperator transactions, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Mono<StoredPolicyVersion> createPolicy(
      String policyId,
      String name,
      long version,
      PolicyDefinition definition,
      String actor,
      String correlationId) {
    validateStable(policyId, name, version, actor, correlationId);
    Objects.requireNonNull(definition, "definition");
    Instant now = clock.instant();
    Mono<StoredPolicyVersion> operation =
        insertPolicy(policyId, name, actor, now)
            .then(insertVersion(policyId, version, definition, actor, now))
            .then(insertChildren(policyId, version, definition))
            .then(
                insertAudit(
                    policyId,
                    version,
                    "CREATED",
                    null,
                    PolicyLifecycle.DRAFT,
                    actor,
                    correlationId,
                    now))
            .then(findVersion(policyId, version));
    return transactions.transactional(operation);
  }

  public Mono<StoredPolicyVersion> findVersion(String policyId, long version) {
    return database
        .sql(
            """
            SELECT p.name, pv.*, fw.request_limit, fw.window_milliseconds,
              fw.algorithm_type AS fixed_algorithm_type,
              tb.capacity, tb.initial_tokens, tb.refill_tokens,
              tb.refill_period_amount, tb.refill_period_unit, tb.request_cost,
              tb.algorithm_type AS token_algorithm_type,
              (SELECT count(*) FROM policy_version_methods m
                 WHERE m.policy_id = pv.policy_id AND m.version = pv.version) AS method_count,
              (SELECT count(*) FROM policy_version_identity_components i
                 WHERE i.policy_id = pv.policy_id AND i.version = pv.version) AS identity_count
            FROM policy_versions pv
            JOIN policies p ON p.policy_id = pv.policy_id
            LEFT JOIN fixed_window_configurations fw
              ON fw.policy_id = pv.policy_id AND fw.version = pv.version
            LEFT JOIN token_bucket_configurations tb
              ON tb.policy_id = pv.policy_id AND tb.version = pv.version
            WHERE pv.policy_id = :policyId AND pv.version = :version
            """)
        .bind("policyId", policyId)
        .bind("version", version)
        .map((row, metadata) -> mapVersion(row))
        .one();
  }

  public Mono<List<PolicySummary>> listPolicySummaries(int page, int size) {
    validatePage(page, size);
    return database
        .sql(
            """
            SELECT p.policy_id, p.name, max(pv.version) AS latest_version,
              max(pv.version) FILTER (WHERE pv.lifecycle_status = 'ACTIVE') AS active_version
            FROM policies p JOIN policy_versions pv ON pv.policy_id = p.policy_id
            GROUP BY p.policy_id, p.name
            ORDER BY p.policy_id
            LIMIT :size OFFSET :offset
            """)
        .bind("size", size)
        .bind("offset", Math.multiplyExact(page, size))
        .map(
            (row, metadata) ->
                new PolicySummary(
                    required(row, "policy_id", String.class),
                    required(row, "name", String.class),
                    required(row, "latest_version", Long.class),
                    row.get("active_version", Long.class)))
        .all()
        .collectList();
  }

  public Mono<PolicySummary> findPolicySummary(String policyId) {
    return database
        .sql(
            """
            SELECT p.policy_id, p.name, max(pv.version) AS latest_version,
              max(pv.version) FILTER (WHERE pv.lifecycle_status = 'ACTIVE') AS active_version
            FROM policies p JOIN policy_versions pv ON pv.policy_id = p.policy_id
            WHERE p.policy_id = :policyId
            GROUP BY p.policy_id, p.name
            """)
        .bind("policyId", policyId)
        .map(
            (row, metadata) ->
                new PolicySummary(
                    required(row, "policy_id", String.class),
                    required(row, "name", String.class),
                    required(row, "latest_version", Long.class),
                    row.get("active_version", Long.class)))
        .one();
  }

  public Mono<List<StoredPolicyVersion>> listVersions(String policyId, int page, int size) {
    validatePage(page, size);
    return database
        .sql(
            "SELECT version FROM policy_versions WHERE policy_id = :policyId "
                + "ORDER BY version DESC LIMIT :size OFFSET :offset")
        .bind("policyId", policyId)
        .bind("size", size)
        .bind("offset", Math.multiplyExact(page, size))
        .map((row, metadata) -> required(row, "version", Long.class))
        .all()
        .concatMap(version -> findVersion(policyId, version))
        .collectList();
  }

  public Mono<Long> countPolicies() {
    return count("SELECT count(*) AS count FROM policies");
  }

  public Mono<Long> countVersions(String policyId) {
    return database
        .sql("SELECT count(*) AS count FROM policy_versions WHERE policy_id = :policyId")
        .bind("policyId", policyId)
        .map((row, metadata) -> required(row, "count", Long.class))
        .one();
  }

  public Mono<StoredPolicyVersion> createVersion(
      String policyId, long sourceVersion, long newVersion, String actor, String correlationId) {
    validateMutation(policyId, newVersion, actor, correlationId);
    Instant now = clock.instant();
    Mono<StoredPolicyVersion> operation =
        lockPolicy(policyId)
            .then(findVersion(policyId, sourceVersion))
            .switchIfEmpty(
                Mono.error(new IllegalArgumentException("source policy version not found")))
            .flatMap(
                source ->
                    insertVersion(policyId, newVersion, source.definition(), actor, now)
                        .then(insertChildren(policyId, newVersion, source.definition()))
                        .then(
                            insertAudit(
                                policyId,
                                newVersion,
                                "VERSION_CREATED",
                                null,
                                PolicyLifecycle.DRAFT,
                                actor,
                                correlationId,
                                now)))
            .then(findVersion(policyId, newVersion));
    return transactions.transactional(operation);
  }

  public Mono<StoredPolicyVersion> replaceDraft(
      String policyId,
      long version,
      long expectedRevision,
      PolicyDefinition definition,
      String actor,
      String correlationId) {
    validateMutation(policyId, version, actor, correlationId);
    Objects.requireNonNull(definition, "definition");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("revision must be nonnegative");
    }
    Instant now = clock.instant();
    Mono<StoredPolicyVersion> operation =
        lockPolicy(policyId)
            .then(findVersion(policyId, version))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("policy version not found")))
            .flatMap(
                current -> {
                  requireLifecycle(current, PolicyLifecycle.DRAFT);
                  if (current.revision() != expectedRevision) {
                    return Mono.error(new IllegalStateException("policy revision does not match"));
                  }
                  return updateDraftDefinition(
                          policyId, version, expectedRevision, definition, actor, now)
                      .flatMap(
                          updated -> {
                            if (updated != 1) {
                              return Mono.error(
                                  new IllegalStateException("policy revision does not match"));
                            }
                            return replaceChildren(policyId, version, definition)
                                .then(
                                    insertAudit(
                                        policyId,
                                        version,
                                        "UPDATED",
                                        PolicyLifecycle.DRAFT,
                                        PolicyLifecycle.DRAFT,
                                        actor,
                                        correlationId,
                                        now));
                          });
                })
            .then(findVersion(policyId, version));
    return transactions.transactional(operation);
  }

  public Mono<ActivationResult> activate(
      String policyId, long version, String actor, String correlationId) {
    Instant now = clock.instant();
    UUID eventId = UUID.randomUUID();
    Mono<ActivationResult> operation =
        lockPolicy(policyId)
            .flatMap(
                highest ->
                    findVersion(policyId, version)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("policy not found")))
                        .flatMap(target -> validateActivation(target, highest)))
            .flatMap(
                target ->
                    disableCurrent(policyId, version, actor, now)
                        .then(activateTarget(policyId, version, actor, now))
                        .then(updateHighestVersion(policyId, version))
                        .then(incrementPolicySetRevision(now))
                        .flatMap(
                            revision -> {
                              PolicyEvent event =
                                  new PolicyEvent(
                                      1,
                                      "POLICY_ACTIVATED",
                                      policyId,
                                      version,
                                      revision,
                                      eventId,
                                      now);
                              return insertAudit(
                                      policyId,
                                      version,
                                      "ACTIVATED",
                                      target.lifecycle(),
                                      PolicyLifecycle.ACTIVE,
                                      actor,
                                      correlationId,
                                      now)
                                  .then(insertOutbox(event))
                                  .then(findVersion(policyId, version))
                                  .map(policy -> new ActivationResult(policy, revision, event));
                            }));
    return transactions.transactional(operation);
  }

  public Mono<ActivationResult> disable(
      String policyId, long version, String actor, String correlationId) {
    validateMutation(policyId, version, actor, correlationId);
    Instant now = clock.instant();
    UUID eventId = UUID.randomUUID();
    Mono<ActivationResult> operation =
        lockPolicy(policyId)
            .then(findVersion(policyId, version))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("policy version not found")))
            .flatMap(
                current -> {
                  requireLifecycle(current, PolicyLifecycle.ACTIVE);
                  return updateLifecycle(
                          policyId,
                          version,
                          PolicyLifecycle.DISABLED,
                          actor,
                          now,
                          "disabled_at",
                          "disabled_by")
                      .then(incrementPolicySetRevision(now))
                      .flatMap(
                          revision -> {
                            PolicyEvent event =
                                new PolicyEvent(
                                    1,
                                    "POLICY_DISABLED",
                                    policyId,
                                    version,
                                    revision,
                                    eventId,
                                    now);
                            return insertAudit(
                                    policyId,
                                    version,
                                    "DISABLED",
                                    PolicyLifecycle.ACTIVE,
                                    PolicyLifecycle.DISABLED,
                                    actor,
                                    correlationId,
                                    now)
                                .then(insertOutbox(event))
                                .then(findVersion(policyId, version))
                                .map(policy -> new ActivationResult(policy, revision, event));
                          });
                });
    return transactions.transactional(operation);
  }

  public Mono<StoredPolicyVersion> archive(
      String policyId, long version, String actor, String correlationId) {
    return transitionWithoutEvent(
        policyId,
        version,
        actor,
        correlationId,
        "ARCHIVED",
        current ->
            current.lifecycle() == PolicyLifecycle.DRAFT
                || current.lifecycle() == PolicyLifecycle.DISABLED,
        PolicyLifecycle.ARCHIVED,
        "archived_at",
        "archived_by");
  }

  public Mono<StoredPolicyVersion> restore(
      String policyId, long version, String actor, String correlationId) {
    validateMutation(policyId, version, actor, correlationId);
    Instant now = clock.instant();
    Mono<StoredPolicyVersion> operation =
        lockPolicy(policyId)
            .then(findVersion(policyId, version))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("policy version not found")))
            .flatMap(
                current -> {
                  requireLifecycle(current, PolicyLifecycle.ARCHIVED);
                  PolicyLifecycle restored =
                      current.activatedAt() == null
                          ? PolicyLifecycle.DRAFT
                          : PolicyLifecycle.DISABLED;
                  return database
                      .sql(
                          "UPDATE policy_versions SET lifecycle_status = :status, archived_at = NULL, "
                              + "archived_by = NULL, updated_at = :now, updated_by = :actor "
                              + "WHERE policy_id = :id AND version = :version")
                      .bind("status", restored.name())
                      .bind("now", offset(now))
                      .bind("actor", actor)
                      .bind("id", policyId)
                      .bind("version", version)
                      .fetch()
                      .rowsUpdated()
                      .then(
                          insertAudit(
                              policyId,
                              version,
                              "RESTORED",
                              PolicyLifecycle.ARCHIVED,
                              restored,
                              actor,
                              correlationId,
                              now));
                })
            .then(findVersion(policyId, version));
    return transactions.transactional(operation);
  }

  public Mono<ActivePolicySet> loadActiveSet() {
    return database
        .sql(
            """
            SELECT state.revision AS policy_set_revision, p.name, pv.*,
              fw.request_limit, fw.window_milliseconds,
              fw.algorithm_type AS fixed_algorithm_type,
              tb.capacity, tb.initial_tokens, tb.refill_tokens,
              tb.refill_period_amount, tb.refill_period_unit, tb.request_cost,
              tb.algorithm_type AS token_algorithm_type,
              (SELECT count(*) FROM policy_version_methods m
                WHERE m.policy_id = pv.policy_id AND m.version = pv.version) AS method_count,
              (SELECT count(*) FROM policy_version_identity_components i
                WHERE i.policy_id = pv.policy_id AND i.version = pv.version) AS identity_count
            FROM policy_set_state state
            LEFT JOIN policy_versions pv ON pv.lifecycle_status = 'ACTIVE'
            LEFT JOIN policies p ON p.policy_id = pv.policy_id
            LEFT JOIN fixed_window_configurations fw
              ON fw.policy_id = pv.policy_id AND fw.version = pv.version
            LEFT JOIN token_bucket_configurations tb
              ON tb.policy_id = pv.policy_id AND tb.version = pv.version
            WHERE state.singleton_id = 1
            ORDER BY pv.priority DESC, pv.policy_id ASC
            """)
        .map(
            (row, metadata) ->
                new ActiveRow(
                    required(row, "policy_set_revision", Long.class),
                    row.get("policy_id", String.class) == null ? null : mapVersion(row)))
        .all()
        .collectList()
        .map(
            rows -> {
              if (rows.isEmpty()) {
                throw new IllegalStateException("policy set state is missing");
              }
              long revision = rows.getFirst().revision();
              List<StoredPolicyVersion> policies =
                  rows.stream().map(ActiveRow::policy).filter(Objects::nonNull).toList();
              return new ActivePolicySet(revision, policies);
            });
  }

  public Mono<Long> currentPolicySetRevision() {
    return database
        .sql("SELECT revision FROM policy_set_state WHERE singleton_id = 1")
        .map((row, metadata) -> required(row, "revision", Long.class))
        .one();
  }

  public Mono<Long> highestActivatedVersion(String policyId) {
    return database
        .sql("SELECT highest_activated_version FROM policies WHERE policy_id = :policyId")
        .bind("policyId", policyId)
        .map(
            (row, metadata) -> {
              Long version = row.get("highest_activated_version", Long.class);
              return version == null ? 0L : version;
            })
        .one();
  }

  public Mono<Long> auditCount(String policyId) {
    return database
        .sql("SELECT count(*) AS count FROM policy_audit WHERE policy_id = :policyId")
        .bind("policyId", policyId)
        .map((row, metadata) -> required(row, "count", Long.class))
        .one();
  }

  public Mono<Long> pendingOutboxCount() {
    return database
        .sql(
            "SELECT count(*) AS count FROM policy_event_outbox "
                + "WHERE publication_status = 'PENDING'")
        .map((row, metadata) -> required(row, "count", Long.class))
        .one();
  }

  public Mono<List<OutboxEvent>> claimOutbox(
      String workerId, Instant now, Duration leaseDuration, int limit) {
    requireText(workerId, "worker ID");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseDuration.isZero() || leaseDuration.isNegative() || limit < 1 || limit > 100) {
      throw new IllegalArgumentException("outbox lease and limit must be bounded and positive");
    }
    return database
        .sql(
            """
            WITH candidates AS (
              SELECT event_id FROM policy_event_outbox
              WHERE next_attempt_at <= :now
                AND (publication_status = 'PENDING'
                  OR (publication_status = 'IN_FLIGHT' AND lease_until < :now))
              ORDER BY occurred_at, event_id
              FOR UPDATE SKIP LOCKED
              LIMIT :limit
            )
            UPDATE policy_event_outbox event
              SET publication_status = 'IN_FLIGHT', lease_owner = :worker,
                lease_until = :leaseUntil, attempt_count = attempt_count + 1
            FROM candidates
            WHERE event.event_id = candidates.event_id
            RETURNING event.*
            """)
        .bind("now", offset(now))
        .bind("limit", limit)
        .bind("worker", workerId)
        .bind("leaseUntil", offset(now.plus(leaseDuration)))
        .map((row, metadata) -> mapOutbox(row))
        .all()
        .collectList();
  }

  public Mono<Void> markOutboxPublished(UUID eventId, Instant publishedAt) {
    return database
        .sql(
            "UPDATE policy_event_outbox SET publication_status = 'PUBLISHED', "
                + "published_at = :publishedAt, lease_owner = NULL, lease_until = NULL, "
                + "last_failure = NULL WHERE event_id = :eventId")
        .bind("publishedAt", offset(publishedAt))
        .bind("eventId", eventId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  public Mono<Void> markOutboxFailed(UUID eventId, Instant nextAttemptAt, String failureCategory) {
    requireText(failureCategory, "failure category");
    return database
        .sql(
            "UPDATE policy_event_outbox SET publication_status = 'PENDING', "
                + "next_attempt_at = :nextAttemptAt, lease_owner = NULL, lease_until = NULL, "
                + "last_failure = :failure WHERE event_id = :eventId")
        .bind("nextAttemptAt", offset(nextAttemptAt))
        .bind("failure", failureCategory)
        .bind("eventId", eventId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  public Mono<Void> deleteAllForTests() {
    return database
        .sql(
            "TRUNCATE policy_event_outbox, policy_audit, token_bucket_configurations, "
                + "fixed_window_configurations, "
                + "policy_version_identity_components, policy_version_methods, "
                + "policy_versions, policies RESTART IDENTITY CASCADE")
        .fetch()
        .rowsUpdated()
        .then(
            database
                .sql(
                    "UPDATE policy_set_state SET revision = 0, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE singleton_id = 1")
                .fetch()
                .rowsUpdated())
        .then();
  }

  private Mono<Void> insertPolicy(String id, String name, String actor, Instant now) {
    return database
        .sql(
            "INSERT INTO policies(policy_id, name, created_at, created_by) "
                + "VALUES (:id, :name, :now, :actor)")
        .bind("id", id)
        .bind("name", name)
        .bind("now", offset(now))
        .bind("actor", actor)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> insertVersion(
      String id, long version, PolicyDefinition definition, String actor, Instant now) {
    DatabaseClient.GenericExecuteSpec statement =
        database
            .sql(
                """
                INSERT INTO policy_versions(
                  policy_id, version, lifecycle_status, revision, description,
                  route_id, route_path, algorithm_type, failure_mode, priority,
                  created_at, created_by, updated_at, updated_by)
                VALUES (:id, :version, 'DRAFT', 0, :description, :routeId, :path,
                  :algorithmType, :failureMode, :priority, :now, :actor, :now, :actor)
                """)
            .bind("id", id)
            .bind("version", version)
            .bind("routeId", definition.routeId())
            .bind("path", definition.path())
            .bind("algorithmType", definition.algorithm().type().name())
            .bind("failureMode", definition.failureMode().name())
            .bind("priority", definition.priority())
            .bind("now", offset(now))
            .bind("actor", actor);
    statement =
        definition.description() == null
            ? statement.bindNull("description", String.class)
            : statement.bind("description", definition.description());
    return statement.fetch().rowsUpdated().then();
  }

  private Mono<Void> insertChildren(String id, long version, PolicyDefinition definition) {
    Mono<Void> method =
        database
            .sql(
                "INSERT INTO policy_version_methods(policy_id, version, method) "
                    + "VALUES (:id, :version, 'GET')")
            .bind("id", id)
            .bind("version", version)
            .fetch()
            .rowsUpdated()
            .then();
    Flux<Void> identities =
        Flux.fromIterable(definition.identityComponents())
            .index()
            .concatMap(
                indexed -> {
                  PolicyIdentityComponent component = indexed.getT2();
                  DatabaseClient.GenericExecuteSpec statement =
                      database
                          .sql(
                              "INSERT INTO policy_version_identity_components("
                                  + "policy_id, version, component_order, component_type, header_name) "
                                  + "VALUES (:id, :version, :position, :type, :name)")
                          .bind("id", id)
                          .bind("version", version)
                          .bind("position", indexed.getT1().shortValue())
                          .bind("type", component.type());
                  statement =
                      component.name() == null
                          ? statement.bindNull("name", String.class)
                          : statement.bind("name", component.name());
                  return statement.fetch().rowsUpdated().then();
                });
    Mono<Void> configuration = insertConfiguration(id, version, definition);
    return method.thenMany(identities).then().then(configuration);
  }

  private Mono<Void> insertConfiguration(String id, long version, PolicyDefinition definition) {
    if (definition.algorithm() instanceof FixedWindowAlgorithmDefinition fixedWindow) {
      return database
          .sql(
              "INSERT INTO fixed_window_configurations("
                  + "policy_id, version, algorithm_type, request_limit, window_milliseconds) "
                  + "VALUES (:id, :version, 'FIXED_WINDOW', :limit, :window)")
          .bind("id", id)
          .bind("version", version)
          .bind("limit", fixedWindow.limit())
          .bind("window", fixedWindow.window().toMillis())
          .fetch()
          .rowsUpdated()
          .then();
    }
    if (definition.algorithm() instanceof TokenBucketAlgorithmDefinition tokenBucket) {
      return database
          .sql(
              "INSERT INTO token_bucket_configurations("
                  + "policy_id, version, algorithm_type, capacity, initial_tokens, refill_tokens, "
                  + "refill_period_amount, refill_period_unit, request_cost) "
                  + "VALUES (:id, :version, 'TOKEN_BUCKET', :capacity, :initialTokens, "
                  + ":refillTokens, :refillPeriodAmount, :refillPeriodUnit, :requestCost)")
          .bind("id", id)
          .bind("version", version)
          .bind("capacity", tokenBucket.capacity())
          .bind("initialTokens", tokenBucket.initialTokens())
          .bind("refillTokens", tokenBucket.refillTokens())
          .bind("refillPeriodAmount", tokenBucket.refillPeriod().amount())
          .bind("refillPeriodUnit", tokenBucket.refillPeriod().unit().symbol())
          .bind("requestCost", tokenBucket.requestCost())
          .fetch()
          .rowsUpdated()
          .then();
    }
    return Mono.error(new IllegalArgumentException("unsupported policy algorithm"));
  }

  private Mono<Void> replaceChildren(String id, long version, PolicyDefinition definition) {
    Mono<Void> deleteConfiguration =
        database
            .sql(
                "DELETE FROM fixed_window_configurations "
                    + "WHERE policy_id = :id AND version = :version")
            .bind("id", id)
            .bind("version", version)
            .fetch()
            .rowsUpdated()
            .then(
                database
                    .sql(
                        "DELETE FROM token_bucket_configurations "
                            + "WHERE policy_id = :id AND version = :version")
                    .bind("id", id)
                    .bind("version", version)
                    .fetch()
                    .rowsUpdated()
                    .then());
    Mono<Void> deleteIdentity =
        database
            .sql(
                "DELETE FROM policy_version_identity_components "
                    + "WHERE policy_id = :id AND version = :version")
            .bind("id", id)
            .bind("version", version)
            .fetch()
            .rowsUpdated()
            .then();
    Mono<Void> deleteMethods =
        database
            .sql("DELETE FROM policy_version_methods WHERE policy_id = :id AND version = :version")
            .bind("id", id)
            .bind("version", version)
            .fetch()
            .rowsUpdated()
            .then();
    return deleteConfiguration
        .then(deleteIdentity)
        .then(deleteMethods)
        .then(insertChildren(id, version, definition));
  }

  private Mono<Long> updateDraftDefinition(
      String id,
      long version,
      long expectedRevision,
      PolicyDefinition definition,
      String actor,
      Instant now) {
    DatabaseClient.GenericExecuteSpec statement =
        database
            .sql(
                """
                UPDATE policy_versions SET description = :description, route_id = :routeId,
                  route_path = :path, algorithm_type = :algorithmType,
                  failure_mode = :failureMode, priority = :priority,
                  revision = revision + 1, updated_at = :now, updated_by = :actor
                WHERE policy_id = :id AND version = :version AND lifecycle_status = 'DRAFT'
                  AND revision = :expectedRevision
                """)
            .bind("routeId", definition.routeId())
            .bind("path", definition.path())
            .bind("algorithmType", definition.algorithm().type().name())
            .bind("failureMode", definition.failureMode().name())
            .bind("priority", definition.priority())
            .bind("now", offset(now))
            .bind("actor", actor)
            .bind("id", id)
            .bind("version", version)
            .bind("expectedRevision", expectedRevision);
    statement =
        definition.description() == null
            ? statement.bindNull("description", String.class)
            : statement.bind("description", definition.description());
    return statement.fetch().rowsUpdated();
  }

  private Mono<Long> lockPolicy(String policyId) {
    return database
        .sql(
            "SELECT highest_activated_version FROM policies "
                + "WHERE policy_id = :policyId FOR UPDATE")
        .bind("policyId", policyId)
        .map(
            (row, metadata) -> {
              Long value = row.get("highest_activated_version", Long.class);
              return value == null ? 0L : value;
            })
        .one()
        .switchIfEmpty(Mono.error(new IllegalArgumentException("policy not found")));
  }

  private static Mono<StoredPolicyVersion> validateActivation(
      StoredPolicyVersion target, long highest) {
    boolean draft = target.lifecycle() == PolicyLifecycle.DRAFT && target.version() >= highest;
    boolean disabled =
        target.lifecycle() == PolicyLifecycle.DISABLED && target.version() == highest;
    if (!draft && !disabled) {
      return Mono.error(new IllegalStateException("invalid policy activation transition"));
    }
    return Mono.just(target);
  }

  private Mono<Void> updateLifecycle(
      String id,
      long version,
      PolicyLifecycle lifecycle,
      String actor,
      Instant now,
      String timestampColumn,
      String actorColumn) {
    String sql =
        "UPDATE policy_versions SET lifecycle_status = :status, "
            + timestampColumn
            + " = :now, "
            + actorColumn
            + " = :actor, updated_at = :now, updated_by = :actor "
            + "WHERE policy_id = :id AND version = :version";
    return database
        .sql(sql)
        .bind("status", lifecycle.name())
        .bind("now", offset(now))
        .bind("actor", actor)
        .bind("id", id)
        .bind("version", version)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<StoredPolicyVersion> transitionWithoutEvent(
      String policyId,
      long version,
      String actor,
      String correlationId,
      String action,
      java.util.function.Predicate<StoredPolicyVersion> allowed,
      PolicyLifecycle resulting,
      String timestampColumn,
      String actorColumn) {
    validateMutation(policyId, version, actor, correlationId);
    Instant now = clock.instant();
    Mono<StoredPolicyVersion> operation =
        lockPolicy(policyId)
            .then(findVersion(policyId, version))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("policy version not found")))
            .flatMap(
                current -> {
                  if (!allowed.test(current)) {
                    return Mono.error(new IllegalStateException("invalid policy transition"));
                  }
                  return updateLifecycle(
                          policyId, version, resulting, actor, now, timestampColumn, actorColumn)
                      .then(
                          insertAudit(
                              policyId,
                              version,
                              action,
                              current.lifecycle(),
                              resulting,
                              actor,
                              correlationId,
                              now));
                })
            .then(findVersion(policyId, version));
    return transactions.transactional(operation);
  }

  private Mono<Void> disableCurrent(String id, long target, String actor, Instant now) {
    return database
        .sql(
            "UPDATE policy_versions SET lifecycle_status = 'DISABLED', "
                + "disabled_at = :now, disabled_by = :actor, updated_at = :now, updated_by = :actor "
                + "WHERE policy_id = :id AND lifecycle_status = 'ACTIVE' AND version <> :target")
        .bind("id", id)
        .bind("target", target)
        .bind("actor", actor)
        .bind("now", offset(now))
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> activateTarget(String id, long version, String actor, Instant now) {
    return database
        .sql(
            "UPDATE policy_versions SET lifecycle_status = 'ACTIVE', "
                + "activated_at = COALESCE(activated_at, CURRENT_TIMESTAMP), "
                + "activated_by = COALESCE(activated_by, :actor), "
                + "disabled_at = NULL, disabled_by = NULL, updated_at = :now, updated_by = :actor "
                + "WHERE policy_id = :id AND version = :version")
        .bind("id", id)
        .bind("version", version)
        .bind("actor", actor)
        .bind("now", offset(now))
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> updateHighestVersion(String id, long version) {
    return database
        .sql(
            "UPDATE policies SET highest_activated_version = "
                + "GREATEST(COALESCE(highest_activated_version, 0), :version) "
                + "WHERE policy_id = :id")
        .bind("id", id)
        .bind("version", version)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Long> incrementPolicySetRevision(Instant now) {
    return database
        .sql(
            "UPDATE policy_set_state SET revision = revision + 1, updated_at = :now "
                + "WHERE singleton_id = 1 RETURNING revision")
        .bind("now", offset(now))
        .map((row, metadata) -> required(row, "revision", Long.class))
        .one();
  }

  private Mono<Void> insertAudit(
      String policyId,
      long version,
      String action,
      PolicyLifecycle previous,
      PolicyLifecycle resulting,
      String actor,
      String correlationId,
      Instant now) {
    DatabaseClient.GenericExecuteSpec statement =
        database
            .sql(
                "INSERT INTO policy_audit(audit_id, policy_id, version, action, "
                    + "previous_status, resulting_status, actor, correlation_id, "
                    + "validation_outcome, occurred_at) "
                    + "VALUES (:auditId, :policyId, :version, :action, :previous, :resulting, "
                    + ":actor, :correlationId, 'VALID', :now)")
            .bind("auditId", UUID.randomUUID())
            .bind("policyId", policyId)
            .bind("version", version)
            .bind("action", action)
            .bind("resulting", resulting.name())
            .bind("actor", actor)
            .bind("correlationId", correlationId)
            .bind("now", offset(now));
    statement =
        previous == null
            ? statement.bindNull("previous", String.class)
            : statement.bind("previous", previous.name());
    return statement.fetch().rowsUpdated().then();
  }

  private Mono<Void> insertOutbox(PolicyEvent event) {
    return database
        .sql(
            "INSERT INTO policy_event_outbox(event_id, event_version, event_type, "
                + "policy_id, version, policy_set_revision, occurred_at, next_attempt_at) "
                + "VALUES (:eventId, :eventVersion, :eventType, :policyId, :version, "
                + ":revision, :occurredAt, :occurredAt)")
        .bind("eventId", event.eventId())
        .bind("eventVersion", event.eventVersion())
        .bind("eventType", event.eventType())
        .bind("policyId", event.policyId())
        .bind("version", event.version())
        .bind("revision", event.policySetRevision())
        .bind("occurredAt", offset(event.occurredAt()))
        .fetch()
        .rowsUpdated()
        .then();
  }

  private static StoredPolicyVersion mapVersion(Row row) {
    long methodCount = required(row, "method_count", Long.class);
    long identityCount = required(row, "identity_count", Long.class);
    if (methodCount != 1 || identityCount != 2) {
      throw new IllegalStateException("stored policy components are incomplete");
    }
    String algorithmType = required(row, "algorithm_type", String.class);
    boolean fixedPresent = row.get("fixed_algorithm_type", String.class) != null;
    boolean tokenPresent = row.get("token_algorithm_type", String.class) != null;
    if (fixedPresent == tokenPresent) {
      throw new IllegalStateException("stored policy must contain exactly one algorithm subtype");
    }
    lab.ratelimiter.gateway.policy.control.PolicyAlgorithmDefinition algorithm;
    if ("FIXED_WINDOW".equals(algorithmType) && fixedPresent) {
      algorithm =
          new FixedWindowAlgorithmDefinition(
              required(row, "request_limit", Long.class),
              Duration.ofMillis(required(row, "window_milliseconds", Long.class)));
    } else if ("TOKEN_BUCKET".equals(algorithmType) && tokenPresent) {
      algorithm =
          new TokenBucketAlgorithmDefinition(
              required(row, "capacity", Long.class),
              required(row, "initial_tokens", Long.class),
              required(row, "refill_tokens", Long.class),
              new RefillPeriod(
                  required(row, "refill_period_amount", Long.class),
                  refillUnit(required(row, "refill_period_unit", String.class))),
              required(row, "request_cost", Long.class));
    } else {
      throw new IllegalStateException(
          "stored policy algorithm discriminator conflicts with subtype");
    }
    PolicyDefinition definition =
        new PolicyDefinition(
            row.get("description", String.class),
            required(row, "route_id", String.class),
            required(row, "route_path", String.class),
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            algorithm,
            FailureMode.valueOf(required(row, "failure_mode", String.class)),
            required(row, "priority", Integer.class));
    OffsetDateTime activated = row.get("activated_at", OffsetDateTime.class);
    return new StoredPolicyVersion(
        required(row, "policy_id", String.class),
        required(row, "name", String.class),
        required(row, "version", Long.class),
        PolicyLifecycle.valueOf(required(row, "lifecycle_status", String.class)),
        required(row, "revision", Long.class),
        definition,
        required(row, "created_at", OffsetDateTime.class).toInstant(),
        required(row, "created_by", String.class),
        activated == null ? null : activated.toInstant(),
        row.get("activated_by", String.class));
  }

  private static RefillPeriod.Unit refillUnit(String symbol) {
    for (RefillPeriod.Unit unit : RefillPeriod.Unit.values()) {
      if (unit.symbol().equals(symbol)) {
        return unit;
      }
    }
    throw new IllegalStateException("stored refill period unit is unsupported");
  }

  private static OutboxEvent mapOutbox(Row row) {
    return new OutboxEvent(
        required(row, "event_id", UUID.class),
        required(row, "event_version", Integer.class),
        required(row, "event_type", String.class),
        required(row, "policy_id", String.class),
        required(row, "version", Long.class),
        required(row, "policy_set_revision", Long.class),
        required(row, "occurred_at", OffsetDateTime.class).toInstant(),
        required(row, "attempt_count", Integer.class));
  }

  private static void validateStable(
      String policyId, String name, long version, String actor, String correlationId) {
    requireText(policyId, "policy ID");
    if (policyId.getBytes(StandardCharsets.UTF_8).length > 128) {
      throw new IllegalArgumentException("policy ID exceeds 128 UTF-8 bytes");
    }
    requireText(name, "name");
    if (name.length() > 128) {
      throw new IllegalArgumentException("name exceeds 128 characters");
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    requireText(actor, "actor");
    requireText(correlationId, "correlation ID");
  }

  private static void validateMutation(
      String policyId, long version, String actor, String correlationId) {
    requireText(policyId, "policy ID");
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    requireText(actor, "actor");
    requireText(correlationId, "correlation ID");
  }

  private Mono<Long> count(String sql) {
    return database.sql(sql).map((row, metadata) -> required(row, "count", Long.class)).one();
  }

  private static void validatePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("page must be nonnegative and size must be 1..100");
    }
  }

  private static void requireLifecycle(
      StoredPolicyVersion policy, PolicyLifecycle requiredLifecycle) {
    if (policy.lifecycle() != requiredLifecycle) {
      throw new IllegalStateException("invalid policy transition");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static OffsetDateTime offset(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static <T> T required(Row row, String name, Class<T> type) {
    return Objects.requireNonNull(row.get(name, type), name);
  }

  private record ActiveRow(long revision, StoredPolicyVersion policy) {}
}
