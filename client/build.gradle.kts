// ktlint is applied per module through the `plugins {}` block, not via
// `subprojects { apply(...) }`: the imperative form broke ktlint-gradle's
// Android plugin detection (NoClassDefFoundError on CommonExtension).
//
// detekt isn't used: no stable release configures against AGP 9.
//
// Plugins used by several modules are declared here with `apply false` so
// each loads once, rather than in a separate classloader per module.
//
// The buildscript block forces org.jetbrains:annotations to 23.0.0, which
// AGP 9's dependencies need; the Kotlin DSL otherwise pins it to 13.0 and
// plugin classpath resolution fails. Re-check when bumping Gradle or AGP.

buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.jetbrains:annotations:23.0.0")
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
}
