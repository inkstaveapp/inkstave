import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlint)
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(libs.versions.jvmTarget.get()),
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Dispatchers.IO for the blocking JFileChooser call in DesktopFilePicker.
    implementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "app.inkstave.desktop.MainKt"

        nativeDistributions {
            // Placeholder formats for dev builds; real Linux packaging is not designed yet.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Inkstave"
            packageVersion = "0.1.0"
        }
    }
}
