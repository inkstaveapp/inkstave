"""The image-cleanup pipeline (docs/image-pipeline.md).

Stages, in order: :func:`inkstave_processing.pipeline.geometry.geometric_correct`
(page detection + perspective correction + dewarping + cropping, combined --
see that module's docstring for why these four are one stage here rather than
three chained ones), :func:`inkstave_processing.pipeline.contrast.enhance_contrast`,
and :func:`inkstave_processing.pipeline.normalize.normalize_page`.
:func:`inkstave_processing.pipeline.run.process_page` runs all three in order.

Every stage is pure (image array in, new image array + metadata out, no I/O,
no global state) and independently unit-testable against synthetic fixtures
(:mod:`tests.pipeline.fixtures`) -- there is no real camera-captured test data
yet, since the capture flow that would produce it (ROADMAP.md M4's other
slices) doesn't exist.
"""
