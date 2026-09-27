"""The FastAPI application: ``GET /health`` and ``POST /process-page``, which
runs the full cleanup and OCR pipeline on one uploaded image.
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
    """Builds the FastAPI application (a factory, so tests get a fresh instance)."""
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
        """Runs the pipeline on one page image and returns the cleaned page plus
        processing and OCR metadata.

        The body is the raw image bytes, avoiding JSON or multipart overhead on the
        largest payload. `session_id`/`sequence_index` are part of the documented
        contract but not used yet. Returns 422, not 500, for an undecodable image or
        a photo with no detectable page.
        """
        # Part of the contract, not used yet.
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
