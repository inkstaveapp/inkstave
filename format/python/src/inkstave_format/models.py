"""Typed models for the .smpk format's JSON documents (format version 1).

Every model sets ``extra="allow"``: the spec requires unknown keys to
survive a read-modify-write round trip, so data from newer clients (e.g. OMR
results) isn't deleted by older ones.

JSON keys are camelCase; Python fields are snake_case with generated
aliases, so write with ``by_alias=True`` (or ``io.dump_json``).
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class _InkstaveModel(BaseModel):
    """Shared config: camelCase JSON aliases, unknown fields preserved."""

    model_config = ConfigDict(
        extra="allow",
        alias_generator=to_camel,
        populate_by_name=True,
    )


class ManifestSource(_InkstaveModel):
    """``manifest.json``'s ``source`` object. ``details`` is untyped because its
    shape depends on ``type`` and the spec doesn't define it yet.
    """

    type: str
    details: dict[str, object] = {}


class Manifest(_InkstaveModel):
    """The v1 ``manifest.json`` of a `.smpk` package (see ``docs/format-spec.md``)."""

    format_version: int
    id: str
    title: str
    subtitle: str | None = None
    composer: str | None = None
    arranger: str | None = None
    lyricist: str | None = None
    genre: str | None = None
    tags: list[str] = []
    parts: list[str] = []
    created_at: str
    modified_at: str
    source: ManifestSource


class Part(_InkstaveModel):
    """``parts/<part-id>/part.json``: one part's page ordering and display name."""

    id: str
    name: str
    page_order: list[str] = []


class PageProcessing(_InkstaveModel):
    """Reproducibility data for a page's cleanup, per docs/image-pipeline.md."""

    crop_polygon: list[tuple[float, float]] = []
    dewarp_mesh_version: str | None = None
    contrast_method: str | None = None


class PageOcr(_InkstaveModel):
    """OCR metadata candidates for a page. Always proposals, never auto-committed
    to ``manifest.json`` -- see docs/image-pipeline.md."""

    engine_version: str
    candidates: dict[str, str] = {}
    confidence: dict[str, float] = {}


class PageMeta(_InkstaveModel):
    """``pages/<page-id>.meta.json``: a page's dimensions and processing/OCR metadata."""

    id: str
    width: int
    height: int
    aspect_ratio_class: str
    processing: PageProcessing | None = None
    ocr: PageOcr | None = None


class Stroke(_InkstaveModel):
    """One freehand annotation stroke, in normalized page-point space."""

    id: str
    points: list[tuple[float, float]]
    color: str
    width_pt: float


class Stamp(_InkstaveModel):
    """One placed music-symbol stamp annotation."""

    id: str
    symbol: str
    x: float
    y: float
    scale: float
    rotation_deg: float


class Highlight(_InkstaveModel):
    """One highlighted rectangular region."""

    id: str
    rect_pt: tuple[float, float, float, float]
    color: str


class TextNote(_InkstaveModel):
    """One free-floating text annotation."""

    id: str
    x: float
    y: float
    text: str
    font_size_pt: float


class AnnotationLayer(_InkstaveModel):
    """``annotations/<page-id>.json``: the full vector annotation layer for one page."""

    page_id: str
    layer_version: int
    strokes: list[Stroke] = []
    stamps: list[Stamp] = []
    highlights: list[Highlight] = []
    text_notes: list[TextNote] = []
