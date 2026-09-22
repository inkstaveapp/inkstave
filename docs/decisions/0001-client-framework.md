# ADR-0001: Client built with Kotlin Multiplatform + Compose Multiplatform

**Status:** accepted
**Date:** 2026-09-23

## Context

v1 needs one codebase covering both Android and Linux desktop, with a
plausible growth path to more targets (iOS, Windows, macOS, web) later
without a rewrite. The client also needs to talk to a local Python
processing service (ADR-0002) and to do nontrivial custom rendering (paged
score view, vector annotation overlay).

## Options considered

- **Kotlin Multiplatform + Compose Multiplatform** — one Kotlin codebase;
  shared business logic across all targets; Compose Multiplatform covers
  Android and Linux/Windows/macOS desktop UI natively (JetBrains-maintained);
  strong typing; good JVM ecosystem for PDF/image handling; first-class
  Android story since Compose *is* the modern Android UI toolkit already.
- **Flutter (Dart)** — mature, single codebase, strong Linux desktop and
  Android support, huge widget/plugin ecosystem. Dart is a smaller
  ecosystem for the kind of local-process/socket/crypto plumbing sync needs,
  and pairing a Dart client with a Python service is one more language
  boundary than Kotlin-talking-to-Python already is.
- **Separate native apps** (Kotlin/Android + GTK or Qt for Linux) — best
  platform-native feel, but duplicates all business logic (format
  reader/writer, sync client, annotation model) across two codebases and
  roughly doubles the surface every specialist agent and doc needs to cover.
- **React Native + custom Linux shell** — web-tech ecosystem, but Linux
  desktop support is the weakest option here and would need significant
  extra glue.

## Decision

Kotlin Multiplatform with Compose Multiplatform, targeting Android and Linux
desktop from `client/shared`, `client/androidApp`, `client/desktopApp`.

## Consequences

- One business-logic implementation (format, sync client, annotation
  model) shared across targets — no duplicate-logic drift.
- Adding Windows/macOS desktop later is largely "enable the target," not a
  rewrite, since Compose Multiplatform desktop already covers all three.
  iOS would need more platform-specific work (camera, pedal HID) but shares
  the same UI/business-logic layer.
- The processing service stays a separate Python process (ADR-0002); the
  client talks to it over a local API rather than in-process bindings.
- Team/agent context now includes Kotlin, Gradle, and Compose idioms as a
  baseline expectation for client work.
