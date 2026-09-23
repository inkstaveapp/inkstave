"""Tests for `inkstave_processing.pipeline.normalize`."""

from __future__ import annotations

import math

import numpy as np

from inkstave_processing.pipeline.normalize import classify_aspect_ratio, normalize_page
from inkstave_processing.pipeline.types import ImageU8

_A4_RATIO = math.sqrt(2)
_LETTER_RATIO = 11 / 8.5


def _solid_image(width: int, height: int) -> ImageU8:
    return np.full((height, width, 3), 255, dtype=np.uint8)


def test_classify_aspect_ratio_recognizes_a4() -> None:
    assert classify_aspect_ratio(1000, round(1000 * _A4_RATIO)) == "a4"


def test_classify_aspect_ratio_recognizes_letter() -> None:
    assert classify_aspect_ratio(1000, round(1000 * _LETTER_RATIO)) == "letter"


def test_classify_aspect_ratio_falls_back_to_custom() -> None:
    assert classify_aspect_ratio(1000, 1000) == "custom"  # a square matches neither


def test_normalize_page_on_an_already_a4_image_needs_no_padding() -> None:
    width, height = 600, round(600 * _A4_RATIO)
    image = _solid_image(width, height)

    result = normalize_page(image, long_edge_px=1000)

    assert result.aspect_ratio_class == "a4"
    assert result.height == 1000  # the long edge
    assert abs(result.width / result.height - 1 / _A4_RATIO) < 0.005


def test_normalize_page_pads_a_near_a4_image_to_the_exact_ratio_without_cropping() -> None:
    # Slightly short of true A4 (within classify_aspect_ratio's tolerance) -- should be padded,
    # not cropped or stretched, to land on the exact ratio.
    width, height = 600, round(600 * _A4_RATIO * 0.99)
    image = _solid_image(width, height)
    image[10:20, 10:20] = 0  # a marker so we can confirm it's still present (not cropped away)

    result = normalize_page(image, long_edge_px=1000)

    assert result.aspect_ratio_class == "a4"
    assert abs(result.width / result.height - 1 / _A4_RATIO) < 0.005
    assert np.any(result.image == 0), "padding must never crop away existing content"


def test_normalize_page_on_a_custom_ratio_image_only_resizes() -> None:
    width, height = 300, 900  # far from both A4 and Letter
    image = _solid_image(width, height)

    result = normalize_page(image, long_edge_px=1000)

    assert result.aspect_ratio_class == "custom"
    assert result.height == 1000
    assert abs(result.width / width - result.height / height) < 0.01  # uniform scale, no distortion
