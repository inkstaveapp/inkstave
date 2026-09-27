"""Page detection, perspective correction, dewarping and cropping, as one step.

`docs/image-pipeline.md` lists these as separate stages, but a 4-corner
perspective transform throws away the boundary's curvature that dewarping
needs, so curvature is corrected from the original detected boundary.

:func:`detect_page` finds the full page contour. :func:`geometric_correct`
fits a degree-2 curve to each edge and maps the four curves onto a flat
rectangle with a Coons patch (standard transfinite interpolation). With
straight edges this reduces to an ordinary perspective transform, so there is
no separate flat-page path.

Scope: this corrects a page bowing smoothly along its boundary (a book that
won't lie flat). It cannot see creases or folds inside the page, since those
don't show up on the boundary.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import numpy as np

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.cv_backend import NDArrayF32
from inkstave_processing.pipeline.types import GeometricCorrectionResult, ImageU8, Point

#: Stored as `processing.dewarpMeshVersion`. Bump it whenever the model would
#: produce different output for the same input, so reprocessing history stays
#: meaningful.
DEWARP_MODEL_VERSION = "coons-boundary-v1"

_BLUR_KERNEL = 5
_CANNY_LOW = 50.0
_CANNY_HIGH = 150.0
_APPROX_EPSILON_FRACTION = 0.02
#: Minimum fraction of the photo a detected page must cover, to reject noise
#: and shadows.
_MIN_CONTOUR_AREA_FRACTION = 0.1


@dataclass(frozen=True)
class PageDetection:
    """A detected page boundary: its full contour, and which points within it are the corners."""

    #: `(n, 2)` float32 points in OpenCV's contour order; the winding direction isn't
    #: guaranteed and nothing relies on it.
    contour: NDArrayF32
    #: Indices into `contour` for (top-left, top-right, bottom-right, bottom-left).
    corner_indices: tuple[int, int, int, int]

    @property
    def corners(self) -> tuple[Point, Point, Point, Point]:
        """The four corners as `(x, y)` points, in (TL, TR, BR, BL) order."""
        tl, tr, br, bl = (self.contour[i] for i in self.corner_indices)
        return (
            (float(tl[0]), float(tl[1])),
            (float(tr[0]), float(tr[1])),
            (float(br[0]), float(br[1])),
            (float(bl[0]), float(bl[1])),
        )


def detect_page(image: ImageU8) -> PageDetection | None:
    """Finds the page boundary in a photo, or `None` if no large enough
    quadrilateral stands out from the background (e.g. a blank image).
    """
    gray = cv_backend.to_grayscale(image)
    blurred = cv_backend.gaussian_blur(gray, _BLUR_KERNEL)
    edges = cv_backend.canny_edges(blurred, _CANNY_LOW, _CANNY_HIGH)
    contour = cv_backend.find_largest_contour(edges)
    if contour is None:
        return None

    image_area = image.shape[0] * image.shape[1]
    if cv_backend.contour_area(contour) < _MIN_CONTOUR_AREA_FRACTION * image_area:
        return None

    corner_indices = _find_corner_indices(contour)
    if corner_indices is None:
        return None
    return PageDetection(contour=contour, corner_indices=corner_indices)


def _find_corner_indices(contour: NDArrayF32) -> tuple[int, int, int, int] | None:
    """Simplifies `contour` to 4 corners and maps each back to its nearest real
    contour point, since later code slices the contour by index and
    `approxPolyDP`'s points aren't necessarily contour points. `None` unless it
    finds exactly 4 corners.
    """
    approx = cv_backend.approx_polygon(contour, _APPROX_EPSILON_FRACTION)
    if len(approx) != 4:
        return None

    indices: list[int] = []
    for corner in approx:
        distances = np.sum((contour - corner) ** 2, axis=1)
        indices.append(int(np.argmin(distances)))
    return _order_corners_tl_tr_br_bl(contour, (indices[0], indices[1], indices[2], indices[3]))


def _order_corners_tl_tr_br_bl(
    contour: NDArrayF32,
    indices: tuple[int, int, int, int],
) -> tuple[int, int, int, int]:
    """Orders 4 corner indices as (TL, TR, BR, BL) with the sum/difference trick
    (TL smallest x+y, BR largest, TR smallest y-x, BL largest). Assumes the page
    isn't rotated near 45 degrees.
    """
    points = [(i, contour[i]) for i in indices]
    tl = min(points, key=lambda p: p[1][0] + p[1][1])
    br = max(points, key=lambda p: p[1][0] + p[1][1])
    tr = min(points, key=lambda p: p[1][1] - p[1][0])
    bl = max(points, key=lambda p: p[1][1] - p[1][0])
    return (tl[0], tr[0], br[0], bl[0])


def _edge_between(contour: NDArrayF32, start_index: int, end_index: int) -> NDArrayF32:
    """Contour points from `start_index` to `end_index` inclusive, walking the
    shorter way round. The winding direction isn't guaranteed, and for a
    page-shaped contour the edge between adjacent corners is always the shorter
    path; walking forward could take the long way through the other corners.
    """
    forward = _wrapped_slice(contour, start_index, end_index)
    backward = _wrapped_slice(contour, end_index, start_index)[::-1]
    return forward if len(forward) <= len(backward) else backward


def _wrapped_slice(contour: NDArrayF32, start_index: int, end_index: int) -> NDArrayF32:
    """`contour[start_index:end_index+1]`, wrapping around the end of the array."""
    if start_index <= end_index:
        return contour[start_index : end_index + 1]
    return np.concatenate([contour[start_index:], contour[: end_index + 1]])


def _fit_edge_curve(edge_points: NDArrayF32) -> Callable[[NDArrayF32], NDArrayF32]:
    """Fits a degree-2 polynomial per coordinate over `t` in `[0, 1]` along the
    edge and returns the curve as a function of `t`. Degree 2 is the lowest that
    can represent a bow; higher degrees start fitting contour noise.
    """
    point_count = len(edge_points)
    if point_count < 3:
        # Too few points to fit a curve: use the straight line between the endpoints.
        start, end = edge_points[0], edge_points[-1]

        def straight_line(t: NDArrayF32) -> NDArrayF32:
            line = start[None, :] + t[:, None] * (end - start)[None, :]
            result: NDArrayF32 = line.astype(np.float32)
            return result

        return straight_line

    t = np.linspace(0.0, 1.0, point_count, dtype=np.float64)
    coeffs_x = np.polyfit(t, edge_points[:, 0].astype(np.float64), 2)
    coeffs_y = np.polyfit(t, edge_points[:, 1].astype(np.float64), 2)

    def fitted_curve(t_query: NDArrayF32) -> NDArrayF32:
        x = np.polyval(coeffs_x, t_query)
        y = np.polyval(coeffs_y, t_query)
        return np.stack([x, y], axis=-1).astype(np.float32)

    return fitted_curve


def geometric_correct(image: ImageU8, detection: PageDetection) -> GeometricCorrectionResult:
    """Maps the detected (possibly curved) page onto a flat rectangle. The output
    is already cropped to the page, since the rectangle is the page boundary.
    """
    tl_index, tr_index, br_index, bl_index = detection.corner_indices
    contour = detection.contour

    top_curve = _fit_edge_curve(_edge_between(contour, tl_index, tr_index))  # u: 0->TL, 1->TR
    bottom_curve = _fit_edge_curve(_edge_between(contour, bl_index, br_index))  # u: 0->BL, 1->BR
    left_curve = _fit_edge_curve(_edge_between(contour, tl_index, bl_index))  # v: 0->TL, 1->BL
    right_curve = _fit_edge_curve(_edge_between(contour, tr_index, br_index))  # v: 0->TR, 1->BR

    top_left, top_right, bottom_right, bottom_left = detection.corners
    target_width, target_height = _target_size(top_left, top_right, bottom_right, bottom_left)

    u = np.linspace(0.0, 1.0, target_width, dtype=np.float32)
    v = np.linspace(0.0, 1.0, target_height, dtype=np.float32)
    uu, vv = np.meshgrid(u, v)  # each (target_height, target_width)

    top_u = top_curve(u)  # (target_width, 2)
    bottom_u = bottom_curve(u)
    left_v = left_curve(v)  # (target_height, 2)
    right_v = right_curve(v)

    top_left_arr = np.array(top_left, dtype=np.float32)
    top_right_arr = np.array(top_right, dtype=np.float32)
    bottom_left_arr = np.array(bottom_left, dtype=np.float32)
    bottom_right_arr = np.array(bottom_right, dtype=np.float32)

    # Coons patch over the whole grid:
    # S(u,v) = (1-v)*Top(u) + v*Bottom(u) + (1-u)*Left(v) + u*Right(v)
    #          - [(1-u)(1-v)*TL + u(1-v)*TR + (1-u)v*BL + uv*BR]
    ruled_u = (1 - vv)[..., None] * top_u[None, :, :] + vv[..., None] * bottom_u[None, :, :]
    ruled_v = (1 - uu)[..., None] * left_v[:, None, :] + uu[..., None] * right_v[:, None, :]
    bilinear_corners = (
        (1 - uu)[..., None] * (1 - vv)[..., None] * top_left_arr
        + uu[..., None] * (1 - vv)[..., None] * top_right_arr
        + (1 - uu)[..., None] * vv[..., None] * bottom_left_arr
        + uu[..., None] * vv[..., None] * bottom_right_arr
    )
    source_points = ruled_u + ruled_v - bilinear_corners  # (target_height, target_width, 2)

    map_x = source_points[..., 0].astype(np.float32)
    map_y = source_points[..., 1].astype(np.float32)
    corrected = cv_backend.remap_image(image, map_x, map_y)

    return GeometricCorrectionResult(
        image=corrected,
        crop_polygon=[top_left, top_right, bottom_right, bottom_left],
        dewarp_mesh_version=DEWARP_MODEL_VERSION,
    )


def _target_size(
    top_left: Point,
    top_right: Point,
    bottom_right: Point,
    bottom_left: Point,
) -> tuple[int, int]:
    """Output size from the average of each axis's two corner-to-corner distances;
    close enough to arc length for choosing a resolution."""

    def distance(a: Point, b: Point) -> float:
        return float(np.hypot(a[0] - b[0], a[1] - b[1]))

    width = max(1, round((distance(top_left, top_right) + distance(bottom_left, bottom_right)) / 2))
    height = max(
        1,
        round((distance(top_left, bottom_left) + distance(top_right, bottom_right)) / 2),
    )
    return width, height
