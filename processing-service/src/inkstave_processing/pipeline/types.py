"""Types shared by the pipeline stages and by callers outside the package."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

import numpy as np
from numpy.typing import NDArray

#: An 8-bit image: ``(height, width, 3)`` BGR (OpenCV's channel order) or
#: ``(height, width)`` grayscale.
ImageU8 = NDArray[np.uint8]

#: An ``(x, y)`` pixel coordinate; floats because positions are sub-pixel.
Point = tuple[float, float]

#: Mirrors the ``aspectRatioClass`` enum in ``page-meta.v1.schema.json``.
AspectRatioClass = Literal["a4", "letter", "custom"]


@dataclass(frozen=True)
class GeometricCorrectionResult:
    """Output of :func:`inkstave_processing.pipeline.geometry.geometric_correct`.

    :ivar image: the corrected page, already cropped to its bounds.
    :ivar crop_polygon: page corners in the input image's pixels,
        ``[TL, TR, BR, BL]`` (``processing.cropPolygon``).
    :ivar dewarp_mesh_version: the correction model that produced `image`
        (``processing.dewarpMeshVersion``).
    """

    image: ImageU8
    crop_polygon: list[Point]
    dewarp_mesh_version: str


@dataclass(frozen=True)
class ContrastResult:
    """Output of :func:`inkstave_processing.pipeline.contrast.enhance_contrast`.

    :ivar image: grayscale output; colour carries no information in sheet music.
    :ivar contrast_method: stored as ``processing.contrastMethod``.
    """

    image: ImageU8
    contrast_method: str


@dataclass(frozen=True)
class NormalizeResult:
    """Output of :func:`inkstave_processing.pipeline.normalize.normalize_page`."""

    image: ImageU8
    aspect_ratio_class: AspectRatioClass
    width: int
    height: int


@dataclass(frozen=True)
class ProcessedPage:
    """The full pipeline's output for one page (:func:`run.process_page`).

    Fields map directly onto `inkstave_format`'s ``PageProcessing``,
    ``PageOcr`` and ``PageMeta``. The OCR fields are proposals, never
    confirmed metadata.
    """

    image: ImageU8
    crop_polygon: list[Point]
    dewarp_mesh_version: str
    contrast_method: str
    aspect_ratio_class: AspectRatioClass
    width: int
    height: int
    ocr_engine_version: str
    ocr_candidates: dict[str, str]
    ocr_confidence: dict[str, float]
