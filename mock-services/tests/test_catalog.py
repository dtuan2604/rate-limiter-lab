"""Catalog mock-service behavior tests."""

import asyncio
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, Response

from rate_limiter_mock_services.catalog import CatalogSettings, create_app, utc_now

CLIENT_HEADERS = {
    "X-Client-Id": "client-a",
    "X-Correlation-Id": "correlation-a",
}
FIXED_TIME = datetime(2026, 7, 26, 12, 30, 45, 123000, tzinfo=UTC)


def test_health_endpoint_reports_catalog_process_health() -> None:
    """Health remains independent from product request counting and headers."""
    app = create_app(CatalogSettings(delay_milliseconds=0, test_endpoints_enabled=True))

    async def exercise() -> None:
        response = await send(app, "GET", "/health")
        count = await send(app, "GET", "/_test/request-count")

        assert response.status_code == 200
        assert response.json() == {"service": "catalog", "status": "UP"}
        assert count.json() == {"catalogRequests": 0}

    asyncio.run(exercise())


def test_catalog_response_schema_and_propagated_metadata() -> None:
    """The product response exposes required metadata and received query values."""
    app = create_app(
        CatalogSettings(delay_milliseconds=0, test_endpoints_enabled=False),
        timestamp_provider=lambda: FIXED_TIME,
    )

    async def exercise() -> None:
        response = await send(
            app,
            "GET",
            "/catalog/items?page=2&tag=a&tag=b",
            headers=CLIENT_HEADERS,
        )

        assert response.status_code == 200
        assert response.json() == {
            "service": "catalog",
            "clientId": "client-a",
            "correlationId": "correlation-a",
            "requestTimestamp": "2026-07-26T12:30:45.123Z",
            "simulatedDelayMilliseconds": 0,
            "queryParameters": {"page": ["2"], "tag": ["a", "b"]},
        }

    asyncio.run(exercise())


def test_catalog_requires_client_and_correlation_headers() -> None:
    """Missing propagated metadata is rejected by FastAPI validation."""
    app = create_app(CatalogSettings(delay_milliseconds=0, test_endpoints_enabled=False))

    async def exercise() -> None:
        missing_client = await send(
            app,
            "GET",
            "/catalog/items",
            headers={"X-Correlation-Id": "correlation"},
        )
        missing_correlation = await send(
            app,
            "GET",
            "/catalog/items",
            headers={"X-Client-Id": "client"},
        )

        assert missing_client.status_code == 422
        assert missing_correlation.status_code == 422

    asyncio.run(exercise())


def test_delay_configuration_uses_injected_scheduler_without_sleeping() -> None:
    """Configured delay is delegated as seconds to a controlled async function."""
    observed_delays: list[float] = []

    async def controlled_delay(seconds: float) -> None:
        observed_delays.append(seconds)

    app = create_app(
        CatalogSettings(delay_milliseconds=125, test_endpoints_enabled=False),
        delay=controlled_delay,
        timestamp_provider=lambda: FIXED_TIME,
    )

    async def exercise() -> None:
        response = await send(app, "GET", "/catalog/items", headers=CLIENT_HEADERS)

        assert response.status_code == 200
        assert response.json()["simulatedDelayMilliseconds"] == 125
        assert observed_delays == [0.125]

    asyncio.run(exercise())


def test_request_counter_is_opt_in_and_resettable() -> None:
    """The protected development counter proves product endpoint arrivals."""
    enabled = create_app(
        CatalogSettings(delay_milliseconds=0, test_endpoints_enabled=True),
        timestamp_provider=lambda: FIXED_TIME,
    )
    disabled = create_app(
        CatalogSettings(delay_milliseconds=0, test_endpoints_enabled=False),
        timestamp_provider=lambda: FIXED_TIME,
    )

    async def exercise() -> None:
        await send(enabled, "GET", "/catalog/items", headers=CLIENT_HEADERS)
        await send(enabled, "GET", "/catalog/items", headers=CLIENT_HEADERS)
        count = await send(enabled, "GET", "/_test/request-count")
        reset = await send(enabled, "POST", "/_test/request-count/reset")
        after_reset = await send(enabled, "GET", "/_test/request-count")
        hidden = await send(disabled, "GET", "/_test/request-count")

        assert count.json() == {"catalogRequests": 2}
        assert reset.json() == {"catalogRequests": 0}
        assert after_reset.json() == {"catalogRequests": 0}
        assert hidden.status_code == 404

    asyncio.run(exercise())


@pytest.mark.parametrize(
    ("environment", "message"),
    [
        ({"CATALOG_DELAY_MILLISECONDS": "-1"}, "between 0 and 5000"),
        ({"CATALOG_DELAY_MILLISECONDS": "5001"}, "between 0 and 5000"),
        ({"CATALOG_DELAY_MILLISECONDS": "slow"}, "whole number"),
    ],
)
def test_invalid_delay_configuration_fails(environment: dict[str, str], message: str) -> None:
    """Invalid delay environment values fail before the app can start."""
    with pytest.raises(ValueError, match=message):
        CatalogSettings.from_environment(environment)


def test_invalid_test_endpoint_flag_fails_configuration() -> None:
    """Observation endpoints require an explicit boolean setting."""
    with pytest.raises(ValueError, match="must be true or false"):
        CatalogSettings.from_environment({"CATALOG_TEST_ENDPOINTS_ENABLED": "sometimes"})


def test_default_timestamp_provider_is_timezone_aware_utc() -> None:
    """Production timestamps always carry the shared UTC time basis."""
    assert utc_now().tzinfo is UTC


def test_internal_errors_are_sanitized_and_still_count_as_arrivals() -> None:
    """Unhandled product failures return stable JSON without leaking details."""

    def fail_timestamp() -> datetime:
        raise RuntimeError("private backend detail")

    app = create_app(
        CatalogSettings(delay_milliseconds=0, test_endpoints_enabled=True),
        timestamp_provider=fail_timestamp,
    )

    async def exercise() -> None:
        response = await send(
            app,
            "GET",
            "/catalog/items",
            headers=CLIENT_HEADERS,
            raise_app_exceptions=False,
        )
        count = await send(app, "GET", "/_test/request-count")

        assert response.status_code == 500
        assert response.json() == {
            "status": 500,
            "error": "INTERNAL_ERROR",
            "message": "Catalog service failed",
        }
        assert "private backend detail" not in response.text
        assert count.json() == {"catalogRequests": 1}

    asyncio.run(exercise())


async def send(
    app: FastAPI,
    method: str,
    path: str,
    *,
    headers: dict[str, str] | None = None,
    raise_app_exceptions: bool = True,
) -> Response:
    """Send one in-process request without external network access."""
    transport = ASGITransport(app=app, raise_app_exceptions=raise_app_exceptions)
    async with AsyncClient(transport=transport, base_url="http://catalog.test") as client:
        request: Callable[..., Awaitable[Response]] = getattr(client, method.lower())
        return await request(path, headers=headers)
