@file:Suppress("UnstableApiUsage")

rootProject.name = "inkstave"

// google() only hosts com.android/com.google/androidx artifacts; filtering it
// avoids a lookup there for every other dependency, where a hard failure
// (rather than a 404) can abort resolution. Repeated in both blocks because
// pluginManagement {} can't see script-level values.
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
