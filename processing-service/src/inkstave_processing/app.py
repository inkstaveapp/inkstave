"""The FastAPI application.

``GET /health`` (M0) and ``POST /process-page`` (M4 slice 2, per
``docs/image-pipeline.md``'s "Service interface (sketch)") -- the full
image-cleanup + OCR pipeline (``inkstave_processing.pipeline.run.process_page``),
exposed as one local HTTP call. Still loopback-only
(``processing-service/README.md``'s ``127.0.0.1:8787``) -- this module
doesn't change that, it only gives the desktop client something to call
besides ``/health``. Wiring the *client* to actually call this endpoint is a
later M4 slice, not this one.
"""

from __future__ import annotations

import base64

from fastapi import FastAPI, HTTPException, Request
from inkstave_format import PageOcr, PageProcessing

from inkstave_processing.models import HealthStatus, ProcessPageResponse
from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.run import PageDetectionFailed, process_page

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

    @app.post("/process-page")
    async def process_page_endpoint(
        request: Request,
        session_id: str,
        sequence_index: int,
        contrast_strength: float = 0.7,
    ) -> ProcessPageResponse:
        """Runs the full pipeline on one captured/imported page image and returns the cleaned
        result plus processing/OCR metadata -- `docs/image-pipeline.md`'s sketched contract.

        `session_id`/`sequence_index` identify which capture session and which photo within it
        this request belongs to (query parameters, not part of the body -- the body is the raw
        image bytes themselves, undecoded, to avoid the size/encoding overhead a JSON- or
        multipart-wrapped request would add for what's typically the largest payload this service
        ever handles; see `ProcessPageResponse`'s own doc for why the *response* makes the
        opposite trade-off). Neither is used by the pipeline itself yet -- they exist in this
        request shape now because `docs/image-pipeline.md` already specifies them as part of the
        contract, for a later M4 slice (LAN capture-session handling) to actually make use of,
        not because this endpoint does anything with them today beyond accepting them.

        Raises HTTP 422 (not 500) if no page can be detected in the submitted image -- a real,
        expected outcome for a bad photo, per `PageDetectionFailed`'s own doc, not a server error.
        """
        # Accepted per the documented contract; not yet used by the pipeline -- see docstring.
        del session_id, sequence_index
        image_bytes = await request.body()
        try:
            image = cv_backend.decode_image(image_bytes)
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

        try:
            result = process_page(image, contrast_strength=contrast_strength)
        except PageDetectionFailed as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

        cleaned_png = cv_backend.encode_png(result.image)
        return ProcessPageResponse(
            cleaned_image_base64=base64.b64encode(cleaned_png).decode("ascii"),
            width=result.width,
            height=result.height,
            aspect_ratio_class=result.aspect_ratio_class,
            processing=PageProcessing(
                crop_polygon=result.crop_polygon,
                dewarp_mesh_version=result.dewarp_mesh_version,
                contrast_method=result.contrast_method,
            ),
            ocr=PageOcr(
                engine_version=result.ocr_engine_version,
                candidates=result.ocr_candidates,
                confidence=result.ocr_confidence,
            ),
        )

    return app
