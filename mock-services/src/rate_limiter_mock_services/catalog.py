"""Catalog mock-service process health."""

from fastapi import FastAPI

app = FastAPI(title="Rate Limiter Lab Catalog Mock", docs_url=None, redoc_url=None)


@app.get("/health", include_in_schema=False)
async def health() -> dict[str, str]:
    """Report process health without claiming product readiness."""
    return {"service": "catalog", "status": "UP"}
