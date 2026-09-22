# Roadmap

Milestones are ordered but not strictly sequential — some can overlap.
Nothing here is a promise of a date, just of sequence.

## M0 — Project scaffolding (current)

- [x] Plan, architecture docs, ADRs, agent definitions (this commit).
- [ ] Scaffold the Kotlin Multiplatform project (`client/`): `shared`,
      `androidApp`, `desktopApp` modules, basic CI-able build.
- [ ] Scaffold the Python processing service (`processing-service/`):
      project layout, dependency management, a trivial health-check entry
      point.
- [ ] Scaffold `format/`: JSON Schema for `.smpk` manifest/annotations, plus
      a minimal reader/writer in both Kotlin and Python for round-trip
      testing.
- [ ] Set up local dev tooling (formatters, linters, pre-commit) for both
      languages.
- [ ] Create the remote repository and push, once the above builds cleanly.

## M1 — Single-device viewer (Android + Linux)

- [ ] Import a score from a single PDF.
- [ ] Import a score from a set of images.
- [ ] Render pages, swipe/tap/keyboard page turning.
- [ ] Basic library screen (list of imported scores with title/composer).
- [ ] `.smpk` read/write for this minimal case (no annotations yet).

## M2 — Annotation engine

- [ ] Freehand drawing/highlighting layer per page.
- [ ] Stamped symbol library (common notation marks) with placement,
      resize, and per-instance styling.
- [ ] Text annotations.
- [ ] Undo/redo, annotation layer persisted in `.smpk`.

## M3 — Pedal & performance mode

- [ ] Bluetooth/USB page-turner pedal support on Android.
- [ ] Pedal support on Linux desktop.
- [ ] Performance mode: screen-on lock, minimal chrome, configurable turn
      gesture-to-pedal mapping, optional half-page/overlap turn behavior.

## M4 — Capture & processing pipeline

- [ ] In-app camera capture flow (Android).
- [ ] LAN device discovery and pairing (see `docs/sync-protocol.md`).
- [ ] Live transfer of captured photos from phone to desktop
      (capture session).
- [ ] Python pipeline: perspective correction, dewarping, crop to page
      bounds, contrast/B&W cleanup, aspect-ratio normalization.
- [ ] OCR pass for title/subtitle/composer/arranger/other metadata, surfaced
      to the user for confirmation/edit before committing to the library.
- [ ] Processed pages + metadata written back into a `.smpk`, synced back to
      the originating device(s).

## M5 — Multi-part scores

- [ ] Group several captured/imported parts under one logical score entry.
- [ ] Part-switching UI within a score.
- [ ] Format support for multi-part `.smpk` bundles
      (see `docs/format-spec.md`).

## M6 — Library sync

- [ ] Bidirectional library sync beyond single capture sessions (new scores,
      edited annotations, deletions) across paired devices on the same LAN.
- [ ] Conflict handling for concurrently edited annotations.

## M7 — Packaging & release

- [ ] Android release build (F-Droid-friendly — no proprietary deps).
- [ ] Linux packaging (Flatpak and/or AppImage).
- [ ] Versioned `.smpk` compatibility policy finalized.
- [ ] Public remote repo, issue tracker, contribution flow opened up.

## Later / exploratory (not committed)

- OMR integration for real notation understanding and automatic part
  alignment (see [ADR-0004](docs/decisions/0004-omr-scope.md)).
- Cloud/relay sync transport for off-LAN devices
  (see [ADR-0003](docs/decisions/0003-sync-approach.md)).
- Additional Compose Multiplatform targets (Windows, macOS, iOS, web).
- Setlist/concert mode, metronome, audio playback/backing tracks.
