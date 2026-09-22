# Performance requirements

**Status: draft requirements**, to be validated with real benchmarks once
M1/M2 are implemented (owned jointly by `client-ui` and `qa-release`, see
`.claude/agents/`). Two distinct scaling concerns, addressed by two
different mechanisms — don't conflate them.

## 1. Heavily annotated pages must stay smooth

A page can accumulate a large number of annotation objects over time
(strokes, stamps, highlights, text notes — `docs/format-spec.md`'s
`annotations/<page-id>.json`) — think a well-worn part with years of pencil
-equivalent markings. Rendering, panning, and zooming that page must not
visibly degrade as annotation count grows.

**Target:** pan/zoom on a page with several hundred annotation objects
holds the platform's native smooth frame rate (effectively 60fps on typical
Android/Linux hardware) — no visible stutter. This is a concrete,
testable bar, not "feels fine."

**How, at a design level** (owned by `client-ui`, informed by
`score-format` on data shape):

- **Viewport culling:** only annotation objects intersecting the current
  visible (and near-visible, for smooth scroll-ahead) viewport are drawn or
  hit-tested each frame — never iterate every annotation on the page
  unconditionally on every frame.
- **Spatial indexing:** maintain a spatial index (e.g. a quad-tree or grid
  bucketing) over a page's annotation objects so culling and tap/hit-testing
  are sub-linear in annotation count, not an O(n) scan per interaction.
- **Vector rendering, not re-rasterization:** annotations are drawn as
  vector primitives on top of the page raster (Compose `Canvas`/`Path`),
  never baked into a re-rasterized bitmap on every edit — that would make
  every stroke cost scale with page resolution instead of stroke
  complexity.
- **Incremental invalidation:** an edit to one annotation invalidates only
  the affected region, not a full-page recomposition/redraw.
- **This is why the format stores one JSON file per page
  (`docs/format-spec.md`)** rather than one file for a whole score: it
  bounds how much annotation data must be loaded/parsed to interact with
  the page currently on screen, independent of how large the rest of the
  score is.

**QA gate** (`qa-release`): a stress-test fixture — a page pre-populated
with a large synthetic annotation set (e.g. 500+ mixed strokes/stamps/
highlights) — is part of the test suite once M2 lands, checked against the
frame-rate target above, not just "does it render at all."

## 2. Large libraries must stay fast

Independent of any single page's annotation density: a user's library can
grow to hold many scores (hundreds, potentially thousands, across years of
importing/capturing music). Listing, searching, sorting, and filtering the
library must not degrade proportionally to its size in a way the user
notices.

**Target:** opening the library screen and searching/filtering it stays
responsive (near-instant, no visible loading stall) at library sizes into
the low thousands of scores.

**How:** see [ADR-0005](decisions/0005-local-library-index-database.md) and
`docs/format-spec.md`'s "Local index vs. source of truth" section — a local
SQL index (SQLDelight-backed) mirrors each `.smpk`'s manifest metadata, so
library queries are indexed SQL queries against a small local database, not
"open and parse every `.smpk` in the library on every list/search."

## Explicit non-goals

- These targets are about UI responsiveness for a human interacting with
  the app, not raw throughput benchmarks disconnected from a real
  interaction (e.g. "parse N files in X ms" only matters insofar as it
  shows up as a stall the user would feel).
- The processing-service pipeline (`docs/image-pipeline.md`) has its own,
  separate performance characteristics (it's an offline batch step per
  captured photo, not an interactive-frame-rate concern) and isn't covered
  by the frame-rate target above.
