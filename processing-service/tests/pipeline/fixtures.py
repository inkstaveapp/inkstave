"""Synthetic test fixtures for the pipeline (`inkstave_processing.pipeline`).

There's no real camera-captured test data yet -- the capture flow that would
produce it is a different, not-yet-built slice of `ROADMAP.md` M4. These
fixtures generate deterministic synthetic "photos" instead, in the same
spirit as the client side generating synthetic PDFs with PDFBox rather than
needing real files (`client/shared/src/desktopTest/.../LibraryImporterEndToEndTest.kt`).
"""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw

from inkstave_processing.pipeline import cv_backend
from inkstave_processing.pipeline.types import ImageU8, Point

#: Row y-positions (in the flat page returned by `make_flat_page`'s default size) where a rule
#: line (a stand-in for a staff line) is drawn -- exposed so tests can check straightness at a
#: known expected location rather than re-deriving it.
RULE_LINE_ROWS = list(range(70, 780, 70))

#: Column x-positions (in `make_flat_page`'s default size) where a vertical "barline" is drawn --
#: exposed for the same reason as `RULE_LINE_ROWS`. Content that varies with *x* (rather than
#: being constant across it, like the horizontal rule lines) is what `apply_horizontal_bow`'s
#: per-row shift actually visibly bends -- see that function's doc.
BARLINE_COLUMNS = [120, 300, 480]


def make_flat_page(width: int = 600, height: int = 800, margin: int = 20) -> ImageU8:
    """A synthetic flat page: a white rectangle with a border, evenly-spaced horizontal rule lines
    (stand-ins for staff lines/text rows), and a few vertical barlines -- enough visual structure
    for contour detection (the border), for testing corrections that bend horizontal content
    (`RULE_LINE_ROWS`), and for testing corrections that bend vertical content (`BARLINE_COLUMNS`)
    -- without needing anything resembling real sheet music. Returned in BGR (`cv2`'s convention,
    matching every other `ImageU8` this package produces)."""
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
    """Places `page` centered on a larger plain-background canvas -- simulating a page photographed
    against a contrasting surface. Returns the composed photo and the page's four corners
    `[TL, TR, BR, BL]` within it, the ground truth `detect_page`'s output is checked against."""
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
    """Displaces every row of `image` horizontally by `amplitude_px * sin(pi * y / height)` --
    zero at the top and bottom, maximal at the vertical midline. Simulates a page curling
    sideways along its own vertical axis (e.g. a book spine holding the center while the left/
    right edges lift) -- `docs/image-pipeline.md`'s "book/binder won't lie flat" case,
    specifically the axis `geometry.geometric_correct`'s boundary-curve fit is designed to
    correct for (see that module's docstring)."""
    height, width = image.shape[:2]
    y_indices = np.arange(height, dtype=np.float32)
    dx = amplitude_px * np.sin(np.pi * y_indices / height)
    map_x = np.tile(np.arange(width, dtype=np.float32), (height, 1)) - dx[:, None]
    map_y = np.tile(y_indices[:, None], (1, width)).astype(np.float32)
    return cv_backend.remap_image(image, map_x, map_y)
