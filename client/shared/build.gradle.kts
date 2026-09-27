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
    // Exclude SQLDelight's generated sources (build output).
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
                    // api: SqlDriver is part of this module's public API (LibraryIndexRepository takes one).
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
        // Code shared by the two JVM-family targets (Android, desktop): java.util.zip,
        // java.io and similar are available to both. Genuinely platform-divergent code
        // (PDF rendering, image decoding) stays expect/actual in androidMain/desktopMain.
        val jvmCommon =
            create("jvmCommon") {
                dependsOn(commonMain)
                dependencies {
                    // LAN discovery; pure Java, so it works on both targets.
                    implementation(libs.jmdns)
                }
            }
        val jvmCommonTest =
            create("jvmCommonTest") {
                dependsOn(commonTest)
                // No dependsOn(jvmCommon): test source sets see main code through the
                // per-target test/main association; a cross-tree dependsOn makes KGP warn.
            }
        val androidMain =
            getByName("androidMain") {
                dependsOn(jvmCommon)
                dependencies {
                    implementation(libs.sqldelight.android.driver)
                    // No PDF library: Android renders PDFs with the platform PdfRenderer.
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
                    // Compose UI tests, run headlessly on Skiko's software renderer.
                    implementation(libs.compose.ui.test)
                    // Skiko's native renderer for the host OS; compose ui-test doesn't include it.
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

// Cross-language .smpk round-trip check (format/scripts/cross_lang_roundtrip.sh).
// Runs CrossLangRoundtripCli against the desktop *main* classpath, so it doesn't
// depend on desktopTest's test-only dependencies.
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
        // CROSS_LANG_FIXTURE_PATH/CROSS_LANG_TARGET_PATH come from the environment.
    }

    registerCrossLangTask("crossLangManifestWrite", "write")
    registerCrossLangTask("crossLangManifestRead", "read")
}

// Log each test's start and finish, and time-box the run, so a hang on CI names
// the test instead of holding the job until GitHub's 6-hour limit.
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
    timeout.set(Duration.ofMinutes(15))
}
