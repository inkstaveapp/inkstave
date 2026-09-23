"""A fully-typed wrapper around every OpenCV call the pipeline uses.

`docs/coding-standards.md` requires strict typing everywhere, including
Python, with no `Any` used to dodge modeling a type. `opencv-python-headless`
(pinned in `pyproject.toml`; see `NOTICE.md`) ships real `.pyi` stubs, which
is better than historical versions of this package -- but they're not
precise: OpenCV's C++ API is heavily overloaded and dtype-polymorphic in ways
the stubs approximate with wide unions (`ndarray[Any, dtype[integer[Any] |
floating[Any]]]` and similar) rather than pinning down, for instance, "this
function takes and returns `uint8`" the way this pipeline actually always
uses it.

Every function below has a precise, hand-written signature (using
`inkstave_processing.pipeline.types.ImageU8` and friends) and does whatever
narrow `.astype()`/`cast()` is needed to make good on it -- callers elsewhere
in the pipeline never import `cv2` directly or see its looser inferred
types. Where OpenCV's own runtime behavior (not just its stub types) is what
we're relying on -- e.g. that `cv2.findContours` really does return
integer-coordinate points -- that's asserted via `.astype()` rather than
trusted blindly, so a future OpenCV version behaving differently would be
caught by a `ValueError`/shape mismatch in a test, not silently produce
wrong output.
"""

from __future__ import annotations

import cv2
import numpy as np
from numpy.typing import NDArray

from inkstave_processing.pipeline.types import ImageU8

#: A separate alias from `ImageU8` (both ultimately back onto `numpy.float32`/`uint8` arrays)
#: purely for readability at call sites that deal in point coordinates rather than pixel
#: intensities -- contours, corners, remap grids.
NDArrayF32 = NDArray[np.float32]


def to_grayscale(image: ImageU8) -> ImageU8:
    """Converts a `(h, w, 3)` BGR image to `(h, w)` grayscale. A no-op copy if already grayscale."""
    if image.ndim == 2:
        return image.copy()
    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY).astype(np.uint8)


def gaussian_blur(image: ImageU8, kernel_size: int) -> ImageU8:
    """Blurs `image` with a `kernel_size` x `kernel_size` Gaussian kernel (odd, positive)."""
    if kernel_size <= 0 or kernel_size % 2 == 0:
        raise ValueError(f"kernel_size must be a positive odd integer, got {kernel_size}")
    return cv2.GaussianBlur(image, (kernel_size, kernel_size), 0).astype(np.uint8)


def canny_edges(image: ImageU8, low_threshold: float, high_threshold: float) -> ImageU8:
    """Canny edge detection on a grayscale `image`; output is a `(h, w)` binary (0/255) edge map."""
    return cv2.Canny(image, low_threshold, high_threshold).astype(np.uint8)


def find_largest_contour(edge_image: ImageU8) -> NDArrayF32 | None:
    """The largest external contour in a binary edge map, as an `(n, 2)` float32 array of `(x, y)`
    points, or `None` if the image has no contours at all (a blank/uniform input -- degenerate,
    not an error)."""
    contours, _ = cv2.findContours(edge_image, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None
    largest = max(contours, key=cv2.contourArea)
    return largest.reshape(-1, 2).astype(np.float32)


def contour_area(contour: NDArrayF32) -> float:
    """The enclosed area of `contour` (an `(n, 2)` point array), in pixels^2."""
    return float(cv2.contourArea(contour.astype(np.float32)))


def approx_polygon(contour: NDArrayF32, epsilon_fraction: float) -> NDArrayF32:
    """`contour` simplified via Douglas-Peucker to (ideally) its corner points. `epsilon_fraction`
    is the simplification tolerance as a fraction of the contour's perimeter -- larger merges more
    points away."""
    perimeter = cv2.arcLength(contour.astype(np.float32), closed=True)
    epsilon = epsilon_fraction * perimeter
    approx = cv2.approxPolyDP(contour.astype(np.float32), epsilon, closed=True)
    return approx.reshape(-1, 2).astype(np.float32)


def remap_image(
    image: ImageU8,
    map_x: NDArrayF32,
    map_y: NDArrayF32,
) -> ImageU8:
    """Resamples `image` at `map_x`/`map_y` (each `(out_h, out_w)`, giving each output pixel's
    *source* coordinate) via bilinear interpolation -- the primitive
    `geometry.geometric_correct`'s mesh warp is built on."""
    return cv2.remap(
        image,
        map_x,
        map_y,
        interpolation=cv2.INTER_LINEAR,
        borderValue=(255, 255, 255),
    ).astype(np.uint8)


def apply_clahe(image: ImageU8, clip_limit: float, tile_grid_size: int) -> ImageU8:
    """Contrast-Limited Adaptive Histogram Equalization on a grayscale `image` -- local contrast
    enhancement that (unlike global histogram equalization) doesn't blow out unevenly-lit photos
    into uniform noise."""
    clahe = cv2.createCLAHE(clipLimit=clip_limit, tileGridSize=(tile_grid_size, tile_grid_size))
    return clahe.apply(image).astype(np.uint8)


def adaptive_threshold(image: ImageU8, block_size: int, constant: float) -> ImageU8:
    """Per-region binarization of a grayscale `image` (0 or 255 per pixel) -- Gaussian-weighted
    local mean minus `constant`, so it copes with uneven lighting far better than one global
    threshold would."""
    if block_size <= 1 or block_size % 2 == 0:
        raise ValueError(f"block_size must be an odd integer > 1, got {block_size}")
    return cv2.adaptiveThreshold(
        image,
        255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        block_size,
        constant,
    ).astype(np.uint8)


def resize_image(image: ImageU8, width: int, height: int) -> ImageU8:
    """Resizes `image` to exactly `(height, width)` via area-based interpolation (the appropriate
    choice for downscaling a photo; for the modest upscales this pipeline does, it's still a
    reasonable, artifact-free default)."""
    return cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA).astype(np.uint8)


def decode_image(encoded_bytes: bytes) -> ImageU8:
    """Decodes `encoded_bytes` (a whole PNG/JPEG/etc. file's bytes, as received over HTTP) into a
    `(h, w, 3)` BGR `ImageU8` -- the same format every other function in this module produces and
    consumes. Raises `ValueError` if the bytes aren't a decodable image, rather than returning
    `None`/a malformed array for a caller to trip over later."""
    buffer = np.frombuffer(encoded_bytes, dtype=np.uint8)
    decoded = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if decoded is None:
        raise ValueError("could not decode image bytes -- not a recognized image format")
    return decoded.astype(np.uint8)


def encode_png(image: ImageU8) -> bytes:
    """Encodes `image` (grayscale or BGR) as PNG file bytes -- the inverse of `decode_image` for
    the output side, and the same codec `docs/format-spec.md` specifies for `pages/<id>.png`."""
    ok, buffer = cv2.imencode(".png", image)
    if not ok:
        raise ValueError("failed to encode image as PNG")
    return bytes(buffer)


def pad_image(
    image: ImageU8,
    top: int,
    bottom: int,
    left: int,
    right: int,
) -> ImageU8:
    """Pads `image` with white borders of the given pixel widths (letterboxing, not stretching --
    see `normalize.normalize_page`)."""
    return cv2.copyMakeBorder(
        image,
        top,
        bottom,
        left,
        right,
        cv2.BORDER_CONSTANT,
        value=(255, 255, 255) if image.ndim == 3 else 255,
    ).astype(np.uint8)
