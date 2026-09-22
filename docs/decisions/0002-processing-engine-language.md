# ADR-0002: Desktop processing pipeline built in Python

**Status:** accepted
**Date:** 2026-09-23

## Context

The desktop-side pipeline (perspective correction, dewarping/flattening,
crop, contrast/B&W cleanup, aspect-ratio normalization, OCR metadata
extraction — see `docs/image-pipeline.md`) needs a mature computer-vision
and OCR ecosystem, since this is the most algorithmically demanding part of
the project and the area most likely to need iteration against real-world
photos.

## Options considered

- **Python** — richest open-source ecosystem for exactly this problem:
  OpenCV, Tesseract (via `pytesseract`), scikit-image, and the broader
  document-dewarping research community publishes primarily in Python. Fast
  to prototype and iterate. Runs as a local sidecar process the client talks
  to over a local API, so its dependency footprint stays isolated from the
  Kotlin build.
- **Rust** — strong performance and memory safety, growing `image`/
  `imageproc` crates, compiles to a single fast binary with no runtime
  dependency. OCR bindings and dewarping algorithm availability are
  meaningfully thinner than Python's.
- **JVM (Kotlin, OpenCV Java bindings + Tess4J)** — single language/runtime
  across the whole project, simpler packaging story. The Java CV/OCR
  ecosystem is thinner and less actively maintained than Python's native
  ecosystem; several relevant open-source dewarping tools have no JVM
  binding at all.

## Decision

Python, run as a local sidecar service (`processing-service/`) that the
desktop client (Kotlin) calls over a local API (loopback HTTP/WebSocket —
see `docs/image-pipeline.md`).

## Consequences

- The client codebase stays JVM/Kotlin-only; Python dependencies never
  enter the Android build (the service is desktop-only, matching where the
  "extra horsepower" requirement actually lives).
- Introduces a second language ecosystem to maintain, document, and
  license-audit (`NOTICE.md` gets a dedicated section for it).
- The client/service boundary must be a stable, versioned contract (schemas
  in `format/`) since the two are built and released somewhat
  independently.
- The processing service is not expected to run on Android; anything that
  must work offline on a phone alone (e.g. viewing/annotating an
  already-processed score) must not depend on it.
