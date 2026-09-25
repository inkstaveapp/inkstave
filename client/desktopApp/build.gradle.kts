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
    // withContext(Dispatchers.IO), wrapping the blocking JFileChooser call --
    // see DesktopFilePicker.kt.
    implementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "app.inkstave.desktop.MainKt"

        nativeDistributions {
            // Flatpak/AppImage packaging specifics are linux-desktop's call
            // (ROADMAP.md M7) -- this just lists a sane default set of
            // formats so `./gradlew :desktopApp:packageDistributionForCurrentOS`
            // produces something runnable during scaffolding/dev.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Inkstave"
            packageVersion = "0.1.0"
        }
    }
}
