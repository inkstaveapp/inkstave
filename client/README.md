# `client/`

Kotlin Multiplatform + Compose Multiplatform application, targeting Android
and Linux desktop (ADR-0001, `../docs/decisions/0001-client-framework.md`).

## Layout

```
client/
├── shared/       Business logic shared by all targets: .smpk manifest
│                 model (kotlinx.serialization), local library index
│                 (SQLDelight, ADR-0005), shared Compose UI
├── androidApp/   Android application module (Compose entry point)
├── desktopApp/   Linux desktop JVM application (Compose Desktop entry point)
└── gradle/libs.versions.toml   Version catalog -- the source of truth for
                                every dependency version used here
```

Owned by the `client-ui`, `android-platform`, and `linux-desktop` agents —
see `../.claude/agents/`. Only scaffolding exists so far (M0): a placeholder
"Inkstave" screen shared by both apps, the manifest model with round-trip
tests, and the local index database with integration tests. Real library/
viewer/annotation UI lands in M1+ (`../ROADMAP.md`).

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
