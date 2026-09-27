"""The image-cleanup pipeline (docs/image-pipeline.md).

Stages: `geometry` (detection, perspective, dewarp and crop in one step),
`contrast`, `normalize`, `ocr`; `run.process_page` runs them in order. Each
stage is pure (arrays in, arrays and metadata out, no I/O), so it can be
tested against synthetic fixtures.
"""
