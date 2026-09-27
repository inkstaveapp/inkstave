"""Tests for the OCR layout classification in `inkstave_processing.pipeline.ocr`, using synthetic
title pages with known text at known positions and sizes.
"""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from inkstave_processing.pipeline.ocr import extract_metadata_candidates
from inkstave_processing.pipeline.types import ImageU8

#: A common system font that, unlike `ImageFont.load_default()`, supports arbitrary point sizes,
#: which the title/composer size differences depend on.
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
    """A synthetic title page: large centered title, optional subtitle below it, and optional
    composer/arranger text top-right/top-left, as `docs/image-pipeline.md` describes."""
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


def test_title_confidence_is_the_highest_among_purely_positional_fields() -> None:
    # Uses an arranger without a label prefix: a label would outrank position, and this test
    # checks the positional rule on its own.
    result = extract_metadata_candidates(_make_title_page(arranger="Jane Doe"))

    assert "title" in result.confidence
    other_confidences = [v for k, v in result.confidence.items() if k != "title"]
    assert all(result.confidence["title"] >= other for other in other_confidences)


def test_a_label_match_outranks_a_positional_guess_in_confidence() -> None:
    # An explicit label must give higher confidence than position alone. Here the arranger is
    # label-matched ("arr. Jane Doe") and the composer purely positional, on the same page.
    result = extract_metadata_candidates(_make_title_page())

    assert "arranger" in result.confidence and "composer" in result.confidence
    assert result.confidence["arranger"] > result.confidence["composer"]


def test_lyricist_is_never_proposed() -> None:
    # See ocr.py's module doc: no positional heuristic exists for this field yet.
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


def _make_page_with_single_credit_line(
    text: str,
    *,
    x: int,
    top_y: int,
    size: int = 28,
    align: str = "left",
    width: int = 1000,
    height: int = 1300,
) -> ImageU8:
    """A page with a title and one extra line placed by `x`/`align` ("left": left edge, "right":
    right edge, "center": center). Edge-anchored rather than centered near an edge, because text
    pushed past the image border is clipped and OCR reads the remainder as garbage.
    """
    image = Image.new("RGB", (width, height), color=(255, 255, 255))
    draw = ImageDraw.Draw(image)
    _draw_text(draw, "UNTITLED WORK", size=64, center_x=width // 2, top_y=60)
    font = ImageFont.truetype(_FONT_PATH, size)
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = int(bbox[2] - bbox[0])
    draw_x: int
    if align == "left":
        draw_x = x
    elif align == "right":
        draw_x = x - text_width
    else:
        draw_x = x - text_width // 2
    draw.text((draw_x, top_y), text, font=font, fill=(0, 0, 0))
    rgb = np.array(image, dtype=np.uint8)
    return np.ascontiguousarray(rgb[:, :, ::-1])


def test_explicit_label_overrides_what_position_alone_would_have_guessed() -> None:
    # Position alone would call left-side text "arranger"; an explicit label must win.
    page = _make_page_with_single_credit_line("Composer: John Smith", x=60, top_y=40, align="left")

    result = extract_metadata_candidates(page)

    assert "JOHN SMITH" in result.candidates.get("composer", "").upper()
    assert "arranger" not in result.candidates


def test_lyrics_by_label_is_classified_as_lyricist() -> None:
    page = _make_page_with_single_credit_line("Lyrics by Jane Roe", x=60, top_y=40, align="left")

    result = extract_metadata_candidates(page)

    assert "JANE ROE" in result.candidates.get("lyricist", "").upper()
    assert "lyricist" in result.confidence


def test_words_by_label_is_also_classified_as_lyricist() -> None:
    page = _make_page_with_single_credit_line("Words by Jane Roe", x=940, top_y=40, align="right")

    result = extract_metadata_candidates(page)

    assert "JANE ROE" in result.candidates.get("lyricist", "").upper()


def test_ampersand_joined_names_on_one_line_stay_as_one_credit_not_two() -> None:
    # Normal word and "&" spacing within one line must stay under _split_by_horizontal_gap's
    # threshold, so a two-name credit isn't split into two blocks.
    page = _make_page_with_single_credit_line(
        "Music by John Smith & Jane Doe",
        x=500,
        top_y=40,
        align="center",
    )

    result = extract_metadata_candidates(page)

    composer = result.candidates.get("composer", "").upper()
    assert "JOHN SMITH" in composer
    assert "JANE DOE" in composer
    # Had the line been split, "& Jane Doe" would become a separate positional guess.
    assert "JANE DOE" not in result.candidates.get("arranger", "").upper()
    assert "JANE DOE" not in result.candidates.get("subtitle", "").upper()


def test_multiple_positionally_matching_lines_are_combined_not_reduced_to_one() -> None:
    # Two stacked, unlabeled top-right lines must both end up in the composer candidate.
    image = Image.new("RGB", (1000, 1300), color=(255, 255, 255))
    draw = ImageDraw.Draw(image)
    _draw_text(draw, "UNTITLED WORK", size=64, center_x=500, top_y=60)
    font = ImageFont.truetype(_FONT_PATH, 28)
    for i, name in enumerate(["John Smith", "Jane Doe"]):
        bbox = draw.textbbox((0, 0), name, font=font)
        text_width = bbox[2] - bbox[0]
        draw.text((1000 - text_width - 40, 200 + i * 60), name, font=font, fill=(0, 0, 0))
    rgb = np.array(image, dtype=np.uint8)
    page: ImageU8 = np.ascontiguousarray(rgb[:, :, ::-1])

    result = extract_metadata_candidates(page)

    composer = result.candidates.get("composer", "").upper()
    assert "JOHN SMITH" in composer
    assert "JANE DOE" in composer


def test_multiple_labeled_lines_for_the_same_field_are_combined() -> None:
    # Two separately labeled composer lines (co-composers) must both survive.
    image = Image.new("RGB", (1000, 1300), color=(255, 255, 255))
    draw = ImageDraw.Draw(image)
    _draw_text(draw, "UNTITLED WORK", size=64, center_x=500, top_y=60)
    font = ImageFont.truetype(_FONT_PATH, 28)
    for i, text in enumerate(["Composer: John Smith", "Composer: Jane Doe"]):
        draw.text((60, 200 + i * 60), text, font=font, fill=(0, 0, 0))
    rgb = np.array(image, dtype=np.uint8)
    page: ImageU8 = np.ascontiguousarray(rgb[:, :, ::-1])

    result = extract_metadata_candidates(page)

    composer = result.candidates.get("composer", "").upper()
    assert "JOHN SMITH" in composer
    assert "JANE DOE" in composer
