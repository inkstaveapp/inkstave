"""Aspect-ratio / size normalization (`docs/image-pipeline.md`, stage 5).

Classifies the cleaned page against the standard aspect ratio classes
`docs/format-spec.md`'s `aspectRatioClass` field expects (`"a4"`, `"letter"`,
or `"custom"`), pads it (never stretches or crops) to exactly match its
classified ratio if it's only slightly off, and resizes to a fixed
resolution tier -- `docs/image-pipeline.md`: "a resolution tier suitable for
the largest expected display (desktop), with the client responsible for
downscaling for smaller screens."
"""

from __future__ import annotations

import math

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.types import AspectRatioClass, ImageU8, NormalizeResult

#: Long-edge pixel length the output is resized to -- both up and down, not just capped, so
#: output resolution is predictable regardless of the input photo's own resolution. 2000px is
#: comfortably sharp on any desktop display this project targets (ADR-0001) without producing
#: unreasonably large files for a page that's mostly white space and a few thousand small marks.
_DEFAULT_LONG_EDGE_PX = 2000

#: Long-side-over-short-side ratios for the two standard classes. A4 is defined by ISO 216 as
#: exactly 1:sqrt(2); US Letter (8.5in x 11in) is exactly 8.5:11.
_A4_RATIO = math.sqrt(2)
_LETTER_RATIO = 11 / 8.5
#: How close a page's actual ratio must be to one of the above to be classified as that class,
#: rather than "custom" -- wide enough to absorb realistic page-detection imprecision, narrow
#: enough that a genuinely different page size (e.g. a French A4-ish-but-not-quite part) doesn't
#: get misclassified and silently distorted-by-padding into the wrong shape.
_RATIO_TOLERANCE = 0.03


def classify_aspect_ratio(width: int, height: int) -> AspectRatioClass:
    """Which `AspectRatioClass` `(width, height)` is closest to, within `_RATIO_TOLERANCE`, or
    `"custom"` if it doesn't match either standard class closely enough."""
    long_side, short_side = max(width, height), min(width, height)
    if short_side == 0:
        return "custom"
    ratio = long_side / short_side
    if abs(ratio - _A4_RATIO) / _A4_RATIO <= _RATIO_TOLERANCE:
        return "a4"
    if abs(ratio - _LETTER_RATIO) / _LETTER_RATIO <= _RATIO_TOLERANCE:
        return "letter"
    return "custom"


def normalize_page(image: ImageU8, long_edge_px: int = _DEFAULT_LONG_EDGE_PX) -> NormalizeResult:
    """Classifies, pads (to exactly match its class's canonical ratio, if not already exact), and
    resizes `image`. `"custom"`-classified pages are resized only -- there's no canonical ratio to
    pad them toward."""
    height, width = image.shape[:2]
    aspect_class = classify_aspect_ratio(width, height)
    padded = _pad_to_class_ratio(image, aspect_class) if aspect_class != "custom" else image
    resized = _resize_long_edge(padded, long_edge_px)
    out_height, out_width = resized.shape[:2]
    return NormalizeResult(
        image=resized,
        aspect_ratio_class=aspect_class,
        width=out_width,
        height=out_height,
    )


def _pad_to_class_ratio(image: ImageU8, aspect_class: AspectRatioClass) -> ImageU8:
    """Pads `image` with white borders so its ratio exactly matches `aspect_class`'s canonical
    ratio -- always by *adding* to the shorter dimension, never by cropping or stretching either
    one, per `docs/image-pipeline.md`'s explicit "preserving all content -- pad rather than
    stretch/distort" requirement."""
    height, width = image.shape[:2]
    target_ratio = _A4_RATIO if aspect_class == "a4" else _LETTER_RATIO

    if height >= width:
        desired_height = round(width * target_ratio)
        if desired_height > height:
            pad = desired_height - height
            return cv_backend.pad_image(image, pad // 2, pad - pad // 2, 0, 0)
        if desired_height < height:
            desired_width = round(height / target_ratio)
            pad = desired_width - width
            return cv_backend.pad_image(image, 0, 0, pad // 2, pad - pad // 2)
        return image

    desired_width = round(height * target_ratio)
    if desired_width > width:
        pad = desired_width - width
        return cv_backend.pad_image(image, 0, 0, pad // 2, pad - pad // 2)
    if desired_width < width:
        desired_height = round(width / target_ratio)
        pad = desired_height - height
        return cv_backend.pad_image(image, pad // 2, pad - pad // 2, 0, 0)
    return image


def _resize_long_edge(image: ImageU8, long_edge_px: int) -> ImageU8:
    """Resizes `image` so its longer side is exactly `long_edge_px`, preserving aspect ratio."""
    height, width = image.shape[:2]
    if max(height, width) == 0:
        return image
    scale = long_edge_px / max(height, width)
    new_width = max(1, round(width * scale))
    new_height = max(1, round(height * scale))
    return cv_backend.resize_image(image, new_width, new_height)
