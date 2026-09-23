"""Integration test for `inkstave_processing.pipeline.run.process_page` -- the full pipeline,
end to end, on a synthetic photo."""

from __future__ import annotations

import numpy as np
import pytest

from inkstave_processing.pipeline.contrast import CONTRAST_METHOD
from inkstave_processing.pipeline.geometry import DEWARP_MODEL_VERSION
from inkstave_processing.pipeline.run import PageDetectionFailed, process_page
from inkstave_processing.pipeline.types import ImageU8
from tests.pipeline.fixtures import apply_horizontal_bow, compose_photo, make_flat_page


def test_process_page_runs_all_stages_on_a_bowed_synthetic_photo() -> None:
    page = make_flat_page(width=600, height=800)
    photo, _ = compose_photo(page)
    bowed_photo = apply_horizontal_bow(photo, amplitude_px=25.0)

    result = process_page(bowed_photo)

    assert result.image.ndim == 2  # contrast cleanup's grayscale output, carried through
    assert result.width > 0 and result.height > 0
    assert result.image.shape == (result.height, result.width)
    assert len(result.crop_polygon) == 4
    assert result.dewarp_mesh_version == DEWARP_MODEL_VERSION
    assert result.contrast_method == CONTRAST_METHOD
    assert result.aspect_ratio_class in ("a4", "letter", "custom")


def test_process_page_raises_a_specific_exception_when_no_page_is_found() -> None:
    blank: ImageU8 = np.full((300, 300, 3), 128, dtype=np.uint8)

    with pytest.raises(PageDetectionFailed):
        process_page(blank)


def test_process_page_contrast_strength_is_configurable() -> None:
    # Confirms contrast_strength actually flows end-to-end through the full pipeline and changes
    # the result. Deliberately not a unique-value-count comparison (unlike
    # test_contrast.py's more targeted tests): normalize_page's resize step (INTER_AREA
    # interpolation) introduces its own edge anti-aliasing downstream of contrast entirely, which
    # dominates a whole-image tonal-range count regardless of the contrast method underneath it --
    # a fragile, indirect signal at this integration level. Whether/how strength changes contrast
    # specifically is already covered precisely, in isolation, by test_contrast.py.
    page = make_flat_page(width=400, height=500)
    photo, _ = compose_photo(page, canvas_width=600, canvas_height=700)

    gentle = process_page(photo, contrast_strength=0.0)
    full = process_page(photo, contrast_strength=1.0)

    assert not np.array_equal(gentle.image, full.image)
