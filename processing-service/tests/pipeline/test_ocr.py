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


def test_title_confidence_is_the_highest_among_purely_positional_fields() -> None:
    # Title's positional assumption (largest + centered) is the strongest *positional* one this
    # heuristic makes -- see ocr.py's module doc. Default fixture's arranger ("arr. Jane Doe") is
    # itself label-matched (see the label-priority tests below), which is a stronger signal than
    # any position and is expected to outrank title's -- so this test uses an arranger with no
    # label prefix, to isolate and confirm the original positional-only invariant still holds
    # among fields position alone is classifying.
    result = extract_metadata_candidates(_make_title_page(arranger="Jane Doe"))

    assert "title" in result.confidence
    other_confidences = [v for k, v in result.confidence.items() if k != "title"]
    assert all(result.confidence["title"] >= other for other in other_confidences)


def test_a_label_match_outranks_a_positional_guess_in_confidence() -> None:
    # The actual new priority this pass adds: an explicit label is a stronger signal than any
    # inferred position, and that should show up as a real confidence difference, not just a
    # classification difference. Default fixture's arranger is "arr. Jane Doe" (label-matched);
    # composer is "L. van Beethoven" (no label, purely positional) -- same page, same OCR
    # conditions, so this isolates the label-vs-position confidence difference specifically.
    result = extract_metadata_candidates(_make_title_page())

    assert "arranger" in result.confidence and "composer" in result.confidence
    assert result.confidence["arranger"] > result.confidence["composer"]


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
    """A page with just a title and one extra line of text positioned by `x`/`align` ("left":
    `x` is the text's left edge; "right": `x` is its right edge; "center": `x` is its center) --
    isolates label-vs-position classification without the rest of `_make_title_page`'s fixed
    layout getting in the way. Left/right alignment (not center-on-a-point) specifically to avoid
    a real trap: centering a longer credit line on a point near either edge can push part of the
    text past the image boundary, where it gets silently clipped and OCR reads the clipped
    remainder as garbage -- this bit an earlier version of these tests directly (a "Composer:
    John Smith" line centered near the left edge OCR'd as "nposer: John Smith").
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
    # "Composer: John Smith" placed on the *left* -- pure position would call left-side text
    # "arranger" (docs/image-pipeline.md's own top-right/top-left convention), but an explicit
    # label must win regardless of where it physically sits on the page.
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
    # A single OCR'd line naturally containing two names ("John Smith & Jane Doe") must not be
    # mistaken by _split_by_horizontal_gap for two separate credit blocks sharing a row (the
    # mechanism that correctly splits e.g. a composer-top-right/arranger-top-left pair that share
    # a row with nothing else interrupting it) -- normal word/"&"-spacing must stay well under
    # that split threshold.
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
    # If the line had incorrectly been split, "& Jane Doe" would fall through to the positional
    # fallback as its own line and could turn up as a spurious arranger/subtitle guess instead of
    # staying part of the one composer candidate.
    assert "JANE DOE" not in result.candidates.get("arranger", "").upper()
    assert "JANE DOE" not in result.candidates.get("subtitle", "").upper()


def test_multiple_positionally_matching_lines_are_combined_not_reduced_to_one() -> None:
    # Two separate, unlabeled composer-position (top-right) lines, stacked vertically -- both
    # should end up in the combined composer candidate, not just whichever one the old
    # single-winner logic would have picked.
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
    # Two separately-labeled composer lines (an unusual but real case -- two co-composers each
    # explicitly credited) must both survive into the combined candidate, not just one.
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
