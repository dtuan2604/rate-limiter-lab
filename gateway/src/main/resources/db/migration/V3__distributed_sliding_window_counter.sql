ALTER TABLE policy_versions
    DROP CONSTRAINT policy_versions_algorithm_supported;

ALTER TABLE policy_versions
    ADD CONSTRAINT policy_versions_algorithm_supported
        CHECK (algorithm_type IN ('FIXED_WINDOW', 'TOKEN_BUCKET', 'SLIDING_WINDOW_COUNTER'));

CREATE TABLE sliding_window_counter_configurations (
    policy_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    algorithm_type VARCHAR(32) NOT NULL DEFAULT 'SLIDING_WINDOW_COUNTER',
    request_limit BIGINT NOT NULL,
    window_amount BIGINT NOT NULL,
    window_unit VARCHAR(2) NOT NULL,
    request_cost BIGINT NOT NULL,
    PRIMARY KEY (policy_id, version),
    CONSTRAINT sliding_window_counter_algorithm_type
        CHECK (algorithm_type = 'SLIDING_WINDOW_COUNTER'),
    CONSTRAINT sliding_window_counter_policy_algorithm_fk
        FOREIGN KEY (policy_id, version, algorithm_type)
        REFERENCES policy_versions(policy_id, version, algorithm_type)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT sliding_window_counter_limit_bounded
        CHECK (request_limit BETWEEN 1 AND 1000000),
    CONSTRAINT sliding_window_counter_window_unit_supported
        CHECK (window_unit IN ('ms', 's', 'm', 'h', 'd')),
    CONSTRAINT sliding_window_counter_window_bounded CHECK (
        window_amount BETWEEN 1 AND 86400000
        AND window_amount * CASE window_unit
            WHEN 'ms' THEN 1
            WHEN 's' THEN 1000
            WHEN 'm' THEN 60000
            WHEN 'h' THEN 3600000
            WHEN 'd' THEN 86400000
        END <= 86400000
    ),
    CONSTRAINT sliding_window_counter_request_cost_bounded
        CHECK (request_cost BETWEEN 1 AND request_limit)
);

CREATE TRIGGER sliding_window_counter_immutable_after_activation
BEFORE INSERT OR UPDATE OR DELETE ON sliding_window_counter_configurations
FOR EACH ROW EXECUTE FUNCTION prevent_activated_policy_child_mutation();
