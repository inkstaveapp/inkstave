@file:Suppress("UnstableApiUsage")

rootProject.name = "inkstave"

// Content-filtered per Google/Android's own recommended settings.gradle.kts
// pattern: google() only actually hosts com.android.*/androidx.*/com.google.*
// artifacts (confirmed empirically -- e.g. org.jetbrains.compose.ui:ui-test
// 404s on dl.google.com but resolves fine from mavenCentral). Without this,
// Gradle still tries google() first for every dependency, including ones it
// never has, which is wasted round-trips at best and -- if that lookup ever
// hard-fails rather than 404s -- can abort resolution for an artifact that
// mavenCentral actually has, since Gradle doesn't always fall through
// cleanly to the next repository after an earlier one errors mid-build.
// (Repeated at both call sites, not factored into a shared val: a
// settings.gradle.kts pluginManagement {} block is evaluated in an isolated
// scope that can't see script-level properties defined alongside it.)
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")
