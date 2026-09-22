# Roadmap

Milestones are ordered but not strictly sequential — some can overlap.
Nothing here is a promise of a date, just of sequence.

**Definition of done, every milestone:** a checkbox below isn't done until
its functional scenarios are covered by tests at the appropriate level
(`docs/testing-strategy.md`), typesafety and documentation standards are met
(`docs/coding-standards.md`), and — for anything UI-facing or performance
-sensitive — the relevant target in `docs/performance.md` is verified, not
assumed.

## M0 — Project scaffolding (current)

- [x] Plan, architecture docs, ADRs, agent definitions (this commit).
- [x] Scaffold the Kotlin Multiplatform project (`client/`): `shared`,
      `androidApp`, `desktopApp` modules. Verified green from a clean build:
      `:shared:test`, `:shared:desktopTest`, `:desktopApp:build`,
      `:androidApp:assembleDebug`, `ktlintCheck` all pass; the desktop app
      was actually launched and confirmed to render a real window. See
      `client/README.md`'s "Known rough edges" for the AGP 9/Gradle 9
      ecosystem workarounds this needed (documented at each call site, not
      just there).
- [x] Scaffold the Python processing service (`processing-service/`):
      project layout, dependency management (`pyproject.toml`, local
      editable dependency on `inkstave-format`), a real `/health` endpoint
      verified to respond over HTTP and confirmed (via `ss`) to bind to
      `127.0.0.1` only, per `docs/image-pipeline.md`'s loopback-only
      requirement.
- [x] Scaffold `format/`: JSON Schema 2020-12 for `manifest.json`,
      `part.json`, page metadata, and the annotation layer (format v1),
      with one fixture per schema. Typed reader/writer in both Kotlin
      (`client/shared/.../format/Manifest.kt`) and Python
      (`format/python`, `inkstave-format`), each with its own passing
      round-trip test proving unknown-field preservation.
      **Gap, not yet done:** a true *cross-language* round-trip test (write
      with Kotlin, read with Python, or vice versa, against the same file)
      per `docs/testing-strategy.md`'s integration-test bar for this area —
      each language's round-trip is tested independently, but not yet
      against each other. Worth doing before M1 leans on this.
- [x] Set up local dev tooling: ktlint (client), ruff + `mypy --strict`
      (both Python projects) all wired and passing;
      `.pre-commit-config.yaml` and `.github/workflows/ci.yml` added
      (neither installed/run against a live CI provider yet — no remote
      exists, see below).
      **Also dropped, with a documented reason:** detekt -- see
      `NOTICE.md` and the comment in `client/build.gradle.kts`.
- [x] Adopt SQLDelight for the local library index database
      (see `docs/decisions/0005-local-library-index-database.md`), with a
      passing integration test (insert/select/search/delete against a real
      SQLite database via the JDBC driver).
- [ ] Create the remote repository and push, once the above builds cleanly.
      Deliberately not done in this pass — `CLAUDE.md` says a remote gets
      created when explicitly asked for, not assumed.

## M1 — Single-device viewer (Android + Linux)

- [ ] Import a score from a single PDF.
- [ ] Import a score from a set of images.
- [ ] Render pages, swipe/tap/keyboard page turning.
- [ ] Basic library screen (list of imported scores with title/composer),
      backed by the local index database, not by scanning `.smpk` files on
      every load (`docs/performance.md`, ADR-0005).
- [ ] `.smpk` read/write for this minimal case (no annotations yet).

## M2 — Annotation engine

- [ ] Freehand drawing/highlighting layer per page.
- [ ] Stamped symbol library (common notation marks) with placement,
      resize, and per-instance styling.
- [ ] Text annotations.
- [ ] Undo/redo, annotation layer persisted in `.smpk`.
- [ ] Rendering stays smooth (target: native frame rate, no visible
      stutter) on pages with hundreds of annotation objects — viewport
      culling and spatial indexing, per `docs/performance.md`. This is a
      tested QA gate for this milestone, not an afterthought.

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
