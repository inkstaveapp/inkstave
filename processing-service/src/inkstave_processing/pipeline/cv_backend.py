"""Typed wrappers for every OpenCV call the pipeline uses.

OpenCV's stubs use wide dtype unions, so each wrapper gives a precise
signature and converts with `.astype()`. The rest of the pipeline never
imports `cv2`, and if OpenCV's runtime behaviour changed, a test would fail
on a dtype or shape mismatch rather than produce wrong output silently.
"""

from __future__ import annotations

import cv2
import numpy as np
from numpy.typing import NDArray

from inkstave_processing.pipeline.types import ImageU8

#: Float32 arrays of point coordinates (contours, corners, remap grids).
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
    """The largest external contour in an edge map as `(n, 2)` float32 points, or
    `None` if there are none (e.g. a blank image)."""
    contours, _ = cv2.findContours(edge_image, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None
    largest = max(contours, key=cv2.contourArea)
    return largest.reshape(-1, 2).astype(np.float32)


def contour_area(contour: NDArrayF32) -> float:
    """The enclosed area of `contour` (an `(n, 2)` point array), in pixels^2."""
    return float(cv2.contourArea(contour.astype(np.float32)))


def approx_polygon(contour: NDArrayF32, epsilon_fraction: float) -> NDArrayF32:
    """`contour` simplified with Douglas-Peucker; `epsilon_fraction` is the
    tolerance as a fraction of the perimeter."""
    perimeter = cv2.arcLength(contour.astype(np.float32), closed=True)
    epsilon = epsilon_fraction * perimeter
    approx = cv2.approxPolyDP(contour.astype(np.float32), epsilon, closed=True)
    return approx.reshape(-1, 2).astype(np.float32)


def remap_image(
    image: ImageU8,
    map_x: NDArrayF32,
    map_y: NDArrayF32,
) -> ImageU8:
    """Bilinearly resamples `image`; `map_x`/`map_y` give each output pixel's
    source coordinate. Out-of-range areas are white."""
    return cv2.remap(
        image,
        map_x,
        map_y,
        interpolation=cv2.INTER_LINEAR,
        borderValue=(255, 255, 255),
    ).astype(np.uint8)


def apply_clahe(image: ImageU8, clip_limit: float, tile_grid_size: int) -> ImageU8:
    """CLAHE on a grayscale image: local contrast enhancement that, unlike global
    equalisation, doesn't blow unevenly lit photos out into noise."""
    clahe = cv2.createCLAHE(clipLimit=clip_limit, tileGridSize=(tile_grid_size, tile_grid_size))
    return clahe.apply(image).astype(np.uint8)


def adaptive_threshold(image: ImageU8, block_size: int, constant: float) -> ImageU8:
    """Binarises a grayscale image against a Gaussian-weighted local mean minus
    `constant`, which copes with uneven lighting better than a global threshold."""
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
    """Resizes `image` to `(height, width)` with area interpolation, best for
    downscaling and artefact-free for small upscales."""
    return cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA).astype(np.uint8)


def decode_image(encoded_bytes: bytes) -> ImageU8:
    """Decodes image file bytes (PNG, JPEG, ...) to a BGR `ImageU8`. Raises
    `ValueError` if the bytes aren't a decodable image."""
    buffer = np.frombuffer(encoded_bytes, dtype=np.uint8)
    decoded = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if decoded is None:
        raise ValueError("could not decode image bytes -- not a recognized image format")
    return decoded.astype(np.uint8)


def encode_png(image: ImageU8) -> bytes:
    """Encodes a grayscale or BGR image as PNG bytes (the `.smpk` page format)."""
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
    """Pads `image` with white borders of the given widths (for letterboxing)."""
    return cv2.copyMakeBorder(
        image,
        top,
        bottom,
        left,
        right,
        cv2.BORDER_CONSTANT,
        value=(255, 255, 255) if image.ndim == 3 else 255,
    ).astype(np.uint8)
