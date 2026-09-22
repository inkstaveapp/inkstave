# CLAUDE.md — instructions for AI agents working in this repo

This file is read automatically by Claude Code. Read it before doing anything
else in this repository.

## What this project is

**Inkstave** is a cross-platform (Android + Linux desktop, v1) sheet-music
viewer/annotator with live phone-to-desktop capture and processing, and
multi-device sync. See [README.md](README.md) for the product pitch and
[ROADMAP.md](ROADMAP.md) for what phase we're in.

## How this project is developed

**AI-assisted, human-supervised — not AI-autonomous.** The repo owner
(Bas) actively monitors this project and will make manual edits, commits, and
architectural calls himself, sometimes without an agent involved. Do not
assume you have full context of the latest state from this file alone:

- Always check `git log` / `git status` / `git diff` for what's actually
  changed recently before assuming a doc or ADR is still current.
- If code and docs disagree, trust the code, then flag the doc as stale
  rather than silently "fixing" the doc to match your assumptions.
- Do not restructure the repo layout, rename the score format, or reverse an
  ADR decision on your own initiative — raise it and let the human decide,
  the same way you would for any other consequential, hard-to-reverse change.

## Foundational decisions already made

These are settled (see `docs/decisions/` for the full ADRs) — don't
re-litigate them without the human explicitly asking:

1. **Client:** Kotlin Multiplatform + Compose Multiplatform, targeting
   Android and Linux desktop from one shared codebase.
   ([ADR-0001](docs/decisions/0001-client-framework.md))
2. **Processing engine:** Python, running as a local sidecar service the
   desktop client talks to, using OpenCV/Tesseract/scikit-image for
   dewarping, cropping, contrast cleanup, and OCR.
   ([ADR-0002](docs/decisions/0002-processing-engine-language.md))
3. **Sync:** LAN-first, direct device-to-device (mDNS discovery + a local
   transport), with the transport abstracted so a cloud/relay option can be
   added later without touching sync logic.
   ([ADR-0003](docs/decisions/0003-sync-approach.md))
4. **OMR (true music-notation recognition):** out of scope for v1. The score
   format reserves room for it later.
   ([ADR-0004](docs/decisions/0004-omr-scope.md))
5. **Local data:** `.smpk` files on disk are the only source of truth. A
   local SQL index (SQLDelight) is a derived, per-device, rebuildable cache
   used purely for fast library search/sort — never synced, never the only
   copy of anything.
   ([ADR-0005](docs/decisions/0005-local-library-index-database.md))

## Where to look

| Question | Read |
|---|---|
| How do the pieces fit together? | `docs/architecture.md` |
| What does the `.smpk` score file actually contain? | `docs/format-spec.md` |
| How do devices find and talk to each other? | `docs/sync-protocol.md` |
| What does the capture → clean-up → OCR pipeline do, step by step? | `docs/image-pipeline.md` |
| What keeps dense-annotation pages and large libraries fast? | `docs/performance.md` |
| What are the typesafety/documentation rules? | `docs/coding-standards.md` |
| What needs a test, at what level? | `docs/testing-strategy.md` |
| What's built, what's next? | `ROADMAP.md` |
| Why was X decided this way? | `docs/decisions/*.md` |

## Specialist agents

For focused work, prefer delegating to the matching subagent in
`.claude/agents/` rather than working broadly yourself — they carry the
relevant domain context and constraints:

- `client-ui` — Compose Multiplatform UI, shared client business logic, page
  rendering/turning, pedal input, annotation UI.
- `android-platform` — Android-specific integration (storage/SAF, permissions,
  camera capture, Bluetooth/USB pedal HID, background services).
- `linux-desktop` — Linux desktop integration (packaging: Flatpak/AppImage,
  window management, filesystem conventions, launching the processing
  service).
- `image-pipeline` — the Python processing service: perspective correction,
  dewarping, crop, contrast/B&W cleanup, aspect-ratio normalization.
- `sync-network` — device discovery, pairing/trust, the transfer protocol,
  capture-session handling.
- `score-format` — the `.smpk` container format, its schemas, versioning, and
  migrations; OCR-extracted metadata field mapping.
- `licensing-compliance` — vetting new dependencies' licenses, keeping
  `NOTICE.md` accurate, flagging anything copyleft/incompatible.
- `qa-release` — test strategy across Kotlin and Python, CI, and packaging
  for release.

## Working conventions

- No remote/hosted repo exists yet (see README). Don't add a `git remote`,
  push, or assume a hosting provider (GitHub org, CI provider, etc.) without
  being told — the human said a remote gets created once something runnable
  exists.
- Every new third-party dependency (library, model, font, dataset) must be
  license-checked and recorded in `NOTICE.md` before or alongside the commit
  that introduces it. Use the `licensing-compliance` agent for this.
- **Typesafe everywhere, including Python.** No untyped Python, no `Any`
  used to dodge modeling a type properly, `mypy --strict` (or equivalent) as
  a CI gate once CI exists. See `docs/coding-standards.md` — this applies to
  every language in this repo without exception.
- **Document as you go, in both layers.** Every public function/class/module
  gets a real doc comment (KDoc/docstring) explaining purpose and non-obvious
  behavior — not a restated signature. Every doc in `docs/` that describes
  behavior you just changed gets updated in the same change. Undocumented
  public API or a stale doc is incomplete work, not a follow-up. See
  `docs/coding-standards.md`.
- **If it should be tested, it is tested.** Every functional scenario —
  unit, integration, end-to-end, or spec/contract level, whichever is
  cheapest and still catches the regression — gets a test as part of
  implementing it, not as a follow-up. Write the tests for a feature
  yourself when you build it; don't hand that off. See
  `docs/testing-strategy.md`.
- Prefer small, reviewable commits. The human reviews history, so keep
  messages accurate about *why*, not just *what*.
- This is greenfield: don't build speculative abstractions ahead of the
  milestone in `ROADMAP.md` that actually needs them.
