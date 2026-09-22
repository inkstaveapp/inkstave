# ADR-0005: `.smpk` files remain the source of truth; a local SQL index is a derived, rebuildable cache

**Status:** accepted
**Date:** 2026-09-23

## Context

`.smpk` files (`docs/format-spec.md`) are the portable, syncable unit of
data — that's deliberate, so sync (ADR-0003) can move/diff individual files
without a server or shared database. But a library screen needs to list,
search, sort, and filter potentially thousands of scores
(`docs/performance.md`) quickly, and re-opening and parsing every `.smpk`
in the library on every screen load or keystroke of a search box does not
scale to that requirement.

## Options considered

- **Files only, no cache** — simplest, one source of truth, nothing to keep
  in sync. Fails the performance requirement outright at nontrivial library
  sizes: listing/search would mean scanning and parsing every `.smpk` in the
  library directory on demand.
- **A database as the actual source of truth** (metadata and/or annotations
  stored primarily in a database, `.smpk` files generated from it on
  export) — fast queries, but breaks the design this project already
  committed to: `.smpk` files being independently portable, syncable, and
  meaningful on their own (ADR-0003's LAN sync moves files; a
  database-as-truth model would need its own sync/merge story on top of, or
  instead of, file sync). Also makes "the file is what it says it is" less
  true, since the database could drift from exported files.
- **Files remain the source of truth; a local SQL index is a derived,
  disposable cache** — the library folder of `.smpk` files is authoritative;
  each device additionally maintains a local SQL database (recommended:
  [SQLDelight](https://cashapp.github.io/sqldelight/), Apache-2.0, generates
  typesafe Kotlin from `.sq` files, works across the Android and JVM/desktop
  targets ADR-0001 already committed to) that mirrors each `.smpk`'s
  `manifest.json` fields (title, composer, tags, part list, timestamps,
  etc.) purely for fast querying. The index is rebuilt by rescanning the
  library whenever it's missing, stale, or suspected corrupt — it is never
  the only copy of anything.

## Decision

Files remain the single source of truth. A local SQLDelight-backed SQL
database is a derived index/cache of manifest metadata, scoped to a single
device, never synced directly (sync moves `.smpk` files; the receiving
device re-indexes them locally), and safe to delete and rebuild at any
time.

## Consequences

- Library list/search/sort/filter becomes an indexed SQL query, meeting the
  responsiveness target in `docs/performance.md`, instead of scaling with
  library size on every interaction.
- The index only needs to store what's queried against (manifest-level
  metadata, not page images or annotation content) — it stays small and
  fast to rebuild.
- Every write path that changes a `.smpk`'s manifest (import, edit
  metadata, receive via sync) must also update the index in the same
  operation, or explicitly mark it stale for a background re-index — this
  is a real synchronization responsibility `score-format`/`client-ui` must
  own deliberately, not an incidental detail.
- Because the index is disposable, a corrupted or out-of-date index is a
  "rebuild it" bug, never a data-loss bug — that property must be
  preserved by any future change to this design; if the index ever becomes
  something that can't be safely regenerated from the files, that's a
  regression of this decision.
- SQLDelight (or whatever concrete library is chosen at implementation
  time) needs the usual `NOTICE.md` entry and license check
  (`licensing-compliance`) when it's actually added in M0/M1.
