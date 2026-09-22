"""The FastAPI application.

M0 scaffolding only: a health check. The real pipeline endpoint
(``POST /process-page``, per ``docs/image-pipeline.md``) lands in M4,
owned by the ``image-pipeline`` agent (``.claude/agents/image-pipeline.md``).
"""

from __future__ import annotations

from fastapi import FastAPI

from inkstave_processing.models import HealthStatus

SERVICE_NAME = "inkstave-processing"
SERVICE_VERSION = "0.1.0"


def create_app() -> FastAPI:
    """Builds the FastAPI application. A factory (not a module-level singleton)
    so tests can construct a fresh instance per test if that's ever needed."""
    app = FastAPI(title=SERVICE_NAME, version=SERVICE_VERSION)

    @app.get("/health")
    def health() -> HealthStatus:
        """Liveness check the desktop client can poll before sending pipeline requests."""
        return HealthStatus(status="ok", service=SERVICE_NAME, version=SERVICE_VERSION)

    return app
