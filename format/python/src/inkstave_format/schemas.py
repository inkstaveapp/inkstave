"""JSON Schema validation for the .smpk format's documents.

These check *shape* against the schemas in ``format/schema/`` -- the
"spec / contract" level test `docs/testing-strategy.md` calls for -- as a
layer distinct from (and complementary to) the typed models in
``models.py``. A document can fail schema validation for reasons the typed
models wouldn't catch on their own (e.g. an out-of-range ``confidence``
value), and vice versa.
"""

from __future__ import annotations

import json
from functools import cache
from pathlib import Path
from typing import Any

import jsonschema

# format/python/src/inkstave_format/schemas.py -> format/schema
_SCHEMA_DIR = Path(__file__).resolve().parents[3] / "schema"


@cache
def _load_schema(filename: str) -> dict[str, Any]:
    path = _SCHEMA_DIR / filename
    with path.open(encoding="utf-8") as handle:
        loaded: dict[str, Any] = json.load(handle)
        return loaded


def validate_manifest(data: dict[str, Any]) -> None:
    """Validates `data` against manifest.v1.schema.json. Raises jsonschema.ValidationError."""
    jsonschema.validate(instance=data, schema=_load_schema("manifest.v1.schema.json"))


def validate_part(data: dict[str, Any]) -> None:
    """Validates `data` against part.v1.schema.json. Raises jsonschema.ValidationError."""
    jsonschema.validate(instance=data, schema=_load_schema("part.v1.schema.json"))


def validate_page_meta(data: dict[str, Any]) -> None:
    """Validates `data` against page-meta.v1.schema.json. Raises jsonschema.ValidationError."""
    jsonschema.validate(instance=data, schema=_load_schema("page-meta.v1.schema.json"))


def validate_annotation_layer(data: dict[str, Any]) -> None:
    """Validates `data` against annotations.v1.schema.json. Raises jsonschema.ValidationError."""
    jsonschema.validate(instance=data, schema=_load_schema("annotations.v1.schema.json"))
