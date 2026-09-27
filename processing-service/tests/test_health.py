"""Integration test for /health through the real FastAPI app."""

from __future__ import annotations

from fastapi.testclient import TestClient

from inkstave_processing.app import SERVICE_NAME, SERVICE_VERSION, create_app


def test_health_returns_ok() -> None:
    client = TestClient(create_app())

    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body == {"status": "ok", "service": SERVICE_NAME, "version": SERVICE_VERSION}
