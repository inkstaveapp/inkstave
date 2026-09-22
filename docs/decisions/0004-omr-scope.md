# ADR-0004: Optical Music Recognition (OMR) is out of scope for v1

**Status:** accepted
**Date:** 2026-09-23

## Context

The idea of combining separate instrument parts of the same piece into a
rendered "full score" view, or otherwise truly *understanding* music
notation (not just images of it), requires OMR — recognizing noteheads,
staves, rhythms, etc. as structured data. This is a substantially harder,
more open-ended problem than the raster image cleanup pipeline
(ADR-0002), and the project's core value (view, annotate, capture-and-clean,
sync) doesn't require it.

## Options considered

- **Out of scope for v1; design the format to allow it later** — v1 stores
  cleaned raster page images, metadata, and manual vector annotations only,
  matching what MobileSheets/forScore already do well today. Multi-part
  "combination" in v1 means grouping parts under one score entry with a
  part-switcher UI (M5), not rendering them merged. `docs/format-spec.md`
  reserves an obvious location for OMR data per part so it can be added
  without a breaking format migration.
- **Integrate an existing OMR engine now** (e.g. Audiveris, Java/AGPL
  -licensed) — would enable real part-alignment/combination from the start,
  but meaningfully expands v1 scope, adds a JVM-based OMR dependency with
  copyleft licensing implications to review (`NOTICE.md` /
  `licensing-compliance` agent) regardless of which language stack
  consumes it, and risks delaying the core viewer/capture/sync experience
  this project actually differentiates on.
- **Build custom OMR** — explicitly rejected; far outside this project's
  scope and duplicates a hard research problem other open-source projects
  already work on.

## Decision

OMR is deferred past v1. The `.smpk` format (`docs/format-spec.md`)
deliberately structures per-part data so an OMR data file could be added to
a `parts/<part-id>/` directory later as an additive, non-breaking change.

## Consequences

- v1's "combine parts" feature (M5) is organizational (grouping + switching
  between parts of the same piece), not a rendered merge of notation.
- If OMR is revisited later, integrating an existing open-source engine
  (Audiveris is the leading candidate) is preferred over building one, and
  must go through the same licensing review as any other dependency before
  being added — Audiveris specifically is AGPL-3.0, which has real
  implications for how it could be distributed/invoked and needs explicit
  sign-off, not just a `NOTICE.md` entry.
- No format migration should be required to add OMR data later, if the
  reserved structure holds up — that's a design constraint on any format
  change made before OMR is revisited, not just on OMR's eventual addition.
