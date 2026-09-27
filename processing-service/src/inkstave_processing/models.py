"""Request/response models for the service's local HTTP API.

Separate from ``inkstave_format``, which models the file format: these
describe the wire contract between the desktop client and this service.
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
    """Response body for ``POST /process-page``.

    ``processing`` and ``ocr`` reuse ``inkstave_format``'s models, since this is
    nearly what the client writes into a page's ``meta.json``; the client, not
    this service, assigns the page id and writes the package. Top-level fields
    use camelCase too, matching the nested models.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    #: The cleaned page as base64-encoded PNG. JSON keeps the response easy to consume
    #: and test, at base64's ~33% size cost; the larger request avoids that cost.
    cleaned_image_base64: str
    width: int
    height: int
    aspect_ratio_class: str
    processing: PageProcessing
    ocr: PageOcr
