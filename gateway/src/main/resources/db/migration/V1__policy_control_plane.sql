CREATE TABLE policies (
    policy_id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    highest_activated_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(128) NOT NULL,
    CONSTRAINT policies_policy_id_nonblank CHECK (btrim(policy_id) <> ''),
    CONSTRAINT policies_name_nonblank CHECK (btrim(name) <> ''),
    CONSTRAINT policies_highest_version_positive
        CHECK (highest_activated_version IS NULL OR highest_activated_version > 0)
);

CREATE TABLE policy_versions (
    policy_id VARCHAR(128) NOT NULL REFERENCES policies(policy_id),
    version BIGINT NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    description VARCHAR(1024),
    route_id VARCHAR(128) NOT NULL,
    route_path VARCHAR(512) NOT NULL,
    algorithm_type VARCHAR(32) NOT NULL,
    failure_mode VARCHAR(16) NOT NULL,
    priority INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128) NOT NULL DEFAULT 'system',
    activated_at TIMESTAMPTZ,
    activated_by VARCHAR(128),
    disabled_at TIMESTAMPTZ,
    disabled_by VARCHAR(128),
    archived_at TIMESTAMPTZ,
    archived_by VARCHAR(128),
    PRIMARY KEY (policy_id, version),
    CONSTRAINT policy_versions_version_positive CHECK (version > 0),
    CONSTRAINT policy_versions_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT policy_versions_status_supported
        CHECK (lifecycle_status IN ('DRAFT', 'ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT policy_versions_route_id_normalized
        CHECK (route_id ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$'),
    CONSTRAINT policy_versions_route_path_proxy CHECK (route_path LIKE '/proxy/%'),
    CONSTRAINT policy_versions_algorithm_supported CHECK (algorithm_type = 'FIXED_WINDOW'),
    CONSTRAINT policy_versions_failure_mode_supported
        CHECK (failure_mode IN ('FAIL_OPEN', 'FAIL_CLOSED')),
    CONSTRAINT policy_versions_priority_bounded CHECK (priority BETWEEN 0 AND 1000),
    CONSTRAINT policy_versions_activation_audit_complete
        CHECK ((activated_at IS NULL) = (activated_by IS NULL))
);

CREATE UNIQUE INDEX policy_versions_one_active_per_policy
    ON policy_versions(policy_id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE TABLE policy_version_methods (
    policy_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    method VARCHAR(16) NOT NULL,
    PRIMARY KEY (policy_id, version, method),
    FOREIGN KEY (policy_id, version)
        REFERENCES policy_versions(policy_id, version) ON DELETE CASCADE,
    CONSTRAINT policy_version_methods_supported CHECK (method = 'GET')
);

CREATE TABLE policy_version_identity_components (
    policy_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    component_order SMALLINT NOT NULL,
    component_type VARCHAR(16) NOT NULL,
    header_name VARCHAR(128),
    PRIMARY KEY (policy_id, version, component_order),
    FOREIGN KEY (policy_id, version)
        REFERENCES policy_versions(policy_id, version) ON DELETE CASCADE,
    CONSTRAINT identity_component_order_supported CHECK (component_order IN (0, 1)),
    CONSTRAINT identity_component_shape CHECK (
        (component_order = 0 AND component_type = 'HEADER' AND header_name = 'X-Client-Id')
        OR (component_order = 1 AND component_type = 'ROUTE' AND header_name IS NULL)
    )
);

CREATE TABLE fixed_window_configurations (
    policy_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    request_limit BIGINT NOT NULL,
    window_milliseconds BIGINT NOT NULL,
    PRIMARY KEY (policy_id, version),
    FOREIGN KEY (policy_id, version)
        REFERENCES policy_versions(policy_id, version) ON DELETE CASCADE,
    CONSTRAINT fixed_window_limit_bounded CHECK (request_limit BETWEEN 1 AND 1000000),
    CONSTRAINT fixed_window_duration_bounded
        CHECK (window_milliseconds BETWEEN 1 AND 86400000)
);

CREATE TABLE policy_set_state (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT policy_set_singleton CHECK (singleton_id = 1),
    CONSTRAINT policy_set_revision_nonnegative CHECK (revision >= 0)
);

INSERT INTO policy_set_state(singleton_id, revision) VALUES (1, 0);

CREATE TABLE policy_audit (
    audit_id UUID PRIMARY KEY,
    policy_id VARCHAR(128) NOT NULL REFERENCES policies(policy_id),
    version BIGINT,
    action VARCHAR(32) NOT NULL,
    previous_status VARCHAR(16),
    resulting_status VARCHAR(16),
    actor VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    validation_outcome VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX policy_audit_policy_history
    ON policy_audit(policy_id, occurred_at DESC);

CREATE TABLE policy_event_outbox (
    event_id UUID PRIMARY KEY,
    event_version INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    policy_id VARCHAR(128) NOT NULL REFERENCES policies(policy_id),
    version BIGINT NOT NULL,
    policy_set_revision BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publication_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(64),
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_failure VARCHAR(32),
    CONSTRAINT policy_event_version_supported CHECK (event_version = 1),
    CONSTRAINT policy_event_type_supported
        CHECK (event_type IN ('POLICY_ACTIVATED', 'POLICY_DISABLED')),
    CONSTRAINT policy_event_revision_positive CHECK (policy_set_revision > 0),
    CONSTRAINT policy_event_status_supported
        CHECK (publication_status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    CONSTRAINT policy_event_attempt_nonnegative CHECK (attempt_count >= 0)
);

CREATE INDEX policy_event_outbox_ready
    ON policy_event_outbox(publication_status, next_attempt_at, occurred_at);

CREATE OR REPLACE FUNCTION prevent_activated_policy_definition_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.activated_at IS NOT NULL AND (
        NEW.description IS DISTINCT FROM OLD.description
        OR NEW.route_id IS DISTINCT FROM OLD.route_id
        OR NEW.route_path IS DISTINCT FROM OLD.route_path
        OR NEW.algorithm_type IS DISTINCT FROM OLD.algorithm_type
        OR NEW.failure_mode IS DISTINCT FROM OLD.failure_mode
        OR NEW.priority IS DISTINCT FROM OLD.priority
    ) THEN
        RAISE EXCEPTION 'activated policy definition is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER policy_versions_immutable_after_activation
BEFORE UPDATE ON policy_versions
FOR EACH ROW EXECUTE FUNCTION prevent_activated_policy_definition_mutation();

CREATE OR REPLACE FUNCTION prevent_activated_policy_child_mutation()
RETURNS TRIGGER AS $$
DECLARE
    target_policy_id VARCHAR(128);
    target_version BIGINT;
    target_activated_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_policy_id := OLD.policy_id;
        target_version := OLD.version;
    ELSE
        target_policy_id := NEW.policy_id;
        target_version := NEW.version;
    END IF;
    SELECT activated_at INTO target_activated_at
      FROM policy_versions
      WHERE policy_id = target_policy_id AND version = target_version;
    IF target_activated_at IS NOT NULL THEN
        RAISE EXCEPTION 'activated policy child definition is immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER policy_methods_immutable_after_activation
BEFORE INSERT OR UPDATE OR DELETE ON policy_version_methods
FOR EACH ROW EXECUTE FUNCTION prevent_activated_policy_child_mutation();

CREATE TRIGGER policy_identity_immutable_after_activation
BEFORE INSERT OR UPDATE OR DELETE ON policy_version_identity_components
FOR EACH ROW EXECUTE FUNCTION prevent_activated_policy_child_mutation();

CREATE TRIGGER fixed_window_immutable_after_activation
BEFORE INSERT OR UPDATE OR DELETE ON fixed_window_configurations
FOR EACH ROW EXECUTE FUNCTION prevent_activated_policy_child_mutation();
