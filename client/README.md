# `client/`

Kotlin Multiplatform + Compose Multiplatform application, targeting Android
and Linux desktop (ADR-0001, `../docs/decisions/0001-client-framework.md`).

## Layout

```
client/
├── shared/       Business logic shared by all targets: .smpk format models
│                 (Manifest/Part/PageMeta/AnnotationLayer) + container
│                 reader/writer (java.util.zip, jvmCommon -- SmpkUpdater
│                 rewrites one entry in an existing package), local library
│                 index (SQLDelight, ADR-0005), PDF/image import pipeline,
│                 a spatial-index-backed annotation engine (commonMain
│                 annotation/ package -- undo/redo, viewport culling/
│                 hit-testing), pedal key-mapping + settings persistence
│                 (commonMain/jvmCommon/desktopMain pedal/ package), LAN
│                 discovery/pairing/capture-session transport (commonMain/
│                 jvmCommon/desktopMain/androidMain sync/ package, M4), a
│                 desktop-only HTTP client + best-effort launcher for
│                 processing-service (desktopMain processing/ package, M4 --
│                 java.net.http.HttpClient, no new dependency), and the real
│                 library/viewer/annotation/pedal-settings/pairing Compose
│                 screens (jvmCommon -- see "jvmCommon" below)
├── androidApp/   Android application module: Compose entry point, SAF-backed
│                 file pickers (DocumentPicker.kt), and the camera capture
│                 flow (CaptureActivity.kt/CameraCapture.kt, M4)
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
see `../.claude/agents/`. M1, M2, and M3 (`../ROADMAP.md`) are real: import
a PDF or a set of images, list the library from the local index, view a
score with swipe/tap/keyboard/pedal page turning, annotate pages (pen,
highlight, stamps, text, undo/redo, persisted back into the `.smpk`), remap
pedal bindings (`PedalSettingsScreen`), and enter a minimal-chrome
performance mode. M4 is also real now: capture photos with the device
camera and import them the same way as any other image set
(`CaptureActivity.kt`); discover, pair with, and manage trust for other
devices on the same LAN (`PairingScreen`, `app.inkstave.shared.sync`) over
a TLS connection authenticated by a human-compared certificate fingerprint
(not a shared password or QR code); and send a real capture session (a
sequence of photos) to a paired device over that same connection
(`LanSyncTransport`). Multi-part scores are still ahead (M5). **Wiring the
camera-capture flow to actually *send* its photos through the sync
transport to a paired desktop, instead of only importing locally, is real
follow-up work, not done in this pass** -- see `ROADMAP.md`'s M4 entry.
**Not done yet, and not claimed:** M3's pedal support and M4's camera
capture have never touched real hardware, and M4's sync has only ever been
exercised between two processes on one machine's loopback interface, never
a real phone and a real desktop on a real LAN -- see `ROADMAP.md`'s M3 and
M4 entries for exactly what is and isn't verified in each case.

**Android hardware key events (`MainActivity.dispatchKeyEvent`):**
`ViewerScreen`/`PedalSettingsScreen` both listen for keys via Compose's own
`onPreviewKeyEvent`, which works reliably on desktop but is a real risk on
Android specifically -- hardware key events there are dispatched to
`Activity.dispatchKeyEvent` *before* Compose's own focus-based key handling
runs at all, and this app's touch-driven page-turning gestures are exactly
the kind of thing that can knock Compose's internal focus off whatever
element is listening. Both composables instead register a plain
`(Key) -> Boolean` handler that `MainActivity` calls directly from
`dispatchKeyEvent`, sidestepping Compose focus for key handling on Android
entirely (see `ViewerScreen`'s `onRawKeyHandlerChange` doc for the full
reasoning). Converts the native `android.view.KeyEvent` via Compose's own
public `KeyEvent(nativeKeyEvent)` wrapper, not a hand-rolled keycode
conversion -- `Key`'s internal representation packs the native keycode
together with other bits, so going through Compose's own conversion is
what guarantees the result compares equal to constants like
`Key.DirectionRight` the same way.

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
`ANDROID_SDK_ROOT` (or `ANDROID_HOME`) must point to it. The desktop app
also needs `keytool` on `PATH` (standard in every JDK distribution, so this
is normally already satisfied by the JDK requirement above) -- it's how
`app.inkstave.shared.sync` generates this device's self-signed sync identity
on first run (`DeviceIdentityProvisioning.desktop.kt`'s doc explains why
`keytool` rather than a library).

For a received capture session's photos to actually come back cleaned
(rather than falling back to raw import -- `ROADMAP.md`'s M4 entry),
`../processing-service/` needs its own `.venv` set up per that directory's
own README. `ProcessingServiceLauncher.kt` looks for it a few directory
levels around the desktop app's own working directory and starts it
automatically if it's not already running -- a **dev-checkout-only**
mechanism, not yet a real release-packaging story (see that class's own
doc, and `ROADMAP.md` M7). Without it, capture sessions still work end to
end, just unprocessed -- this is a real, expected, gracefully-handled
degradation, not a hard dependency for the app to run at all.

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
- **`settings.gradle.kts`'s `google()` repository is content-filtered** to
  `com.android.*`/`androidx.*`/`com.google.*` groups only. This isn't a
  workaround for anything broken -- it's the standard, Google-recommended
  pattern, added because `google()` was otherwise being tried (and 404ing)
  for every non-Android dependency this project has, which is wasted
  round-trips at best. If a future dependency genuinely needs `google()`
  and doesn't match those group patterns, widen the filter rather than
  removing it.
- **The JVM-specific outbound-network restriction earlier sessions hit
  (M1-M3) is intermittent, not a permanent property of this environment --
  don't assume a new dependency will hit it.** M1-M3 all documented a
  session where `curl`/`python3` reached Maven Central fine but a plain
  `java`/Gradle process consistently could not, which blocked
  `compose.uiTest` from ever being downloaded. M4's camera-capture pass hit
  no such restriction at all: CameraX (four new `androidx.camera:*`
  artifacts) resolved and `:androidApp:assembleDebug`/
  `:androidApp:testDebugUnitTest` both passed for real, and in the same
  session, `compose.uiTest` *also* finally resolved -- see the next point.
- **The Compose UI tests (`ui/AppUiTest.kt`, `ui/PedalSettingsUiTest.kt`) run
  and pass.** They failed for a long time with
  `org.jetbrains.skiko.LibraryLoadException` ("Cannot find
  libskiko-linux-x64.so.sha256"), first suspected to be a missing system
  graphics library. It was a classpath gap: `compose.uiTest` doesn't bring
  Skiko's native renderer, so `desktopTest` now also depends on
  `compose.desktop.currentOs`. Once they ran, they caught a real bug:
  Pedal Settings' Reset/Back buttons were laid out off-screen.

None of these are permanent -- they're the actual state of the Kotlin/AGP/
Compose ecosystem transition happening right as this was scaffolded, and
should be revisited (in this order: drop the workarounds one at a time) the
next time these dependency versions are bumped.
