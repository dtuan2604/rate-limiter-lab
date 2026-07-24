"""Orders process-health tests."""

import asyncio

from httpx import ASGITransport, AsyncClient

from rate_limiter_mock_services.orders import app


def test_orders_reports_process_health() -> None:
    """The orders skeleton exposes only its process-health boundary."""

    async def exercise_service() -> None:
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://orders.test"
        ) as client:
            response = await client.get("/health")
            missing = await client.get("/orders")

        assert response.status_code == 200
        assert response.json() == {"service": "orders", "status": "UP"}
        assert missing.status_code == 404

    asyncio.run(exercise_service())
