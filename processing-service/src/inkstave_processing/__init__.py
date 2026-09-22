"""Inkstave's desktop-side image processing & OCR pipeline.

See ``docs/image-pipeline.md`` in the repository root for the pipeline
stages this service will run (perspective correction, dewarping, crop,
contrast/B&W cleanup, aspect-ratio normalization, OCR metadata extraction)
-- none of that is implemented yet (M0 scaffolding only implements the
service shell and its health check). The service is desktop-only, binds to
loopback only, and is never exposed on the network -- see
``docs/image-pipeline.md``'s "Service interface" section.
"""
