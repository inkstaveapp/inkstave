"""Aspect-ratio and size normalisation (`docs/image-pipeline.md`, stage 5).

Classifies the page as `"a4"`, `"letter"` or `"custom"`, pads (never
stretches or crops) a slightly-off page to its class's exact ratio, and
resizes to a fixed resolution; the client downscales for smaller screens.
"""

from __future__ import annotations

import math

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.types import AspectRatioClass, ImageU8, NormalizeResult

#: Output long edge in pixels, scaled up or down so resolution is predictable:
#: sharp on desktop displays without large files for mostly white pages.
_DEFAULT_LONG_EDGE_PX = 2000

#: Long/short side ratios: A4 is 1:sqrt(2) (ISO 216), US Letter is 8.5:11.
_A4_RATIO = math.sqrt(2)
_LETTER_RATIO = 11 / 8.5
#: Relative tolerance for matching a class: wide enough for detection error,
#: narrow enough that a genuinely different page size isn't padded into the
#: wrong shape.
_RATIO_TOLERANCE = 0.03


def classify_aspect_ratio(width: int, height: int) -> AspectRatioClass:
    """The standard class `(width, height)` matches within `_RATIO_TOLERANCE`,
    else `"custom"`."""
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
    """Classifies, pads to the class's exact ratio, and resizes `image`. Custom
    pages are only resized."""
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
    """Pads `image` with white so its ratio matches `aspect_class` exactly. Only
    ever adds, never crops or stretches, so no content is lost."""
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
