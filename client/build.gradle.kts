// Root build: intentionally near-empty. ktlint is applied per-module (see
// each module's own build.gradle.kts) rather than centrally via
// `subprojects { apply(plugin = ...) }`: that imperative-apply pattern
// broke ktlint-gradle's Android-plugin detection here with
// `NoClassDefFoundError: com/android/build/api/dsl/CommonExtension`
// (org.jlleitschuh.gradle.ktlint.android.AndroidPluginsApplier) --
// applying it through the `plugins {}` DSL block per-module, alongside AGP
// in the same block, resolves it onto one consistent classpath and avoids
// the issue.
//
// detekt was also tried and dropped for this pass: detekt 1.23.8 (current
// stable) fails to configure against AGP 8.11.1 with
// `NoClassDefFoundError: com/android/build/gradle/BaseExtension`
// (io.gitlab.arturbosch.detekt.internal.DetektAndroid.registerTasks) --
// detekt's own compatibility table lists 1.23.8 as tested against
// Kotlin 2.0.21/Gradle 8.12.1, and detekt 2.0 (which does target current
// AGP) is alpha-only, built against Kotlin 2.4.10/Gradle 9.6.1/AGP 9.3.1.
// Neither is a safe pick right now -- see the M0 completion notes in
// ROADMAP.md.
//
// Every plugin used by more than one module is declared here once with
// `apply false` and referenced (without a version) from each module's own
// `plugins {}` block. Without this, Gradle warned: "The Kotlin Gradle
// plugin was loaded multiple times in different subprojects" -- each
// module resolving the same plugin independently loads a separate
// classloader instance of it, which Gradle flags as unsupported/fragile.
//
// The buildscript block below forces org.jetbrains:annotations to the
// version AGP 9.x's ddmlib/layoutlib-api/repository dependencies actually
// need (23.0.0). Without it, resolving the plugin classpath fails with
// "Could not resolve org.jetbrains:annotations:{strictly 13.0} ... Pinned
// to the embedded Kotlin" -- Gradle's Kotlin-DSL script compiler pins that
// artifact to 13.0 for its own embedded Kotlin runtime, which conflicts
// with what AGP 9's transitive dependencies request. This is a known,
// currently-unresolved friction point in the Gradle/AGP 9 ecosystem as of
// this scaffolding (September 2026) -- revisit whether this override is
// still necessary next time these versions are bumped.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.jetbrains:annotations:23.0.0")
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
}
