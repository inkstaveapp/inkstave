"""Contrast / black-and-white cleanup (`docs/image-pipeline.md`, stage 4).

Turns an unevenly lit photo into crisp notation on a clean background
without erasing faint pencil annotations, which is why `enhance_contrast`
takes a tunable `strength` instead of always binarising.
"""

from __future__ import annotations

import numpy as np

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.types import ContrastResult, ImageU8

#: Stored as `processing.contrastMethod`.
CONTRAST_METHOD = "clahe-adaptive-threshold-blend-v1"

_CLAHE_CLIP_LIMIT = 2.0
_CLAHE_TILE_SIZE = 8
_ADAPTIVE_THRESHOLD_BLOCK_SIZE = 25
_ADAPTIVE_THRESHOLD_CONSTANT = 10.0


def enhance_contrast(image: ImageU8, strength: float = 0.7) -> ContrastResult:
    """Cleans up `image` (grayscale or BGR) and returns grayscale.

    `strength` (`0.0`-`1.0`) blends CLAHE alone (evens lighting, keeps faint
    pencil marks) with CLAHE plus adaptive thresholding (crisp black on white,
    but faint marks are lost). Blending rather than switching keeps the setting
    continuous.
    """
    if not 0.0 <= strength <= 1.0:
        raise ValueError(f"strength must be between 0.0 and 1.0, got {strength}")

    gray = cv_backend.to_grayscale(image)
    clahe_result = cv_backend.apply_clahe(gray, _CLAHE_CLIP_LIMIT, _CLAHE_TILE_SIZE)

    if strength == 0.0:
        # Skip thresholding whose result would get zero weight.
        return ContrastResult(image=clahe_result, contrast_method=CONTRAST_METHOD)

    binarized = cv_backend.adaptive_threshold(
        clahe_result,
        _ADAPTIVE_THRESHOLD_BLOCK_SIZE,
        _ADAPTIVE_THRESHOLD_CONSTANT,
    )
    blended = (1.0 - strength) * clahe_result.astype(np.float64) + strength * binarized.astype(
        np.float64,
    )
    return ContrastResult(image=blended.astype(np.uint8), contrast_method=CONTRAST_METHOD)
