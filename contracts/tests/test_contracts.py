"""Executable tests for the Phase 0 contract foundations."""

import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate

CONTRACTS_ROOT = Path(__file__).resolve().parents[1]
EXAMPLES_ROOT = CONTRACTS_ROOT / "examples"


def load_json(path: Path) -> dict[str, Any]:
    """Load a JSON object fixture."""
    document = json.loads(path.read_text(encoding="utf-8"))
    assert isinstance(document, dict)
    return document


def validation_paths(schema_name: str, example_name: str) -> list[list[str | int]]:
    """Return stable paths for every validation failure."""
    schema = load_json(CONTRACTS_ROOT / schema_name)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    instance = load_json(EXAMPLES_ROOT / example_name)
    return [list(error.absolute_path) for error in validator.iter_errors(instance)]


def test_approved_token_bucket_policy_is_valid() -> None:
    """The documented token-bucket policy example satisfies the initial schema."""
    assert validation_paths("policy.schema.json", "policy.valid.json") == []


def test_non_positive_policy_capacity_fails_at_stable_path() -> None:
    """An invalid capacity is reported at the algorithm field."""
    assert validation_paths("policy.schema.json", "policy.invalid.json") == [
        ["algorithm", "capacity"]
    ]


def test_empty_phase_zero_traffic_scenario_is_valid() -> None:
    """The non-inventive empty v0 traffic-scenario scaffold is valid."""
    assert validation_paths("traffic-scenario.schema.json", "traffic.valid.json") == []


def test_unapproved_traffic_field_fails_at_document_root() -> None:
    """Unapproved traffic fields are rejected rather than silently accepted."""
    assert validation_paths("traffic-scenario.schema.json", "traffic.invalid.json") == [[]]


def test_documented_rate_limit_error_is_valid() -> None:
    """The approved Phase 2 structured 429 shape is accepted."""
    assert validation_paths("error.schema.json", "error.valid.json") == []


def test_missing_client_error_is_valid() -> None:
    """The missing trusted simulation identity has a strict 400 contract."""
    assert validation_paths("error.schema.json", "error.missing-client.valid.json") == []


def test_unmatched_route_error_is_valid() -> None:
    """An unmatched proxy request has a strict 404 contract."""
    assert validation_paths("error.schema.json", "error.route-not-found.valid.json") == []


def test_backend_unavailable_error_is_valid() -> None:
    """A failed catalog connection has a strict 502 contract."""
    assert validation_paths("error.schema.json", "error.backend-unavailable.valid.json") == []


def test_rate_limit_state_unavailable_error_is_valid() -> None:
    """A fail-closed Redis decision has a strict structured 503 contract."""
    assert (
        validation_paths("error.schema.json", "error.rate-limit-state-unavailable.valid.json") == []
    )


def test_negative_retry_after_fails_at_stable_path() -> None:
    """Negative retry durations are structurally invalid."""
    assert validation_paths("error.schema.json", "error.invalid.json") == [
        ["retryAfterMilliseconds"]
    ]


def test_admin_openapi_is_valid_and_defers_unapproved_paths() -> None:
    """The initial OpenAPI file is valid without inventing endpoint paths."""
    openapi_path = CONTRACTS_ROOT / "admin-api.openapi.yaml"
    document = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
    assert isinstance(document, dict)

    validate(document, base_uri=f"{CONTRACTS_ROOT.as_uri()}/")

    assert document["paths"] == {}
