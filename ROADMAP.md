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
      **Gap closed:** `format/scripts/cross_lang_roundtrip.sh` now proves a
      `manifest.json` written by Kotlin is read identically by Python, and
      vice versa, unknown fields included, against a shared canonical
      fixture (`format/fixtures/manifest.v1.cross-lang.json`) — see
      `format/README.md`'s "Cross-language round-trip check". Verified to
      actually catch a real mismatch (not just pass the happy path): the
      check was run against a deliberately-broken Kotlin encoder, confirmed
      to fail with a clear diff, then the break was reverted and the check
      re-confirmed green. Wired into `.github/workflows/ci.yml` as its own
      job (not yet run against a live CI provider — no remote exists yet,
      same as the other jobs).
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

- [x] Import a score from a single PDF. Desktop renders via Apache PDFBox
      (`NOTICE.md`); Android via the platform's built-in
      `android.graphics.pdf.PdfRenderer` (no dependency).
- [x] Import a score from a set of images (PNG/JPEG at minimum), re-encoded
      to PNG at import time regardless of source format.
- [x] Render pages, swipe/tap/keyboard page turning. Swipe via
      `HorizontalPager`; tap zones on the left/right thirds; desktop arrow
      keys (`Key.DirectionLeft`/`Right`) -- a no-op on Android, as intended,
      since nothing there dispatches a key event.
- [x] Basic library screen (list of imported scores with title/composer),
      backed by the local index database, not by scanning `.smpk` files on
      every load (`docs/performance.md`, ADR-0005).
- [x] `.smpk` read/write for this minimal case (no annotations yet):
      `SmpkWriter`/`SmpkReader` (`client/shared/.../format/SmpkContainer.kt`),
      backed by `java.util.zip`, with `Part`/`PageMeta` Kotlin models added
      alongside M0's `Manifest`. Pages are `.png`, not the originally
      -specified `.webp` -- see the note in `docs/format-spec.md`'s
      container listing (no cross-platform WebP codec dependency existed
      yet; revisit once one is actually needed).
- **Verified green:** `:shared:test`, `:shared:desktopTest` (25 tests,
  covering `Part`/`PageMeta` round-trip, the `.smpk` container round-trip,
  `ImportPipeline`, and the real-PDFBox-backed `renderPdfPages`/
  `decodeImagePage` tests), `ktlintCheck`, `:desktopApp:build`, and
  `:androidApp:assembleDebug` (real APK produced) all pass from a clean
  build.
- **UI-level end-to-end automation: partially closed.**
  `client/shared/src/desktopTest/.../ui/AppUiTest.kt` now drives the real
  `App`/`LibraryScreen`/`ViewerScreen` composables through real simulated UI
  interaction (clicks on real semantic nodes, via Compose Multiplatform's
  `runComposeUiTest`) covering the exact headline scenario
  `docs/testing-strategy.md` calls for: import a PDF through the real
  FAB/menu/file-picker seam, see it appear in the library from the index,
  open it, turn pages via a real tap-zone click, and check a page indicator
  added specifically to make that observable (`TestTags` in
  `LibraryScreen.kt`). **Not yet build-verified**: the `compose.uiTest`
  dependency it needs isn't in this machine's Gradle cache and could not be
  downloaded in the session that added it, due to a JVM-specific outbound
  -network restriction on that machine unrelated to this code (confirmed:
  `curl`/`python3` reach Maven Central fine from the same shell, a plain
  `java` process consistently cannot) -- see `client/README.md`'s "Known
  rough edges". The dependency declaration itself is confirmed correct
  (Gradle gets past script compilation and reaches the network call before
  failing). **Action needed:** run `./gradlew :shared:desktopTest` with
  working network access to confirm this test actually passes -- it has not
  been observed to pass yet, only to be correctly written and wired.
  An Android instrumented counterpart
  (`client/shared/src/androidInstrumentedTest/.../ui/AppInstrumentedTest.kt`)
  is written against the same scenario using the standard, long-stable
  `androidx.compose.ui.test.junit4` API, but is **not wired into the Gradle
  build** (no dependencies/source-set config added, for the same
  network-verification reason, plus a judgment call not to guess at
  unfamiliar Kotlin-Gradle-Plugin Android-instrumented-test API with no way
  to compile-check it) and has never been compiled or run. Two KVM
  -accelerated AVDs exist on the development machine this was written on,
  so running it is very likely feasible once wired -- deliberately not
  attempted in that pass to avoid launching a heavyweight emulator process
  on a machine confirmed (twice) to be in concurrent active use by its
  owner during automated sessions. See that file's module doc for exactly
  what's left to wire it up.

## M2 — Annotation engine

- [x] Freehand drawing/highlighting layer per page. `AnnotationOverlay.kt`'s
      Pen/Highlight modes, drag-gesture-captured, stored in normalized
      page-point space (`app.inkstave.shared.annotation.PagePointSpace` —
      see below).
- [x] Stamped symbol library (common notation marks) with placement,
      resize, and per-instance styling. Six marks (`STAMP_PALETTE`:
      fermata, accent, staccato, forte, piano, repeat), tap-to-place,
      drag-to-move (Select mode), toolbar +/− buttons to resize (a
      resize-handle equivalent, not a pinch gesture — a deliberate,
      documented M2 UX call, see `AnnotationOverlay.kt`). Drawn as vector
      shapes/plain Latin letters rather than Unicode musical-symbol glyphs,
      to avoid missing-glyph risk without a new font dependency.
- [x] Text annotations. Tap to place (Text mode) or edit (Select mode) via
      a dialog with a font-size stepper; rendered/dragged like every other
      annotation type.
- [x] Undo/redo, annotation layer persisted in `.smpk`. Undo/redo:
      `AnnotationHistory`, a capped (50-deep) full-layer-snapshot stack —
      simple and obviously correct given the bounded per-page size this
      milestone's own performance target already assumes. Persistence:
      `SmpkUpdater.updateAnnotationLayer` rewrites the one changed
      `annotations/<pageId>.json` entry in an existing `.smpk` (every other
      entry, including every *other* page's bytes, copied through
      untouched — regression-tested explicitly), debounced 600ms after the
      last edit so a burst of quick edits costs one rewrite, not one per
      pointer-move.
- [x] Rendering stays smooth... — **the QA gate is a proxy, stated
      precisely, not overclaimed:** `docs/performance.md`'s target is a
      frame-rate ("no visible stutter"), which isn't meaningfully
      automatable headlessly. What's actually tested and verified:
      `AnnotationSpatialIndex` (a uniform grid, `docs/performance.md`'s
      viewport-culling/hit-testing design) returns *exactly correct*
      results against a 600-item stress page (proven against an
      independent brute-force scan), and does so at **avg 0.0055ms per
      query, avg 0.0010ms per hitTest**, over 2000 iterations each —
      roughly 360x/2000x under the 2ms-per-query budget that target
      implies (see `AnnotationSpatialIndexPerformanceTest`'s own doc for
      the budget's derivation). That's evidence the indexing layer won't
      be the bottleneck; it is not a literal measured frame rate, and
      shouldn't be read as one.
- **Format layer:** `AnnotationLayer`/`Stroke`/`Stamp`/`Highlight`/`TextNote`
  Kotlin models added (`client/shared/.../format/AnnotationLayer.kt`,
  mirroring `Manifest`/`Part`/`PageMeta`'s unknown-field-preserving
  pattern — Python already had these from M0). `SmpkReader.readAnnotationLayer`
  and the new `SmpkUpdater` extend the container (`docs/format-spec.md`'s
  `annotations/<page-id>.json`) to read/update an existing `.smpk`, not
  just write a fresh one.
- **Verified green:** all 52 tests across the 15 test classes touched by
  M2 (including every pre-existing M1 test — no regression) pass under
  `:shared:desktopTest`; `:shared:test`, `ktlintCheck`, `:desktopApp:build`,
  and `:androidApp:assembleDebug` all pass too. `:shared:desktopTest`
  itself is still blocked in this specific sandboxed session by the
  network issue `client/README.md`'s "Known rough edges" already documents
  (a JVM/Gradle-specific outbound-network restriction unrelated to this
  code) — worked around for verification *only* by temporarily commenting
  out the one `compose.uiTest` dependency line and moving `AppUiTest.kt`
  aside, running the real suite, confirming all 52 tests green, then
  restoring both exactly (git diff confirms only the intended files
  changed). Not a permanent change; the dependency is exactly as it was.
- **UI-level end-to-end automation for M2 itself: not attempted.** M1's
  `AppUiTest.kt` gap (real Compose UI interaction, blocked on the same
  network issue) is unchanged by this pass. M2's headline e2e scenario
  ("annotate a page, close and reopen the score, annotation is still there
  and in the right place") is instead covered by `AnnotationEndToEndTest`
  — a headless integration test driving the real
  import → annotate → `SmpkUpdater` save → reopen path without real UI
  widgets, the same honest pattern `LibraryImporterEndToEndTest` set for
  M1.

## M3 — Pedal & performance mode

Built around a real constraint: the repo owner has a pedal but didn't know
(and wasn't at a device to check) what keys it actually sends -- and no
pedal vendor's keys are one true standard anyway (see
`PedalKeyMapping.DEFAULT`'s own doc). So this milestone is deliberately
"good defaults covering every major vendor's factory mode, plus a foolproof
remap flow," not a hardcoded guess.

- [x] Bluetooth/USB page-turner pedal support on Android. These pedals pair
      as standard HID keyboards (no vendor SDK, no `BLUETOOTH_CONNECT`
      permission needed); the real work was that Android hardware key
      events are dispatched via `Activity.dispatchKeyEvent`, *before*
      Compose's own focus-based key handling even runs, and depending on
      Compose focus surviving this app's touch-driven page-turning
      gestures wasn't something that could be verified without physical
      hardware. `MainActivity.dispatchKeyEvent` bridges directly to the
      same `PedalKeyMapping`/`handlePedalAction` logic desktop uses, so
      neither platform can drift into handling a `PedalAction` differently
      (`app.inkstave.shared.pedal`, `ViewerScreen`'s `onRawKeyHandlerChange`).
- [x] Pedal support on Linux desktop: `ViewerScreen`'s existing
      `onPreviewKeyEvent` (M1's hardcoded arrow keys) now looks up
      `PedalKeyMapping` instead.
- [x] Performance mode: minimal chrome (toolbar/back button/page indicator
      hidden; page turning -- swipe, tap zone, pedal -- stays fully live) with
      one always-visible toggle to get back out; screen-on lock via
      `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`
      (`PerformanceMode.android.kt`) -- Android-only by design, a documented
      no-op on desktop (`PerformanceMode.desktop.kt`), since a laptop's
      display-sleep timing is the user's own setting to control, not this
      app's to override; configurable pedal mapping via `PedalSettingsScreen`
      (below); optional half-page/overlap turn via `HorizontalPager`'s own
      negative-`pageSpacing` support (a real Compose Foundation feature, not
      a custom transition) -- worth a visual check once at a device, given
      an open, not-fully-triaged upstream Compose issue around negative
      `pageSpacing` (`ViewerScreen.kt`'s `OVERLAP_PAGE_SPACING` doc).
- **The remap flow, the actual fix for "I don't know my pedal's keys":**
  `PedalSettingsScreen` (entry point: `LibraryScreen`'s "Pedal Settings"
  action) lists each action's bound keys with per-key removal, and an "Add
  binding" → "press the key you want to use now" capture flow -- a real
  key listener (`onPreviewKeyEvent`, desktop-reliable on its own; the same
  `onRawKeyHandlerChange` Android bridge as the viewer, for the same
  focus-reliability reason) that binds whatever key arrives and shows what
  it detected, so the user doesn't need to already know what their pedal
  sends. Settings persist locally: `~/.config/inkstave/pedal-settings.json`
  on desktop (XDG convention -- deliberately `$XDG_CONFIG_HOME`, not the
  library's own `$XDG_DATA_HOME`: this is user configuration, not app
  -generated data), `filesDir/pedal-settings.json` on Android.
- **Also fixed, found while implementing this (not originally scoped, but
  a real regression this exact milestone-plus-M2 combination creates):**
  once a Bluetooth pedal is paired, Android's `InputMethodManager` sees a
  hardware keyboard is connected and suppresses the soft keyboard's
  auto-show-on-focus -- which would otherwise make M2's text-annotation
  dialog (and any future text field) silently untypeable the moment a
  pedal is connected. Every text field now explicitly requests the
  keyboard on focus instead of trusting that heuristic
  (`Modifier.showSoftKeyboardOnFocus`, `SoftKeyboard.kt`) -- the underlying
  decision logic is a plain, fully unit-tested function
  (`onTextFieldFocusChanged`, `SoftKeyboardTest`), not something that
  needed the blocked UI-test dependency below to verify.
- **Verified, precisely:** all pedal/settings/soft-keyboard logic is
  covered by real, passing unit/integration tests with **no hardware
  involved** -- `PedalKeyMappingTest` (7), `PedalSettingsStoreTest` (5),
  `SoftKeyboardTest` (3) -- confirmed together with every pre-existing
  test (67 total, 0 failures) via the same temporarily-disable-and-restore
  method M2 used for the still-unresolved `compose.uiTest` dependency
  (`client/README.md`'s "Known rough edges"), restored cleanly afterward
  (`git diff` confirmed). `:shared:test`, `:androidApp:assembleDebug`,
  `:desktopApp:build`, and `ktlintCheck` all pass directly, no workaround
  needed. A new UI-level test, `PedalSettingsUiTest.kt`, is written against
  the real composable (same pattern as `AppUiTest.kt`) but -- like
  `AppUiTest.kt` -- **not observed to pass in this session**, for the
  identical, already-documented dependency-resolution reason.
- **Explicitly not done, and not claimed:** real pedal hardware validation.
  Everything above is verified with synthetic key events; whether the
  owner's actual pedal sends a key `PedalKeyMapping.DEFAULT` already
  covers, or needs the remap flow, is unknown until they're at a physical
  device with the pedal connected. That's expected and fine -- the remap
  flow exists specifically so this doesn't need to be known in advance --
  but it's a real gap, not a formality: nothing here has touched real
  hardware yet.

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
