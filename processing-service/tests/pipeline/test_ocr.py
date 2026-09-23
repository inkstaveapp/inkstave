"""Unit tests for `inkstave_processing.pipeline.ocr` -- the OCR layout-classification heuristic,
run against synthetic title-page images with known text at known positions/sizes, the same
honest synthetic-fixture spirit as the rest of this pipeline's tests (`tests/pipeline/fixtures.py`'s
module doc). There's no real scanned sheet music to test against; a rendered image with
known-correct text in known-correct positions is the closest honest substitute.
"""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from inkstave_processing.pipeline.ocr import extract_metadata_candidates
from inkstave_processing.pipeline.types import ImageU8

#: A widely-available system font (present on this dev machine and any standard Debian/Ubuntu
#: install) -- picked over `ImageFont.load_default()` specifically because it supports arbitrary
#: point sizes, which this module's title/composer size differences require to be a meaningful
#: test signal at all.
_FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def _draw_text(draw: ImageDraw.ImageDraw, text: str, size: int, center_x: int, top_y: int) -> None:
    font = ImageFont.truetype(_FONT_PATH, size)
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    draw.text((center_x - text_width // 2, top_y), text, font=font, fill=(0, 0, 0))


def _make_title_page(
    *,
    title: str = "SONATA NO. 14",
    subtitle: str | None = "Moonlight",
    composer: str | None = "L. van Beethoven",
    arranger: str | None = "arr. Jane Doe",
    width: int = 1000,
    height: int = 1300,
) -> ImageU8:
    """A synthetic page laid out like a real title page: a large centered title near the top, an
    optional smaller centered subtitle below it, and optional smaller composer/arranger text in
    the top-right/top-left -- exactly the convention `docs/image-pipeline.md`'s heuristic (and
    this module's own subtitle extension) targets. Body of the page is left blank (real sheet
    music notation isn't relevant to this heuristic, which only looks at the upper region)."""
    image = Image.new("RGB", (width, height), color=(255, 255, 255))
    draw = ImageDraw.Draw(image)
    _draw_text(draw, title, size=64, center_x=width // 2, top_y=60)
    if subtitle is not None:
        _draw_text(draw, subtitle, size=36, center_x=width // 2, top_y=150)
    if composer is not None:
        font = ImageFont.truetype(_FONT_PATH, 28)
        bbox = draw.textbbox((0, 0), composer, font=font)
        draw.text((width - (bbox[2] - bbox[0]) - 40, 40), composer, font=font, fill=(0, 0, 0))
    if arranger is not None:
        _draw_text(draw, arranger, size=28, center_x=120, top_y=40)
    rgb = np.array(image, dtype=np.uint8)
    return np.ascontiguousarray(rgb[:, :, ::-1])


def test_classifies_title_composer_arranger_and_subtitle_correctly() -> None:
    page = _make_title_page()

    result = extract_metadata_candidates(page)

    assert "SONATA" in result.candidates.get("title", "").upper()
    assert "MOONLIGHT" in result.candidates.get("subtitle", "").upper()
    assert "BEETHOVEN" in result.candidates.get("composer", "").upper()
    assert "JANE DOE" in result.candidates.get("arranger", "").upper()


def test_title_confidence_is_the_highest_of_the_classified_fields() -> None:
    # Title's positional assumption (largest + centered) is the strongest of this heuristic's --
    # see ocr.py's module doc on why title's weight is higher than subtitle/composer/arranger's.
    result = extract_metadata_candidates(_make_title_page())

    assert "title" in result.confidence
    other_confidences = [v for k, v in result.confidence.items() if k != "title"]
    assert all(result.confidence["title"] >= other for other in other_confidences)


def test_lyricist_is_never_proposed() -> None:
    # See ocr.py's module doc: no defensible positional heuristic exists for this field yet.
    result = extract_metadata_candidates(_make_title_page())

    assert "lyricist" not in result.candidates
    assert "lyricist" not in result.confidence


def test_missing_composer_and_arranger_are_simply_absent_not_guessed() -> None:
    page = _make_title_page(composer=None, arranger=None)

    result = extract_metadata_candidates(page)

    assert "title" in result.candidates
    assert "composer" not in result.candidates
    assert "arranger" not in result.candidates


def test_blank_page_yields_no_candidates_not_an_error() -> None:
    blank: ImageU8 = np.full((400, 300), 255, dtype=np.uint8)

    result = extract_metadata_candidates(blank)

    assert result.candidates == {}
    assert result.confidence == {}
    assert result.engine_version  # still populated even with nothing recognized


def test_engine_version_is_a_non_empty_string() -> None:
    result = extract_metadata_candidates(_make_title_page())

    assert isinstance(result.engine_version, str)
    assert result.engine_version != ""
