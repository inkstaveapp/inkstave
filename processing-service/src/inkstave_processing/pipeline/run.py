"""Runs the full pipeline (`docs/image-pipeline.md`) on one captured/imported page image."""

from __future__ import annotations

from inkstave_processing.pipeline.contrast import enhance_contrast
from inkstave_processing.pipeline.geometry import detect_page, geometric_correct
from inkstave_processing.pipeline.normalize import normalize_page
from inkstave_processing.pipeline.ocr import extract_metadata_candidates
from inkstave_processing.pipeline.types import ImageU8, ProcessedPage


class PageDetectionFailed(Exception):
    """No page boundary was found in the photo. An expected outcome for a bad
    photo, to be reported to the user, not an internal error."""


def process_page(image: ImageU8, contrast_strength: float = 0.7) -> ProcessedPage:
    """Runs geometry correction, contrast cleanup, normalisation and OCR on
    `image`, in that order. Raises `PageDetectionFailed` if no page is found;
    the other stages can't fail.

    OCR runs last, on the final image, so its positional heuristics see exactly
    what the client displays.
    """
    detection = detect_page(image)
    if detection is None:
        raise PageDetectionFailed("no page boundary detected in the input image")

    geometric = geometric_correct(image, detection)
    contrast = enhance_contrast(geometric.image, contrast_strength)
    normalized = normalize_page(contrast.image)
    ocr = extract_metadata_candidates(normalized.image)

    return ProcessedPage(
        image=normalized.image,
        crop_polygon=geometric.crop_polygon,
        dewarp_mesh_version=geometric.dewarp_mesh_version,
        contrast_method=contrast.contrast_method,
        aspect_ratio_class=normalized.aspect_ratio_class,
        width=normalized.width,
        height=normalized.height,
        ocr_engine_version=ocr.engine_version,
        ocr_candidates=ocr.candidates,
        ocr_confidence=ocr.confidence,
    )
