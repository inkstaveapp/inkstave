"""Tests for inkstave_format: the "spec / contract" and "unit" level tests
docs/testing-strategy.md calls for on format/'s field validation and
round-trip behaviour (mirrors client/shared's ManifestJsonTest.kt)."""

from __future__ import annotations

import json
from pathlib import Path

import jsonschema
import pytest

from inkstave_format import (
    AnnotationLayer,
    Manifest,
    PageMeta,
    Part,
    dump_json,
    parse_json,
    validate_annotation_layer,
    validate_manifest,
    validate_page_meta,
    validate_part,
)

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "fixtures"


def _read_fixture(name: str) -> str:
    return (FIXTURES_DIR / name).read_text(encoding="utf-8")


def test_manifest_fixture_is_schema_valid() -> None:
    validate_manifest(json.loads(_read_fixture("manifest.v1.json")))


def test_part_fixture_is_schema_valid() -> None:
    validate_part(json.loads(_read_fixture("part.v1.json")))


def test_page_meta_fixture_is_schema_valid() -> None:
    validate_page_meta(json.loads(_read_fixture("page-meta.v1.json")))


def test_annotation_layer_fixture_is_schema_valid() -> None:
    validate_annotation_layer(json.loads(_read_fixture("annotations.v1.json")))


def test_invalid_manifest_missing_required_field_fails_validation() -> None:
    data = json.loads(_read_fixture("manifest.v1.json"))
    del data["title"]
    with pytest.raises(jsonschema.ValidationError):
        validate_manifest(data)


def test_manifest_model_reads_known_fields() -> None:
    manifest = parse_json(Manifest, _read_fixture("manifest.v1.json"))

    assert manifest.format_version == 1
    assert manifest.title == "Clair de Lune"
    assert manifest.composer == "Claude Debussy"
    assert manifest.tags == ["piano", "impressionist"]
    assert manifest.source.type == "pdf-import"


def test_manifest_round_trip_preserves_unknown_fields() -> None:
    data = json.loads(_read_fixture("manifest.v1.json"))
    data["futureField"] = {"nested": "value-from-a-newer-client"}

    manifest = parse_json(Manifest, json.dumps(data))
    re_encoded = json.loads(dump_json(manifest))

    assert re_encoded["futureField"] == {"nested": "value-from-a-newer-client"}
    assert re_encoded["title"] == "Clair de Lune"
    # camelCase, matching the format spec and the Kotlin side -- not snake_case.
    assert "formatVersion" in re_encoded
    assert "format_version" not in re_encoded


def test_part_model_reads_page_order() -> None:
    part = parse_json(Part, _read_fixture("part.v1.json"))

    assert part.id == "part-piano"
    assert part.page_order == ["page-1", "page-2", "page-3"]


def test_page_meta_model_reads_nested_ocr_candidates() -> None:
    page_meta = parse_json(PageMeta, _read_fixture("page-meta.v1.json"))

    assert page_meta.aspect_ratio_class == "a4"
    assert page_meta.ocr is not None
    assert page_meta.ocr.candidates["title"] == "Clair de Lune"


def test_annotation_layer_model_reads_all_annotation_kinds() -> None:
    layer = parse_json(AnnotationLayer, _read_fixture("annotations.v1.json"))

    assert len(layer.strokes) == 1
    assert len(layer.stamps) == 1
    assert layer.stamps[0].symbol == "fermata"
    assert len(layer.highlights) == 1
    assert len(layer.text_notes) == 1
