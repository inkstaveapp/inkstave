"""Integration test for `POST /process-page`, per `docs/testing-strategy.md`'s "Desktop client
<-> processing-service" requirement: real HTTP calls through `TestClient` against the real
pipeline (`inkstave_processing.pipeline.run.process_page`), not a mocked one -- the same spirit
as `test_health.py`'s existing test."""

from __future__ import annotations

import base64

import cv2
import numpy as np
from fastapi.testclient import TestClient

from inkstave_processing.app import create_app
from inkstave_processing.pipeline.cv_backend import encode_png
from tests.pipeline.fixtures import apply_horizontal_bow, compose_photo, make_flat_page


def _synthetic_photo_png_bytes() -> bytes:
    page = make_flat_page(width=500, height=650)
    photo, _ = compose_photo(page)
    bowed = apply_horizontal_bow(photo, amplitude_px=15.0)
    return encode_png(bowed)


def test_process_page_returns_cleaned_image_and_metadata() -> None:
    client = TestClient(create_app())

    response = client.post(
        "/process-page?session_id=test-session&sequence_index=0&contrast_strength=0.7",
        content=_synthetic_photo_png_bytes(),
        headers={"content-type": "application/octet-stream"},
    )

    assert response.status_code == 200
    body = response.json()

    # camelCase throughout, top level included -- ProcessPageResponse's own doc explains why
    # (consistency with inkstave_format's established JSON convention, docs/format-spec.md).
    cleaned_bytes = base64.b64decode(body["cleanedImageBase64"])
    decoded = cv2.imdecode(np.frombuffer(cleaned_bytes, dtype=np.uint8), cv2.IMREAD_UNCHANGED)
    assert decoded is not None  # the response really is a valid, decodable PNG

    assert body["width"] > 0
    assert body["height"] > 0
    assert body["aspectRatioClass"] in ("a4", "letter", "custom")
    assert len(body["processing"]["cropPolygon"]) == 4
    assert body["processing"]["dewarpMeshVersion"]
    assert body["processing"]["contrastMethod"]
    # This synthetic fixture has no real text on it (see fixtures.py's module doc) -- an
    # empty-but-present OCR result is the correct outcome here, not a failure. test_ocr.py covers
    # the classification heuristic itself against realistic title-page fixtures.
    assert body["ocr"]["engineVersion"]
    assert isinstance(body["ocr"]["candidates"], dict)


def test_process_page_returns_422_not_500_when_no_page_is_found() -> None:
    client = TestClient(create_app())
    blank = np.full((300, 300, 3), 128, dtype=np.uint8)

    response = client.post(
        "/process-page?session_id=test-session&sequence_index=0",
        content=encode_png(blank),
        headers={"content-type": "application/octet-stream"},
    )

    assert response.status_code == 422
    assert "detail" in response.json()


def test_process_page_returns_422_for_undecodable_bytes() -> None:
    client = TestClient(create_app())

    response = client.post(
        "/process-page?session_id=test-session&sequence_index=0",
        content=b"not an image at all",
        headers={"content-type": "application/octet-stream"},
    )

    assert response.status_code == 422
