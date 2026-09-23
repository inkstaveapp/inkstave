plugins {
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.3.1")
}

android {
    namespace = "app.inkstave.android"
    // compileSdk/targetSdk 37 (Compose Multiplatform 1.12.1's Android
    // artifacts require it -- see the libs.versions.toml comment on `agp`)
    // and minSdk 26 (Android 8.0), required for the Bluetooth LE / USB HID
    // APIs the pedal-input work in M3 (ROADMAP.md) will need.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.inkstave.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // :shared declares these as `implementation`, not `api` (correct encapsulation -- see
    // shared/build.gradle.kts), so they aren't transitively visible here. androidApp never
    // needed them directly before CaptureActivity.kt: MainActivity/DocumentPicker only ever
    // consumed the shared App composable and androidx.activity.compose's setContent, never
    // Compose Foundation/Material3 symbols themselves.
    implementation(compose.foundation)
    implementation(compose.material3)
    // suspendCancellableCoroutine, bridging the SAF picker's callback-based
    // ActivityResultLauncher API into the suspend PickedFile picker functions
    // app.inkstave.shared.ui.App expects -- see DocumentPicker.kt.
    implementation(libs.kotlinx.coroutines.core)
    // M4 camera capture (CaptureActivity.kt) -- Android-only, so declared here,
    // not in :shared (see client/README.md's layout note on where
    // platform-only code belongs). camera-view specifically for PreviewView.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // CaptureUiStateTest.kt/CameraCaptureTest.kt: plain JVM unit tests (no Robolectric/Android
    // framework needed -- see those files' own docs). This module had no src/test before M4, so
    // kotlin-test was never declared here; :shared's equivalent test source sets declare it the
    // same way (see shared/build.gradle.kts).
    testImplementation(kotlin("test"))
}
