"""Shared types for the pipeline package.

Kept separate from any one stage module since several stages share them
(:class:`Point`, :class:`ImageU8`) or are consumed outside the pipeline
package entirely (:class:`AspectRatioClass` mirrors
``format/schema/page-meta.v1.schema.json``'s ``aspectRatioClass`` enum and
``inkstave_format``'s ``PageMeta`` -- see :mod:`inkstave_processing.pipeline.normalize`
for why it's redeclared here rather than imported, despite the overlap).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

import numpy as np
from numpy.typing import NDArray

#: An 8-bit image array: ``(height, width, 3)`` for BGR (OpenCV's native
#: channel order) or ``(height, width)`` for single-channel grayscale.
#: OpenCV's own stubs type most of its functions' return values more loosely
#: than this (see :mod:`inkstave_processing.pipeline.cv_backend`'s module
#: docstring) -- this alias is this package's own, precise contract for
#: what a stage actually hands the next one.
ImageU8 = NDArray[np.uint8]

#: A single ``(x, y)`` pixel coordinate, as floats since sub-pixel positions
#: arise throughout (contour points, fitted curve samples, remap grids).
Point = tuple[float, float]

#: Mirrors ``format/schema/page-meta.v1.schema.json``'s ``aspectRatioClass``
#: enum exactly (``docs/format-spec.md``, ``pages/<page-id>.meta.json``).
AspectRatioClass = Literal["a4", "letter", "custom"]


@dataclass(frozen=True)
class GeometricCorrectionResult:
    """Output of :func:`inkstave_processing.pipeline.geometry.geometric_correct`.

    :ivar image: the corrected page, cropped to its own bounds -- this *is*
        the "cropping" stage `docs/image-pipeline.md` lists separately; see
        that module's docstring for why cropping isn't its own function here.
    :ivar crop_polygon: the detected page corners in the *original* (input)
        image's pixel coordinates, ``[TL, TR, BR, BL]`` -- this is exactly
        `docs/format-spec.md`'s ``pages/<page-id>.meta.json``'s
        ``processing.cropPolygon`` shape.
    :ivar dewarp_mesh_version: identifies which correction model produced
        `image`, for `docs/format-spec.md`'s
        ``processing.dewarpMeshVersion`` (reprocessing/reproducibility) --
        see :mod:`inkstave_processing.pipeline.geometry`'s docstring for
        the honest scope of what this value's model actually corrects for.
    """

    image: ImageU8
    crop_polygon: list[Point]
    dewarp_mesh_version: str


@dataclass(frozen=True)
class ContrastResult:
    """Output of :func:`inkstave_processing.pipeline.contrast.enhance_contrast`.

    :ivar image: single-channel (grayscale) output -- contrast cleanup
        operates on and produces grayscale, per `docs/image-pipeline.md`
        ("crisp black notation on a clean white background"); color isn't
        meaningful for sheet music and carrying it through would only cost
        memory/bandwidth for nothing this format needs.
    :ivar contrast_method: for `docs/format-spec.md`'s
        ``processing.contrastMethod``.
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
    """The full pipeline's output for one page
    (:func:`inkstave_processing.pipeline.run.process_page`).

    Deliberately shaped to drop straight into `inkstave_format`'s
    ``PageProcessing``/``PageMeta`` fields without reshaping -- see that
    module's fields (``crop_polygon``, ``dewarp_mesh_version``,
    ``contrast_method``) and `format/schema/page-meta.v1.schema.json`.
    Converting this into an actual `PageMeta` (and wiring it into an HTTP
    endpoint or `.smpk`) is out of scope for this pass -- see
    `docs/decisions/`-style scope notes in `ROADMAP.md`'s M4 entry.
    """

    image: ImageU8
    crop_polygon: list[Point]
    dewarp_mesh_version: str
    contrast_method: str
    aspect_ratio_class: AspectRatioClass
    width: int
    height: int
