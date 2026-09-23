"""Runs the full pipeline (`docs/image-pipeline.md`) on one captured/imported page image."""

from __future__ import annotations

from inkstave_processing.pipeline.contrast import enhance_contrast
from inkstave_processing.pipeline.geometry import detect_page, geometric_correct
from inkstave_processing.pipeline.normalize import normalize_page
from inkstave_processing.pipeline.types import ImageU8, ProcessedPage


class PageDetectionFailed(Exception):
    """Raised by `process_page` when `geometry.detect_page` can't find a page boundary in the
    input image at all -- see that function's doc for when this happens. A real, expected outcome
    for a bad photo (no page in frame, background too similar to the page), not an internal
    error; callers (the eventual HTTP endpoint, M4's later slices) are expected to catch this and
    surface it to the user as "couldn't find the page in this photo," not a 500."""


def process_page(image: ImageU8, contrast_strength: float = 0.7) -> ProcessedPage:
    """Runs geometric correction, contrast cleanup, and aspect-ratio normalization on `image`, in
    that order, and combines their results into one `ProcessedPage`.

    Raises `PageDetectionFailed` if no page boundary can be found. Every other stage always
    succeeds (by construction -- contrast and normalization have no failure mode, only different
    output depending on input).
    """
    detection = detect_page(image)
    if detection is None:
        raise PageDetectionFailed("no page boundary detected in the input image")

    geometric = geometric_correct(image, detection)
    contrast = enhance_contrast(geometric.image, contrast_strength)
    normalized = normalize_page(contrast.image)

    return ProcessedPage(
        image=normalized.image,
        crop_polygon=geometric.crop_polygon,
        dewarp_mesh_version=geometric.dewarp_mesh_version,
        contrast_method=contrast.contrast_method,
        aspect_ratio_class=normalized.aspect_ratio_class,
        width=normalized.width,
        height=normalized.height,
    )
