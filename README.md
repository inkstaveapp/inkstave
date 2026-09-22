# Inkstave

An open-source, cross-platform sheet music viewer and annotator — in the spirit of
[MobileSheets](https://www.zubersoft.com/mobilesheets/) and
[forScore](https://forscore.co) — with one key difference: **multi-device
integration**. A phone can capture photos of paper sheet music and stream them
live to a desktop, which uses its extra horsepower to clean up, flatten, crop,
and OCR the pages into a polished digital score that syncs back to every
device.

> **Status:** pre-implementation. This repository currently contains the
> plan, architecture, and AI-agent scaffolding for the project — not yet the
> application itself. See [ROADMAP.md](ROADMAP.md) for what's next.

## What it does (v1 target)

- **View** scores imported as PDF, individual images, or multi-page image
  sets, with page turning (including via a Bluetooth/USB pedal).
- **Annotate** scores: freehand highlighting, stamped music symbols (repeat,
  fermata, dynamics, fingerings, etc.), text notes — stored as vector layers
  so they scale cleanly across device sizes.
- **Capture** sheet music with a phone camera and stream it live over the
  local network to a desktop.
- **Process** captured pages on the desktop: perspective-correct and crop to
  the page bounds, flatten curled/warped pages, clean up contrast to a crisp
  black-and-white result, normalize to standard aspect ratios, and run OCR to
  extract title, subtitle, composer, arranger, and other metadata.
- **Organize** multiple parts of the same piece (e.g. separate instrument
  parts) into one logical score entry.
- **Sync** libraries and annotations across a user's devices.

Full recognition of music notation (OMR) to actually *understand* and align
parts is an explicit non-goal for v1 — see
[ADR-0004](docs/decisions/0004-omr-scope.md) — but the score format is
designed so it can be added later without a breaking migration.

## Platforms

v1 targets **Android** and **Linux desktop**, sharing one Kotlin Multiplatform
codebase. See [ADR-0001](docs/decisions/0001-client-framework.md) for why, and
[docs/architecture.md](docs/architecture.md) for how the pieces fit together.
Additional Compose Multiplatform targets (Windows, macOS, iOS, web) are a
plausible future expansion, not a v1 commitment.

## Repository layout

```
client/               Kotlin Multiplatform + Compose Multiplatform app
                       (shared/, androidApp/, desktopApp/ — to be scaffolded)
processing-service/   Python image-processing & OCR pipeline (desktop-side)
format/                Shared .smpk score-format schemas & validators
docs/                  Architecture, format spec, sync protocol, ADRs
.claude/agents/        Specialist AI subagents for each part of the project
```

## Working with this repo

This project is **AI-assisted, not AI-autonomous**. A human (the repo owner)
reviews and steers all significant work, and manual commits will happen
alongside agent-driven ones. If you're an AI agent picking up work here,
start with [CLAUDE.md](CLAUDE.md).

## License

Licensed under the [Apache License 2.0](LICENSE) (see that file for the
reasoning). Third-party dependencies and their licenses are tracked in
[NOTICE.md](NOTICE.md) — every new dependency must be added there. See
[.claude/agents/licensing-compliance.md](.claude/agents/licensing-compliance.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). There is no remote/hosted repository
yet — that gets set up once the project has something runnable.
