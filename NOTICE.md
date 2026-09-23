# Third-party dependencies and licenses

This file is the running ledger of every third-party dependency used by this
project (libraries, models, fonts, sample data) and its license. It must be
kept in sync with what's actually declared in `client/`'s Gradle build files
and `processing-service/`'s Python dependency manifest.

Maintained by / consult: [.claude/agents/licensing-compliance.md](.claude/agents/licensing-compliance.md).

Rules:

- Every dependency added to the build must have a row here in the same
  change.
- Prefer permissive licenses (MIT, Apache-2.0, BSD) and weak-copyleft
  (LGPL, MPL-2.0) consumed as unmodified dynamic dependencies. Anything
  GPL/AGPL-licensed needs an explicit note on how it's isolated (e.g. run as
  a separate process, not statically linked) and the human's sign-off before
  being added.
- If a dependency's license requires attribution text in end-user-facing
  "about"/licenses screens, note that in the Notes column so the client app
  can surface it.

**M0 scope note:** the tables below list the *direct* dependencies declared
in `client/gradle/libs.versions.toml` and the two `pyproject.toml` files as
of M0 scaffolding -- not an exhaustive transitive-dependency scan. A full
scan (e.g. a Gradle license-report plugin for `client/`, `pip-licenses` for
the Python side) is real follow-up work `licensing-compliance` should run
before any release (`ROADMAP.md` M7), not something done by hand here.

## Client (Kotlin Multiplatform / Android / Linux desktop)

| Dependency | Version | License | Notes |
|---|---|---|---|
| Kotlin / Kotlin Multiplatform Gradle plugin | 2.2.20 | Apache-2.0 | |
| Compose Multiplatform (org.jetbrains.compose) | 1.12.1 | Apache-2.0 | |
| kotlinx.serialization | 1.9.0 | Apache-2.0 | JSON (de)serialization for the `.smpk` format models. |
| Android Gradle Plugin (com.android.tools.build:gradle) | 9.4.0 | Apache-2.0 | Build-time only, not shipped in the app. |
| AndroidX activity-compose | 1.11.0 | Apache-2.0 | |
| AndroidX core-ktx | 1.16.0 | Apache-2.0 | |
| SQLDelight (app.cash.sqldelight) | 2.3.2 | Apache-2.0 | Local library index, ADR-0005. |
| ktlint-gradle (org.jlleitschuh.gradle.ktlint) | 14.2.0 | MIT | Dev tooling; wraps ktlint (also MIT). |
| kotlinx-coroutines-core | 1.10.2 | Apache-2.0 | M1: library/viewer screen async import + lazy page loading. |
| Apache PDFBox (org.apache.pdfbox:pdfbox) | 3.0.7 | Apache-2.0 | M1: desktop-only PDF-to-page rendering (`docs/decisions/0001-client-framework.md`). Android uses the platform's built-in `android.graphics.pdf.PdfRenderer` instead -- no dependency, no license entry needed for that path. |
| Compose UI testing (`org.jetbrains.compose.ui:ui-test` via the `compose.uiTest` Gradle plugin accessor) | 1.12.1 (matches `composeMultiplatform`) | Apache-2.0 | Test-only (`client/shared/src/desktopTest/.../ui/AppUiTest.kt`), closes `docs/testing-strategy.md`'s M1 UI-level e2e gap for the desktop target. Declared and confirmed to script-compile correctly; not yet downloaded/build-verified in the environment this was added in -- see `ROADMAP.md`'s M1 entry and `client/README.md`'s "Known rough edges". |

**Not added: detekt.** Tried at 1.23.8 (current stable); fails to
configure against AGP 8.11.1/9.x with a `NoClassDefFoundError`. detekt 2.0
(which does target current AGP) is alpha-only. See the comment at the top
of `client/build.gradle.kts`. Revisit once a stable, AGP-9-compatible
detekt release exists.

## Processing service (Python)

| Dependency | Version | License | Notes |
|---|---|---|---|
| FastAPI | \>=0.115 (0.120.x installed) | MIT | Local HTTP API, loopback-only (`docs/image-pipeline.md`). |
| uvicorn | \>=0.32 (0.38.x installed) | BSD-3-Clause | ASGI server FastAPI runs on. |
| httpx | \>=0.27 (dev/test only) | BSD-3-Clause | Used transitively by FastAPI's `TestClient`. |
| opencv-python-headless | ==4.14.0.94 | Apache-2.0 | Image pipeline (`inkstave_processing.pipeline`, `docs/image-pipeline.md`). Pinned to the latest 4.x line rather than the newly-released 5.x, deliberately -- see `processing-service/README.md`. `-headless` specifically: no GUI/Qt/X11 dependency needed for a service with no display. |
| numpy | \>=2.1 (2.5.x installed) | BSD-3-Clause (+ small 0BSD/MIT/Zlib/CC0-1.0-licensed portions, per pip's own license metadata -- all permissive) | Image pipeline array operations. |
| pillow | \>=11 (dev/test only) | MIT-CMU | Synthetic test-fixture image generation only (`tests/pipeline/fixtures.py`) -- not a pipeline runtime dependency. |

`inkstave-processing` also depends on `inkstave-format` (below), installed
locally/editable from `format/python` -- not a third-party dependency.

## Format tooling

| Dependency | Version | License | Notes |
|---|---|---|---|
| pydantic | \>=2.9 (2.13.x installed) | MIT | Typed models for `.smpk` JSON documents. |
| jsonschema | \>=4.23 (4.26.x installed) | MIT | Validates against `format/schema/*.schema.json`. |
| mypy | \>=1.14 (dev-only, shared by `format/python` and `processing-service`) | MIT | `mypy --strict`, required per `docs/coding-standards.md`. |
| ruff | \>=0.8 (dev-only, shared) | MIT | Lint + format for both Python projects. |
| pytest | \>=8.3 (dev-only, shared) | MIT | |

## Fonts / assets / models

| Dependency | Version | License | Notes |
|---|---|---|---|
| _none yet_ | | | |
