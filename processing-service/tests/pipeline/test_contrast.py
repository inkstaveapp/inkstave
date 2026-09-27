"""Tests for `inkstave_processing.pipeline.contrast`."""

from __future__ import annotations

import numpy as np
import pytest

from inkstave_processing.pipeline.contrast import CONTRAST_METHOD, enhance_contrast
from inkstave_processing.pipeline.types import ImageU8


def _image_with_faint_mark() -> ImageU8:
    """White page with strong black ink and one faint grey "pencil" mark."""
    image: ImageU8 = np.full((200, 200, 3), 255, dtype=np.uint8)
    image[20:180, 20:30] = 0  # strong black "ink"
    image[80:120, 80:120] = 210  # faint grey "pencil mark"
    return image


def test_enhance_contrast_rejects_out_of_range_strength() -> None:
    image = _image_with_faint_mark()
    with pytest.raises(ValueError, match="strength"):
        enhance_contrast(image, strength=1.5)
    with pytest.raises(ValueError, match="strength"):
        enhance_contrast(image, strength=-0.1)


def test_enhance_contrast_returns_grayscale_with_the_documented_method_name() -> None:
    image = _image_with_faint_mark()

    result = enhance_contrast(image, strength=0.5)

    assert result.image.ndim == 2
    assert result.contrast_method == CONTRAST_METHOD


#: The faint mark's region in `_image_with_faint_mark`.
_MARK_REGION = (slice(80, 120), slice(80, 120))


def test_low_strength_keeps_the_faint_mark_legibly_distinct() -> None:
    """At low strength the faint mark stays mid-grey, distinct from both ink and
    background."""
    image = _image_with_faint_mark()

    result = enhance_contrast(image, strength=0.0)

    mark_mean = float(np.mean(result.image[_MARK_REGION]))
    assert 40 < mark_mean < 220, f"faint mark should stay legible, got mean={mark_mean:.1f}"


def test_full_strength_produces_a_near_binary_result() -> None:
    """At full strength the output is nearly pure black and white (losing the
    faint mark is the expected cost)."""
    image = _image_with_faint_mark()

    result = enhance_contrast(image, strength=1.0)

    near_black_or_white = np.sum((result.image < 10) | (result.image > 245))
    assert near_black_or_white / result.image.size > 0.95


def test_strength_zero_skips_binarization_without_changing_behavior() -> None:
    """Skipping thresholding at strength 0 must not change the output."""
    image = _image_with_faint_mark()

    result = enhance_contrast(image, strength=0.0)

    assert result.image.ndim == 2
    mark_mean = float(np.mean(result.image[_MARK_REGION]))
    assert 40 < mark_mean < 220
