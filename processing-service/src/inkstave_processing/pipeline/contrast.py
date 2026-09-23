"""Contrast / black-and-white cleanup (`docs/image-pipeline.md`, stage 4).

Turns an unevenly-lit photo into crisp notation on a clean background,
*without* blowing out faint pencil annotations a musician may want kept
legible -- `docs/image-pipeline.md` calls this out explicitly, which is why
`enhance_contrast` takes a tunable `strength` rather than always fully
binarizing.
"""

from __future__ import annotations

import numpy as np

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.types import ContrastResult, ImageU8

#: Identifies the method in `docs/format-spec.md`'s `processing.contrastMethod`.
CONTRAST_METHOD = "clahe-adaptive-threshold-blend-v1"

_CLAHE_CLIP_LIMIT = 2.0
_CLAHE_TILE_SIZE = 8
_ADAPTIVE_THRESHOLD_BLOCK_SIZE = 25
_ADAPTIVE_THRESHOLD_CONSTANT = 10.0


def enhance_contrast(image: ImageU8, strength: float = 0.7) -> ContrastResult:
    """Cleans up `image` (grayscale, or BGR -- converted to grayscale first; see
    `types.ContrastResult`'s doc for why the *output* is always grayscale).

    `strength` (`0.0` to `1.0`) blends between two results:

    - CLAHE alone (`strength=0.0`): local contrast enhancement that stays fully grayscale --
      evens out lighting without discarding any tonal information, so faint pencil marks (much
      lower contrast than printed ink) stay visible.
    - CLAHE, then fully binarized via adaptive thresholding (`strength=1.0`): crisp pure
      black-on-white, the best look for a clean printed page, but any faint pencil mark below the
      local threshold is lost entirely.

    Blending the two (rather than switching between two separate code paths at some cutoff) means
    there's no discontinuity in behavior as a user adjusts the setting -- `strength=0.5` looks
    like a sensible midpoint, not a jump between two different algorithms.
    """
    if not 0.0 <= strength <= 1.0:
        raise ValueError(f"strength must be between 0.0 and 1.0, got {strength}")

    gray = cv_backend.to_grayscale(image)
    clahe_result = cv_backend.apply_clahe(gray, _CLAHE_CLIP_LIMIT, _CLAHE_TILE_SIZE)

    if strength == 0.0:
        # Skip the (unused) binarization entirely rather than blend-with-zero-weight -- avoids
        # adaptiveThreshold's cost when its result would be discarded anyway.
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
