"""Typed Python models and JSON Schema validation for the Inkstave .smpk format.

See ``docs/format-spec.md`` in the repository root for the authoritative
prose specification this package mirrors, and ``docs/coding-standards.md``
for why these are typed models rather than raw ``dict`` parsing.
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
