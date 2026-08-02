"""Locally runnable catalog mock service."""

import asyncio
import os
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Annotated

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse

Delay = Callable[[float], Awaitable[None]]
TimestampProvider = Callable[[], datetime]
MAXIMUM_DELAY_MILLISECONDS = 5000


@dataclass(frozen=True)
class CatalogSettings:
    """Validated catalog runtime configuration."""

    delay_milliseconds: int
    test_endpoints_enabled: bool

    def __post_init__(self) -> None:
        """Reject values that would create an unbounded or negative delay."""
        if not 0 <= self.delay_milliseconds <= MAXIMUM_DELAY_MILLISECONDS:
            raise ValueError("catalog delay must be between 0 and 5000 milliseconds")

    @classmethod
    def from_environment(cls, environment: Mapping[str, str] | None = None) -> "CatalogSettings":
        """Load and validate settings from an explicit mapping or the process."""
        source = os.environ if environment is None else environment
        raw_delay = source.get("CATALOG_DELAY_MILLISECONDS", "0")
        try:
            delay = int(raw_delay)
        except ValueError as exception:
            raise ValueError("catalog delay must be a whole number") from exception

        raw_test_endpoints = source.get("CATALOG_TEST_ENDPOINTS_ENABLED", "false").lower()
        if raw_test_endpoints not in {"true", "false"}:
            raise ValueError("catalog test endpoint flag must be true or false")
        return cls(
            delay_milliseconds=delay,
            test_endpoints_enabled=raw_test_endpoints == "true",
        )


class RequestCounter:
    """Concurrency-safe process-local catalog request counter."""

    def __init__(self) -> None:
        """Create an empty counter."""
        self._value = 0
        self._lock = asyncio.Lock()

    async def increment(self) -> None:
        """Record one product request arrival."""
        async with self._lock:
            self._value += 1

    async def read(self) -> int:
        """Read the current arrival count."""
        async with self._lock:
            return self._value

    async def reset(self) -> int:
        """Reset the count and return the resulting value."""
        async with self._lock:
            self._value = 0
            return self._value


def utc_now() -> datetime:
    """Return a timezone-aware UTC timestamp."""
    return datetime.now(UTC)


def create_app(
    settings: CatalogSettings,
    *,
    delay: Delay = asyncio.sleep,
    timestamp_provider: TimestampProvider = utc_now,
) -> FastAPI:
    """Create an isolated catalog application from validated dependencies."""
    application = FastAPI(
        title="Rate Limiter Lab Catalog Mock",
        docs_url=None,
        redoc_url=None,
    )
    counter = RequestCounter()

    @application.exception_handler(Exception)
    async def internal_error(_request: Request, _exception: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=500,
            content={
                "status": 500,
                "error": "INTERNAL_ERROR",
                "message": "Catalog service failed",
            },
        )

    @application.get("/health", include_in_schema=False)
    async def health() -> dict[str, str]:
        return {"service": "catalog", "status": "UP"}

    @application.get("/catalog/items")
    async def catalog_items(
        request: Request,
        client_id: Annotated[str, Header(alias="X-Client-Id")],
        correlation_id: Annotated[str, Header(alias="X-Correlation-Id")],
    ) -> dict[str, object]:
        await counter.increment()
        requested_at = timestamp_provider()
        await delay(settings.delay_milliseconds / 1000)
        query_parameters = {key: request.query_params.getlist(key) for key in request.query_params}
        return {
            "service": "catalog",
            "clientId": client_id,
            "correlationId": correlation_id,
            "requestTimestamp": requested_at.isoformat(timespec="milliseconds").replace(
                "+00:00", "Z"
            ),
            "simulatedDelayMilliseconds": settings.delay_milliseconds,
            "queryParameters": query_parameters,
        }

    if settings.test_endpoints_enabled:

        @application.get("/_test/request-count", include_in_schema=False)
        async def request_count() -> dict[str, int]:
            return {"catalogRequests": await counter.read()}

        @application.post("/_test/request-count/reset", include_in_schema=False)
        async def reset_request_count() -> dict[str, int]:
            return {"catalogRequests": await counter.reset()}

    return application


app = create_app(CatalogSettings.from_environment())
