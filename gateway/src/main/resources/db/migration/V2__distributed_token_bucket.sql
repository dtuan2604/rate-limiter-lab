ALTER TABLE policy_versions
    DROP CONSTRAINT policy_versions_algorithm_supported;

ALTER TABLE policy_versions
    ADD CONSTRAINT policy_versions_algorithm_supported
        CHECK (algorithm_type IN ('FIXED_WINDOW', 'TOKEN_BUCKET')),
    ADD CONSTRAINT policy_versions_algorithm_identity
        UNIQUE (policy_id, version, algorithm_type);

ALTER TABLE fixed_window_configurations
    ADD COLUMN algorithm_type VARCHAR(32) NOT NULL DEFAULT 'FIXED_WINDOW';

ALTER TABLE fixed_window_configurations
    ADD CONSTRAINT fixed_window_algorithm_type
        CHECK (algorithm_type = 'FIXED_WINDOW'),
    ADD CONSTRAINT fixed_window_policy_algorithm_fk
        FOREIGN KEY (policy_id, version, algorithm_type)
        REFERENCES policy_versions(policy_id, version, algorithm_type)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE token_bucket_configurations (
    policy_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    algorithm_type VARCHAR(32) NOT NULL DEFAULT 'TOKEN_BUCKET',
    capacity BIGINT NOT NULL,
    initial_tokens BIGINT NOT NULL,
    refill_tokens BIGINT NOT NULL,
    refill_period_amount BIGINT NOT NULL,
    refill_period_unit VARCHAR(2) NOT NULL,
    request_cost BIGINT NOT NULL,
    PRIMARY KEY (policy_id, version),
    CONSTRAINT token_bucket_algorithm_type CHECK (algorithm_type = 'TOKEN_BUCKET'),
    CONSTRAINT token_bucket_policy_algorithm_fk
        FOREIGN KEY (policy_id, version, algorithm_type)
        REFERENCES policy_versions(policy_id, version, algorithm_type)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT token_bucket_capacity_bounded CHECK (capacity BETWEEN 1 AND 100000),
    CONSTRAINT token_bucket_initial_tokens_bounded
        CHECK (initial_tokens BETWEEN 0 AND capacity),
    CONSTRAINT token_bucket_refill_tokens_bounded
        CHECK (refill_tokens BETWEEN 1 AND 100000),
    CONSTRAINT token_bucket_request_cost_bounded
        CHECK (request_cost BETWEEN 1 AND 100000 AND request_cost <= capacity),
    CONSTRAINT token_bucket_refill_period_unit_supported
        CHECK (refill_period_unit IN ('ms', 's', 'm', 'h', 'd')),
    CONSTRAINT token_bucket_refill_period_bounded CHECK (
        refill_period_amount BETWEEN 1 AND 86400000
        AND refill_period_amount * CASE refill_period_unit
            WHEN 'ms' THEN 1
            WHEN 's' THEN 1000
            WHEN 'm' THEN 60000
            WHEN 'h' THEN 3600000
            WHEN 'd' THEN 86400000
        END <= 86400000
    ),
    CONSTRAINT token_bucket_empty_to_full_bounded CHECK (
        ((capacity + refill_tokens - 1) / refill_tokens)
        * refill_period_amount * CASE refill_period_unit
            WHEN 'ms' THEN 1
            WHEN 's' THEN 1000
            WHEN 'm' THEN 60000
            WHEN 'h' THEN 3600000
            WHEN 'd' THEN 86400000
        END <= 2592000000
    )
);

CREATE TRIGGER token_bucket_immutable_after_activation
BEFORE INSERT OR UPDATE OR DELETE ON token_bucket_configurations
FOR EACH ROW EXECUTE FUNCTION prevent_activated_policy_child_mutation();
