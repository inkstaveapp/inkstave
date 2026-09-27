"""Deterministic synthetic page "photos" for the pipeline tests."""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.types import ImageU8, Point

#: Rows of the horizontal rule lines (staff-line stand-ins) in `make_flat_page`.
RULE_LINE_ROWS = list(range(70, 780, 70))

#: Columns of the vertical barlines in `make_flat_page`; these are what
#: `apply_horizontal_bow` visibly bends.
BARLINE_COLUMNS = [120, 300, 480]


def make_flat_page(width: int = 600, height: int = 800, margin: int = 20) -> ImageU8:
    """A white BGR page with a border (for detection), horizontal rule lines and
    vertical barlines (for checking straightness)."""
    image = Image.new("RGB", (width, height), color=(255, 255, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle([margin, margin, width - margin, height - margin], outline=(0, 0, 0), width=3)
    for y in RULE_LINE_ROWS:
        if margin < y < height - margin:
            draw.line([(margin + 15, y), (width - margin - 15, y)], fill=(0, 0, 0), width=3)
    for x in BARLINE_COLUMNS:
        draw.line([(x, margin + 10), (x, height - margin - 10)], fill=(0, 0, 0), width=3)
    rgb = np.array(image, dtype=np.uint8)
    return np.ascontiguousarray(rgb[:, :, ::-1])


def compose_photo(
    page: ImageU8,
    canvas_width: int = 900,
    canvas_height: int = 1100,
    background_color: tuple[int, int, int] = (60, 60, 60),
) -> tuple[ImageU8, list[Point]]:
    """Centres `page` on a darker canvas, like a photo of a page on a table.
    Returns the photo and the true page corners `[TL, TR, BR, BL]`."""
    canvas = np.full((canvas_height, canvas_width, 3), background_color, dtype=np.uint8)
    page_height, page_width = page.shape[:2]
    offset_x = (canvas_width - page_width) // 2
    offset_y = (canvas_height - page_height) // 2
    canvas[offset_y : offset_y + page_height, offset_x : offset_x + page_width] = page
    corners: list[Point] = [
        (float(offset_x), float(offset_y)),
        (float(offset_x + page_width), float(offset_y)),
        (float(offset_x + page_width), float(offset_y + page_height)),
        (float(offset_x), float(offset_y + page_height)),
    ]
    return canvas, corners


def apply_horizontal_bow(image: ImageU8, amplitude_px: float) -> ImageU8:
    """Shifts each row sideways by `amplitude_px * sin(pi * y / height)`, simulating
    a page bowing like a book that won't lie flat."""
    height, width = image.shape[:2]
    y_indices = np.arange(height, dtype=np.float32)
    dx = amplitude_px * np.sin(np.pi * y_indices / height)
    map_x = np.tile(np.arange(width, dtype=np.float32), (height, 1)) - dx[:, None]
    map_y = np.tile(y_indices[:, None], (1, width)).astype(np.float32)
    return cv_backend.remap_image(image, map_x, map_y)
