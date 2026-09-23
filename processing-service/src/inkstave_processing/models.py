"""Typed request/response models for the processing service's local HTTP API.

Kept separate from ``inkstave_format`` (which models the *file* format):
these describe the wire contract between the desktop client and this
service (``docs/image-pipeline.md``), a distinct, though related, concern
from what ends up written into a `.smpk`.
"""

from __future__ import annotations

from typing import Literal

from inkstave_format import PageOcr, PageProcessing
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class HealthStatus(BaseModel):
    """Response body for ``GET /health``."""

    status: Literal["ok"]
    service: str
    version: str


class ProcessPageResponse(BaseModel):
    """Response body for ``POST /process-page`` (``docs/image-pipeline.md``'s "Service interface
    (sketch)": ``{ cleanedImage, processingParams, ocrCandidates }``).

    Reuses ``inkstave_format``'s own ``PageProcessing``/``PageOcr`` models for the
    ``processing``/``ocr`` fields rather than declaring a parallel shape -- this response *is*
    (almost) what ends up in a `.smpk`'s ``pages/<page-id>.meta.json`` (`docs/format-spec.md`);
    the caller (the desktop client, once a later M4 slice wires it up) still owns assigning a
    ``page-id`` and actually writing it into the package via ``SmpkWriter``/``SmpkUpdater``, not
    this service.

    Uses the same camelCase JSON alias convention as ``inkstave_format``'s own models
    (``docs/format-spec.md``: "every JSON document in this format uses camelCase keys") for its
    *own* top-level fields too, not just the nested ``processing``/``ocr`` objects that already
    inherit it from ``PageProcessing``/``PageOcr`` -- without this, the response would mix
    conventions (snake_case at the top level, camelCase one level down), which is exactly the kind
    of inconsistency a test caught (see `tests/test_process_page.py`) rather than something
    shipped unnoticed.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    #: The cleaned/normalized page image, PNG-encoded then base64-encoded -- JSON was chosen over
    #: a raw-bytes/multipart response specifically so this endpoint's response is trivially
    #: testable via `TestClient.json()` and consumable from any HTTP client without a separate
    #: multipart parser, at the (acceptable, ~33%) size cost base64 always carries; the *request*
    #: side (see `app.py`'s handler) avoids that cost instead, where the payload is largest.
    cleaned_image_base64: str
    width: int
    height: int
    aspect_ratio_class: str
    processing: PageProcessing
    ocr: PageOcr
