# `client/`

Kotlin Multiplatform + Compose Multiplatform application, targeting Android
and Linux desktop (ADR-0001, `../docs/decisions/0001-client-framework.md`).

## Layout

```
client/
├── shared/       Business logic shared by all targets: .smpk format models
│                 (Manifest/Part/PageMeta) + container reader/writer
│                 (java.util.zip, jvmCommon), local library index (SQLDelight,
│                 ADR-0005), PDF/image import pipeline, and the real library/
│                 viewer Compose screens (jvmCommon -- see "jvmCommon" below)
├── androidApp/   Android application module: Compose entry point, SAF-backed
│                 file pickers (DocumentPicker.kt)
├── desktopApp/   Linux desktop JVM application: Compose Desktop entry point,
│                 JFileChooser-backed file pickers (DesktopFilePicker.kt)
└── gradle/libs.versions.toml   Version catalog -- the source of truth for
                                every dependency version used here
```

**`shared`'s `jvmCommon` source set:** both of this project's targets
(`androidTarget`, `jvm("desktop")`) are JVM-family, so `shared/build.gradle.kts`
wires a manual intermediate source set (`jvmCommon`, `dependsOn(commonMain)`,
depended on by both `androidMain` and `desktopMain`) for code that needs
`java.util.zip`/JVM APIs but doesn't need Android- or desktop-specific ones --
the `.smpk` container reader/writer, the import pipeline, and most of the
library/viewer UI all live there rather than in `commonMain` (which can't see
`java.*`) or being duplicated per-target. Genuinely platform-divergent code
(PDF rendering, image decoding, bitmap decoding for display) is `expect` in
`jvmCommon`, `actual` in `androidMain`/`desktopMain`.

Owned by the `client-ui`, `android-platform`, and `linux-desktop` agents —
see `../.claude/agents/`. M1 (`../ROADMAP.md`) is real: import a PDF or a set
of images, list the library from the local index, view a score with swipe/
tap/keyboard page turning. Annotations, pedal input, capture/sync, and
multi-part scores are still ahead (M2+).

## Building

```
./gradlew :shared:test :shared:desktopTest   # unit + integration tests
./gradlew :desktopApp:run                     # launch the desktop app
./gradlew :androidApp:assembleDebug           # build the Android debug APK
./gradlew ktlintCheck                         # lint (ktlintFormat to auto-fix)
```

Requires JDK 17+ and an Android SDK with platform 37 / build-tools 37.0.0
installed (`compileSdk`/`targetSdk` -- see the comments in
`androidApp/build.gradle.kts` and `shared/build.gradle.kts` for why).
`ANDROID_SDK_ROOT` (or `ANDROID_HOME`) must point to it.

## Known rough edges (as of M0, September 2026)

Worth knowing before touching build files -- each is explained in detail in
a comment at its call site, not just noted here:

- **AGP is at 9.4.0**, ahead of the Kotlin Multiplatform Gradle plugin's own
  documented compatibility range (which tops out at 8.11.1 for Kotlin
  2.2.x) -- current Compose Multiplatform's Android artifacts require AGP
  9.1+ regardless, so staying on 8.x wasn't actually an option. See the
  comment on `agp` in `gradle/libs.versions.toml`.
- **`android.builtInKotlin=false` / `android.newDsl=false`** are set in
  `gradle.properties` as AGP's own documented temporary bypass, because the
  classic `org.jetbrains.kotlin.android`/`.multiplatform` plugins this
  project uses don't yet coexist with AGP 9's built-in-Kotlin mode. Migrating
  off this is real follow-up work, not a default to just remove.
- **A forced `org.jetbrains:annotations:23.0.0`** in the root
  `build.gradle.kts`'s `buildscript` block works around a genuine Gradle/AGP
  9 ecosystem conflict (Gradle's Kotlin-DSL embedded-Kotlin pin vs. AGP 9's
  transitive dependency on a newer version). See the comment there.
- **detekt is not wired up** (only ktlint) -- detekt 1.23.8 doesn't
  configure cleanly against this AGP version; detekt 2.0 is alpha-only. See
  `NOTICE.md` and the comment in `build.gradle.kts`.

None of these are permanent -- they're the actual state of the Kotlin/AGP/
Compose ecosystem transition happening right as this was scaffolded, and
should be revisited (in this order: drop the workarounds one at a time) the
next time these dependency versions are bumped.
