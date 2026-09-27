"""Typed Python models and JSON Schema validation for the Inkstave .smpk format.

``docs/format-spec.md`` is the authoritative specification this mirrors.
"""

from inkstave_format.io import dump_json, parse_json
from inkstave_format.models import (
    AnnotationLayer,
    Highlight,
    Manifest,
    ManifestSource,
    PageMeta,
    PageOcr,
    PageProcessing,
    Part,
    Stamp,
    Stroke,
    TextNote,
)
from inkstave_format.schemas import (
    validate_annotation_layer,
    validate_manifest,
    validate_page_meta,
    validate_part,
)

__all__ = [
    "AnnotationLayer",
    "Highlight",
    "Manifest",
    "ManifestSource",
    "PageMeta",
    "PageOcr",
    "PageProcessing",
    "Part",
    "Stamp",
    "Stroke",
    "TextNote",
    "dump_json",
    "parse_json",
    "validate_annotation_layer",
    "validate_manifest",
    "validate_page_meta",
    "validate_part",
]
