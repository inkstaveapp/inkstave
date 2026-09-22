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
    // suspendCancellableCoroutine, bridging the SAF picker's callback-based
    // ActivityResultLauncher API into the suspend PickedFile picker functions
    // app.inkstave.shared.ui.App expects -- see DocumentPicker.kt.
    implementation(libs.kotlinx.coroutines.core)
}
