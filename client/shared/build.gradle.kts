import java.time.Duration

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlint)
    // SQLDelight generates Kotlin sources into build/generated/sqldelight
    // and adds that directory to commonMain -- exclude it, it's build
    // output, not code this project owns or should lint/format.
    filter {
        exclude { entry -> entry.file.path.contains("${File.separator}generated${File.separator}") }
    }
}

kotlin {
    android {
        namespace = "app.inkstave.shared"
        compileSdk =
            libs.versions.androidCompileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
        // Runs commonTest on the Android JVM too (host tests, no device needed).
        withHostTest {}
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget
                    .fromTarget(libs.versions.jvmTarget.get()),
            )
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget
                    .fromTarget(libs.versions.jvmTarget.get()),
            )
        }
    }

    sourceSets {
        val commonMain =
            getByName("commonMain") {
                dependencies {
                    implementation(libs.kotlinx.serialization.json)
                    // `api`, not `implementation`: app.cash.sqldelight.db.SqlDriver is part of
                    // this module's public surface (LibraryIndexRepository's constructor takes
                    // one, and each app module constructs a platform-specific driver to pass
                    // in -- see SqlDriverFactory in the jvmCommon source set below), so
                    // downstream modules (androidApp, desktopApp) need it on their compile
                    // classpath too, not just this module's own.
                    api(libs.sqldelight.runtime)
                    implementation(libs.kotlinx.coroutines.core)
                    implementation(libs.compose.runtime)
                    implementation(libs.compose.foundation)
                    implementation(libs.compose.material3)
                }
            }
        val commonTest =
            getByName("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        // Both of this project's targets (androidTarget, jvm("desktop")) are JVM-family,
        // so java.util.zip (the .smpk container format, docs/format-spec.md) and the
        // java.io/javax.imageio types the M1 import pipeline needs are available to both
        // -- there's no need to duplicate that code per-target or hide it behind
        // expect/actual. This intermediate source set is where JVM-only, still
        // platform-agnostic-within-the-JVM-family code lives; genuinely
        // platform-divergent code (PDF rendering, image decoding, where Android and
        // desktop use different APIs entirely) stays expect/actual, declared here and
        // implemented in androidMain/desktopMain.
        val jvmCommon =
            create("jvmCommon") {
                // dependsOn makes commonMain's dependencies (kotlinx.serialization, Compose)
                // visible here too, via the Kotlin Gradle plugin's source-set dependency
                // propagation -- no need to redeclare them.
                dependsOn(commonMain)
                dependencies {
                    // LAN discovery (docs/sync-protocol.md, ROADMAP.md M4) -- pure Java, no
                    // native code, so it works identically on androidMain and desktopMain.
                    implementation(libs.jmdns)
                }
            }
        val jvmCommonTest =
            create("jvmCommonTest") {
                dependsOn(commonTest)
                // No dependsOn(jvmCommon) here: a *Test source set gets visibility into its
                // target's main compilation (jvmCommon included, via desktopTest's own
                // dependsOn(jvmCommon) further down) through the test/main compilation
                // association Kotlin sets up per-target, not through an explicit dependsOn
                // edge -- KGP warns ("Invalid Source Set Dependency Across Trees") if one is
                // added here, since jvmCommonTest and jvmCommon are in different source-set
                // trees (test vs. main).
            }
        val androidMain =
            getByName("androidMain") {
                dependsOn(jvmCommon)
                dependencies {
                    implementation(libs.sqldelight.android.driver)
                    // No PDF library dependency here: Android's built-in
                    // android.graphics.pdf.PdfRenderer (part of the platform SDK since
                    // API 21) handles PDF rendering on this target -- see
                    // shared/src/androidMain/.../importer/PdfRendering.android.kt.
                    // PDFBox (desktopMain/desktopTest) is only needed where there's no
                    // platform PDF renderer, i.e. the JVM desktop target.
                }
            }
        val desktopMain =
            getByName("desktopMain") {
                dependsOn(jvmCommon)
                dependencies {
                    implementation(libs.sqldelight.sqlite.driver)
                    implementation(libs.pdfbox)
                }
            }
        val desktopTest =
            getByName("desktopTest") {
                dependsOn(jvmCommonTest)
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.sqldelight.sqlite.driver)
                    implementation(libs.pdfbox)
                    // Real Compose UI tests (docs/testing-strategy.md's M1 UI-level e2e
                    // gap -- see ui/AppUiTest.kt): runComposeUiTest drives the actual
                    // LibraryScreen/ViewerScreen composables headlessly against Skiko's
                    // software renderer, no display server required. compose.uiTest is
                    // marked @ExperimentalComposeLibrary by the Compose Multiplatform
                    // Gradle plugin itself -- the opt-in below is for *declaring this
                    // dependency*, not related to any API stability concern in the tests
                    // that consume it. Confirmed to resolve correctly against a working
                    // network (script-compiles and reaches Maven Central); this session's
                    // environment could not download it -- see client/README.md's "Known
                    // rough edges" and ROADMAP.md's M1 entry.
                    implementation(libs.compose.ui.test)
                    // Skiko's native renderer for the host OS. compose.uiTest doesn't pull it
                    // in, and without it every UI test failed with LibraryLoadException
                    // "Cannot find libskiko-linux-x64.so.sha256" -- a classpath gap, not a
                    // missing system graphics library as first suspected.
                    implementation(compose.desktop.currentOs)
                }
            }
    }
}

sqldelight {
    databases {
        create("InkstaveDatabase") {
            packageName.set("app.inkstave.shared.index")
        }
    }
}

// Cross-language `.smpk` format round-trip check
// (format/scripts/cross_lang_roundtrip.sh, docs/testing-strategy.md).
// Runs CrossLangRoundtripCli.kt as a plain JVM process against the desktop
// target's MAIN compilation classpath -- desktop, not android, because this
// is a CLI process and desktop is this project's JVM-CLI-capable target.
// Deliberately the *main* compilation, not desktopTest's: see
// CrossLangRoundtripCli.kt's module doc for why it lives in jvmCommon
// (production) rather than jvmCommonTest -- using desktopTest here would
// couple this task to every dependency desktopTest has, including
// compose.uiTest (ui/AppUiTest.kt), for no reason this CLI actually needs.
run {
    val desktopMainCompilation =
        kotlin.targets
            .getByName("desktop")
            .compilations
            .getByName("main")

    fun registerCrossLangTask(
        taskName: String,
        mode: String,
    ) = tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Cross-language .smpk manifest.json round-trip check ($mode mode) -- see CrossLangRoundtripCli.kt."
        dependsOn("desktopMainClasses")
        classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
        mainClass.set("app.inkstave.shared.format.crosslang.CrossLangRoundtripCliKt")
        args = listOf(mode)
        // CROSS_LANG_FIXTURE_PATH / CROSS_LANG_TARGET_PATH are passed through
        // from the environment by format/scripts/cross_lang_roundtrip.sh;
        // JavaExec inherits the Gradle process's environment by default, so
        // nothing else is needed here.
    }

    registerCrossLangTask("crossLangManifestWrite", "write")
    registerCrossLangTask("crossLangManifestRead", "read")
}

// Print each test as it starts and finishes, and bound the whole run: on CI a
// hung test otherwise shows nothing (Gradle only reports failures) and holds the
// job until GitHub's 6-hour limit.
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
    timeout.set(Duration.ofMinutes(15))
}
