# Testing strategy

**The rule:** if a functional scenario should be under test, it is under
test. Not "would be nice to test" — every documented behavior (a
`ROADMAP.md` milestone's feature, a rule in another `docs/*.md`, an ADR's
stated consequence) gets a test at the level that actually catches its
regression, before the work implementing it is considered done. A feature
without tests is unfinished work, not finished work with a follow-up ticket.

This is a project-wide standard, alongside `docs/coding-standards.md`
(typesafety/documentation) and `docs/performance.md` (performance). Overall
strategy and CI gating is owned by the `qa-release` agent
(`.claude/agents/qa-release.md`), but **writing the tests for a feature is
the job of whichever specialist agent builds that feature**, not a separate
hand-off — the same way documentation is written by whoever writes the
code, not delegated away.

## Levels, and what each one is for in this project

Pick the cheapest level that would actually catch the regression — prefer
unit over integration, integration over end-to-end, when either would
catch the same bug. This keeps the suite fast enough to run often.

### Unit tests

Pure logic, isolated from I/O, the fastest and cheapest level:

- `format/`: field validation, migration functions between format
  versions, annotation-coordinate math (normalized-space conversions).
- `client/shared`: view-model/state logic, annotation hit-testing and
  spatial-index behavior (`docs/performance.md`), sync message
  serialization/deserialization.
- `processing-service/`: each pipeline stage's pure logic in isolation
  (given a fixed input array/image, assert the output shape and key
  properties — e.g. crop stage returns a polygon within image bounds), OCR
  field-classification heuristics against fixed OCR-output fixtures.

### Integration tests

Two real components talking to each other, still without a full UI:

- Cross-language format round-trip: write a `.smpk` with the Kotlin
  implementation, read it with the Python one, and vice versa — every
  format version gets a fixture exercised both directions. This is the
  concrete test of `docs/format-spec.md`'s "unknown fields are preserved"
  and `score-format`'s migration-compatibility rules.
- Desktop client ↔ processing-service: real HTTP/WebSocket calls against
  the documented API contract (`docs/image-pipeline.md`), not a mocked
  transport, at least for the happy path and the documented error cases.
- Sync pairing and transfer (`docs/sync-protocol.md`) between two local
  instances (e.g. two processes on loopback standing in for two devices):
  discovery, pairing handshake, a capture-session transfer, rejection of an
  unpaired connection attempt.
- Local index database (ADR-0005): writes to the library produce a
  consistent index; a deleted/corrupted index is correctly rebuilt from
  `.smpk` files alone — this is the concrete test of ADR-0005's central
  claim, not just an assumption to trust.

### End-to-end tests

A full user-facing flow, exercised as close to how a user actually
experiences it as practical (instrumented/UI tests on Android, a real
desktop app run for Linux, `webapp-testing`-style interaction where a
web-based harness is used for iteration):

At minimum, one e2e test per `ROADMAP.md` milestone's headline
capability, for example:
- M1: import a PDF, see it in the library, open it, turn pages.
- M2: annotate a page, close and reopen the score, annotation is still
  there and in the right place.
- M3: a simulated pedal input event turns the page.
- M4: a simulated capture session delivers photos to the desktop, the
  pipeline runs, a draft score with OCR-suggested metadata appears for
  confirmation.
- M6: an annotation edited on one (simulated) device appears on another
  after sync.

### Spec / contract tests

Schema- and contract-level checks, distinct from integration tests because
they check *shape*, not *behavior*:

- JSON Schema validation for every `.smpk` component (`manifest.json`,
  `part.json`, page metadata, annotation layers) against fixtures for every
  supported format version.
- The processing-service API request/response shapes validated against
  their schema in `format/`, independent of the pipeline logic behind them.

## What's exempt, and why

Not everything gets an automated test — but the exemption has to be a real
reason, not convenience:

- Genuinely hardware-dependent behavior that can't be simulated
  meaningfully (does a specific physical Bluetooth pedal actually send the
  expected HID event) stays a documented manual verification step
  (`qa-release` tracks these), with as much of the surrounding logic
  (mapping a HID event to a "turn page" action) still unit-tested with a
  synthetic event.
- Visual/design polish with no behavioral assertion to make (does this
  spacing look right) is a manual/design review, not a test — but the
  underlying state/logic it renders from still is.

## Ownership

- `qa-release`: overall strategy, CI wiring, the milestone-level e2e
  fixtures, and the performance stress-test gates in `docs/performance.md`.
- Every other specialist agent: unit/integration tests for the area it
  implements, written alongside the feature, not after.
