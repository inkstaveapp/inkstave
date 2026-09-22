"""Typed request/response models for the processing service's local HTTP API.

Kept separate from ``inkstave_format`` (which models the *file* format):
these describe the wire contract between the desktop client and this
service (``docs/image-pipeline.md``), a distinct, though related, concern
from what ends up written into a `.smpk`.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel


class HealthStatus(BaseModel):
    """Response body for ``GET /health``."""

    status: Literal["ok"]
    service: str
    version: str
