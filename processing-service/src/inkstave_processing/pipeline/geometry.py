"""Page detection, perspective correction, dewarping, and cropping -- combined.

`docs/image-pipeline.md` lists these as three separate stages (1: detect +
perspective-correct, 2: dewarp, 3: crop). Implemented here as one combined
operation instead, for a concrete reason: a naive flat 4-corner perspective
transform (stage 1 alone) necessarily discards the page boundary's actual
curvature -- it only looks at 4 simplified corner points -- before stage 2
could ever see it to correct for it. Chaining "flatten, then dewarp" in that
order throws away exactly the information dewarping needs; correcting for
curvature has to start from the *original* detected boundary, not from an
already-flattened image.

Instead: :func:`detect_page` finds the raw page-boundary contour (not just 4
corners) in the original photo. :func:`geometric_correct` fits a smooth curve
to each of the four edges between corners (a degree-2 polynomial per edge)
and maps the resulting four boundary curves onto a flat target rectangle via
a *Coons patch* -- a standard bilinear boundary-interpolation surface (see
any computational-geometry treatment of transfinite interpolation; this
isn't a novel technique invented for this pipeline). When the detected edges
are already straight (a flat photo, no curl), the fitted curves are very
close to straight lines and the Coons patch reduces to something very close
to an ordinary 4-point perspective transform -- so this one operation
subsumes the flat case, not just the curved one; there's no separate
"straight perspective transform" code path.

**Honest scope**, per this pass's own instructions to scope dewarping
honestly rather than overclaim: this models one specific, common failure
mode -- a page bowing/curling smoothly along its own boundary
(`docs/image-pipeline.md`'s "book/binder won't lie flat"). It does **not**
correct local creases, folds, or crumpling that the boundary curve doesn't
capture -- a fold in the middle of an otherwise-flat page wouldn't show up
on the boundary at all, and this model has no way to detect or correct it.
A more general solution (per-pixel structure estimation, a learned dewarping
model, etc.) is real, larger future work if this scope proves insufficient
in practice; not attempted here.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import numpy as np

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.cv_backend import NDArrayF32
from inkstave_processing.pipeline.types import GeometricCorrectionResult, ImageU8, Point

#: Identifies the correction model in `docs/format-spec.md`'s
#: `processing.dewarpMeshVersion`. Bump this string whenever the *model*
#: changes in a way that would produce different output for the same input
#: (not necessarily every time this module's code changes at all), so a
#: stored page's reprocessing history stays meaningful
#: (`docs/image-pipeline.md`'s "Reprocessing" section).
DEWARP_MODEL_VERSION = "coons-boundary-v1"

_BLUR_KERNEL = 5
_CANNY_LOW = 50.0
_CANNY_HIGH = 150.0
_APPROX_EPSILON_FRACTION = 0.02
#: A detected page must cover at least this fraction of the photo's area to
#: be accepted -- rejects spurious small contours (noise, a shadow) rather
#: than confidently "detecting" garbage.
_MIN_CONTOUR_AREA_FRACTION = 0.1


@dataclass(frozen=True)
class PageDetection:
    """A detected page boundary: its full contour, and which points within it are the corners."""

    #: `(n, 2)` float32 points, in the order OpenCV's contour-following produced them (one
    #: consistent direction around the loop; not guaranteed clockwise vs counterclockwise, and
    #: nothing here relies on which).
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
    """Finds the page boundary in `image` (a full, unsimplified photo of a page against some
    background), or `None` if no sufficiently large quadrilateral region is found -- e.g. a
    blank/uniform image, or a photo where the page doesn't stand out from its background. That
    second case is a real limitation of contour-based detection generally, not a bug to fix here.
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
    """Simplifies `contour` to (ideally) 4 points via Douglas-Peucker, then maps each simplified
    corner back to its nearest point in the *original* contour -- `approxPolyDP`'s output points
    aren't guaranteed to be a subset of the input contour's points, and later code needs real
    indices into `contour` to slice it into edges. Returns `None` if simplification doesn't land
    on exactly 4 corners: a real limitation (a noisy or non-quadrilateral detected boundary), not
    silently guessed around.
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
    """Re-orders 4 contour-point indices into (top-left, top-right, bottom-right, bottom-left) via
    the standard sum/difference trick: TL has the smallest x+y, BR the largest x+y, TR the
    smallest y-x, BL the largest y-x. Assumes a roughly axis-aligned photo (the page isn't rotated
    close to 45 degrees) -- true for the "photograph a page held roughly upright" use case this
    pipeline targets.
    """
    points = [(i, contour[i]) for i in indices]
    tl = min(points, key=lambda p: p[1][0] + p[1][1])
    br = max(points, key=lambda p: p[1][0] + p[1][1])
    tr = min(points, key=lambda p: p[1][1] - p[1][0])
    bl = max(points, key=lambda p: p[1][1] - p[1][0])
    return (tl[0], tr[0], br[0], bl[0])


def _edge_between(contour: NDArrayF32, start_index: int, end_index: int) -> NDArrayF32:
    """Points from `contour[start_index]` to `contour[end_index]` inclusive, walking whichever
    direction around the contour's loop is *shorter* -- OpenCV doesn't guarantee a particular
    winding direction, so "always walk forward" would silently grab the long way around (through
    the other two corners) whenever the contour happens to wind the other way, corrupting the
    curve fit with three edges' worth of points instead of one. For any reasonably page-shaped
    (convex-ish) contour, the true edge between two adjacent corners is always the shorter of the
    two possible paths, regardless of winding direction -- so picking the shorter one is the
    correct general rule, not a heuristic that happens to work for one direction.
    """
    forward = _wrapped_slice(contour, start_index, end_index)
    backward = _wrapped_slice(contour, end_index, start_index)[::-1]
    return forward if len(forward) <= len(backward) else backward


def _wrapped_slice(contour: NDArrayF32, start_index: int, end_index: int) -> NDArrayF32:
    """`contour[start_index:end_index+1]`, wrapping around the array's end if `end_index <
    start_index`, walking forward through the contour's own point order."""
    if start_index <= end_index:
        return contour[start_index : end_index + 1]
    return np.concatenate([contour[start_index:], contour[: end_index + 1]])


def _fit_edge_curve(edge_points: NDArrayF32) -> Callable[[NDArrayF32], NDArrayF32]:
    """Fits a degree-2 polynomial to each coordinate of `edge_points`, as a function of a
    `[0, 1]`-normalized parameter walking from the edge's start point to its end point, and
    returns a function evaluating that fitted curve at arbitrary parameter values.

    Degree 2 (not higher) is deliberate: it's the lowest degree that can represent a smooth
    bow/curl at all, and a higher degree would start fitting noise in the detected contour rather
    than the page's actual shape.
    """
    point_count = len(edge_points)
    if point_count < 3:
        # Too few points to fit a meaningful curve -- fall back to a straight line between the
        # two endpoints. This is the *correct* degenerate behavior (no curvature to model), not
        # a workaround: a 2-point "curve" has no meaningful bow to fit in the first place.
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
    """Maps the (possibly curved) page boundary `detection` describes onto a flat rectangle, via
    the Coons-patch boundary interpolation this module's docstring describes. The output image
    *is* the cropped page -- there's no separate crop step, since the target rectangle's extent
    is exactly the page boundary by construction.
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

    # The Coons patch itself, vectorized over the whole (target_height, target_width) grid at
    # once: S(u,v) = (1-v)*Top(u) + v*Bottom(u) + (1-u)*Left(v) + u*Right(v)
    #               - [(1-u)(1-v)*TL + u(1-v)*TR + (1-u)v*BL + uv*BR]
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
    """The output rectangle's size, from the average of each axis's two edge lengths -- the
    standard sizing heuristic for a 4-point perspective transform, generalized here to curved
    edges via their straight-line corner-to-corner distance (a fine approximation for choosing an
    output *resolution*; it doesn't need to be exact arc length)."""

    def distance(a: Point, b: Point) -> float:
        return float(np.hypot(a[0] - b[0], a[1] - b[1]))

    width = max(1, round((distance(top_left, top_right) + distance(bottom_left, bottom_right)) / 2))
    height = max(
        1,
        round((distance(top_left, bottom_left) + distance(top_right, bottom_right)) / 2),
    )
    return width, height
