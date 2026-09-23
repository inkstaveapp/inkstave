"""Tests for `inkstave_processing.pipeline.geometry`."""

from __future__ import annotations

import numpy as np

from inkstave_processing.pipeline.geometry import detect_page, geometric_correct
from inkstave_processing.pipeline.types import ImageU8
from tests.pipeline.fixtures import (
    BARLINE_COLUMNS,
    apply_horizontal_bow,
    compose_photo,
    make_flat_page,
)


def test_detect_page_finds_the_composed_page_corners() -> None:
    page = make_flat_page()
    photo, expected_corners = compose_photo(page)

    detection = detect_page(photo)

    assert detection is not None
    detected_corners = detection.corners
    for detected, expected in zip(detected_corners, expected_corners, strict=True):
        assert abs(detected[0] - expected[0]) < 5, f"x off by too much: {detected} vs {expected}"
        assert abs(detected[1] - expected[1]) < 5, f"y off by too much: {detected} vs {expected}"


def test_detect_page_returns_none_on_a_blank_uniform_image() -> None:
    blank: ImageU8 = np.full((200, 200, 3), 128, dtype=np.uint8)

    assert detect_page(blank) is None


def test_geometric_correct_on_a_flat_photo_recovers_roughly_original_proportions() -> None:
    page = make_flat_page(width=600, height=800)
    photo, _ = compose_photo(page)

    detection = detect_page(photo)
    assert detection is not None
    result = geometric_correct(photo, detection)

    height, width = result.image.shape[:2]
    assert abs(width - 600) < 15
    assert abs(height - 800) < 15
    assert len(result.crop_polygon) == 4


def _column_straightness_std(gray: ImageU8, expected_col: int, window: int = 25) -> float:
    """How much the darkest-pixel column wanders across rows, near `expected_col` -- a small value
    means the content there forms a straight vertical line; a large value means it's curved. Only
    looks at rows with at least one sufficiently dark pixel in the window, so background-only rows
    (outside the drawn line's vertical extent) don't count as noise."""
    width = gray.shape[1]
    left = max(0, expected_col - window)
    right = min(width, expected_col + window)
    strip = gray[:, left:right]
    dark_mask = strip < 128
    col_positions = []
    for row in range(strip.shape[0]):
        dark_cols = np.nonzero(dark_mask[row, :])[0]
        if len(dark_cols) > 0:
            col_positions.append(float(np.mean(dark_cols)))
    if len(col_positions) < 10:
        return float("inf")  # not enough signal to judge -- maximally bad, not a false pass
    return float(np.std(col_positions))


def test_geometric_correct_straightens_a_horizontally_bowed_page() -> None:
    # apply_horizontal_bow shifts each *row* sideways by an amount depending on its y-position --
    # a horizontal rule line (constant y) shifts as a whole and stays straight, but a vertical
    # barline (constant x) is exactly the kind of content that warp visibly bends into a curve
    # (see that function's doc). Test the barlines, not the rule lines, for that reason.
    page = make_flat_page(width=600, height=800)
    photo, _ = compose_photo(page)
    bowed_photo = apply_horizontal_bow(photo, amplitude_px=40.0)

    detection = detect_page(bowed_photo)
    assert detection is not None, "page detection must still succeed on a bowed (not flat) photo"
    corrected = geometric_correct(bowed_photo, detection)

    corrected_gray = np.mean(corrected.image, axis=2).astype(np.uint8)
    bowed_gray = np.mean(bowed_photo, axis=2).astype(np.uint8)

    # Compare straightness of the same barline, before vs after correction. The "before" value is
    # measured directly on the bowed photo at the same expected column (+150: compose_photo's
    # default horizontal canvas offset); "after" is scaled by how geometric_correct's output width
    # compares to the original page width (verified close by the proportions test above).
    scale = corrected.image.shape[1] / 600
    for expected_col in BARLINE_COLUMNS:
        before = _column_straightness_std(bowed_gray, expected_col + 150)
        after = _column_straightness_std(corrected_gray, round(expected_col * scale))
        assert after < before / 3, (
            f"column {expected_col}: dewarping should substantially straighten a bowed barline "
            f"(before={before:.2f}, after={after:.2f})"
        )
