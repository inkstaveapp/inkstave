plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.3.1")
    // SQLDelight generates Kotlin sources into build/generated/sqldelight
    // and adds that directory to commonMain -- exclude it, it's build
    // output, not code this project owns or should lint/format.
    filter {
        exclude { entry -> entry.file.path.contains("${File.separator}generated${File.separator}") }
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
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
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
            }
        }
        val commonTest by getting {
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
        val jvmCommon by creating {
            // dependsOn makes commonMain's dependencies (kotlinx.serialization, Compose)
            // visible here too, via the Kotlin Gradle plugin's source-set dependency
            // propagation -- no need to redeclare them.
            dependsOn(commonMain)
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest)
            // No dependsOn(jvmCommon) here: a *Test source set gets visibility into its
            // target's main compilation (jvmCommon included, via desktopTest's own
            // dependsOn(jvmCommon) further down) through the test/main compilation
            // association Kotlin sets up per-target, not through an explicit dependsOn
            // edge -- KGP warns ("Invalid Source Set Dependency Across Trees") if one is
            // added here, since jvmCommonTest and jvmCommon are in different source-set
            // trees (test vs. main).
        }
        val androidMain by getting {
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
        val desktopMain by getting {
            dependsOn(jvmCommon)
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.pdfbox)
            }
        }
        val desktopTest by getting {
            dependsOn(jvmCommonTest)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.pdfbox)
            }
        }
    }
}

android {
    namespace = "app.inkstave.shared"
    // 37: Compose Multiplatform 1.12.1's Android artifacts require
    // compileSdk 37+ (see the libs.versions.toml comment on `agp`).
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("InkstaveDatabase") {
            packageName.set("app.inkstave.shared.index")
        }
    }
}
