# `.smpk` score format — draft spec

**Status: proposal, not yet implemented.** Naming (`.smpk` / "Sheet Music
Package") is a placeholder — open to bikeshedding before M0's format tooling
is built, but pick *something* and move rather than blocking on a name.

## Goals

- Hold everything a score needs to be viewed and annotated: page images,
  vector annotations, metadata, and (for multi-part scores) grouping.
- Be reproducible: keep enough of the original + processing parameters that
  a page can be reprocessed later without re-capturing it.
- Be forward-compatible with features not built yet, especially OMR
  (ADR-0004) and cloud sync (ADR-0003), without forcing a breaking migration.
- Be diffable/sync-friendly: small, independent files rather than one opaque
  blob, so a sync transport can send just what changed.

## Container

A `.smpk` file is a **zip archive** (uncompressed or `deflate`), chosen
because it's a ubiquitous, well-supported, streamable, partially-updatable
container in both Kotlin (`java.util.zip`) and Python (`zipfile`) with no new
dependency required.

```
score.smpk
├── manifest.json
├── parts/
│   ├── <part-id>/
│   │   └── part.json
│   └── ...
├── pages/
│   ├── <page-id>.webp            # cleaned, display-ready raster
│   ├── <page-id>.raw.jpg         # optional: original capture, kept for reprocessing
│   └── <page-id>.meta.json       # per-page processing/OCR metadata
├── annotations/
│   └── <page-id>.json            # vector annotation layer for that page
└── thumbnails/                    # optional, regenerable cache — excluded from sync payloads
    └── <page-id>.webp
```

## `manifest.json`

```json
{
  "formatVersion": 1,
  "id": "uuid",
  "title": "string",
  "subtitle": "string | null",
  "composer": "string | null",
  "arranger": "string | null",
  "lyricist": "string | null",
  "genre": "string | null",
  "tags": ["string"],
  "parts": ["part-id", "..."],
  "createdAt": "ISO-8601",
  "modifiedAt": "ISO-8601",
  "source": {
    "type": "pdf-import | image-import | camera-capture",
    "details": { "...": "type-specific, e.g. original filename" }
  }
}
```

Metadata fields are deliberately a flat, OCR-friendly set matching what
title/subtitle/composer/arranger/lyricist OCR extraction (M4) can plausibly
populate — see `docs/image-pipeline.md`. Unrecognized fields must be
preserved on round-trip (forward compatibility), not dropped.

## `parts/<part-id>/part.json`

```json
{
  "id": "part-id",
  "name": "string (e.g. \"Trumpet in B♭\", \"Piano\", \"Full Score\")",
  "pageOrder": ["page-id", "..."]
}
```

A single-part score (the v1 common case) still has exactly one entry here —
no special-cased "no parts" mode, so M5's multi-part support is additive,
not a migration.

## `pages/<page-id>.meta.json`

```json
{
  "id": "page-id",
  "width": 0,
  "height": 0,
  "aspectRatioClass": "a4 | letter | custom",
  "processing": {
    "cropPolygon": [[0, 0]],
    "dewarpMeshVersion": "string | null",
    "contrastMethod": "string | null"
  },
  "ocr": {
    "engineVersion": "string",
    "candidates": { "title": "string", "composer": "string" },
    "confidence": { "title": 0.0 }
  }
}
```

`processing` and `ocr` are populated by the processing service
(`docs/image-pipeline.md`) and are advisory/reproducibility data — the
client never needs to parse them to render the page, only to offer
"reprocess this page" or to show OCR suggestions for confirmation.

## `annotations/<page-id>.json`

Vector layer, one file per page (so annotation edits sync independently of
page images):

```json
{
  "pageId": "page-id",
  "layerVersion": 1,
  "strokes": [{ "id": "...", "points": [[0,0]], "color": "#RRGGBB", "widthPt": 0 }],
  "stamps": [{ "id": "...", "symbol": "fermata", "x": 0, "y": 0, "scale": 1.0, "rotationDeg": 0 }],
  "highlights": [{ "id": "...", "rectPt": [0,0,0,0], "color": "#RRGGBBAA" }],
  "textNotes": [{ "id": "...", "x": 0, "y": 0, "text": "string", "fontSizePt": 0 }]
}
```

Coordinates are in a normalized page-point space (independent of the
rendered raster's pixel dimensions) so the same annotation renders correctly
at any display size — this is the mechanism that satisfies the "render
correctly on different device sizes" requirement for annotations
specifically (page images themselves are normalized per
`docs/image-pipeline.md`).

One annotation-layer file per page, rather than one file for a whole score,
is also a deliberate performance choice: it bounds how much annotation data
must be loaded to interact with the page currently on screen to that one
page's data, regardless of how many pages or how much annotation history
the rest of the score carries. See `docs/performance.md` for the full
rendering-performance design (viewport culling, spatial indexing) this
enables.

## Local index vs. source of truth

`.smpk` files on disk are the **only** source of truth — for a score's
content, metadata, and annotations alike. They are what syncs between
devices (`docs/sync-protocol.md`) and what's portable/backed-up/moved
around outside the app.

They are not, however, what the library screen queries directly at scale.
Each device additionally maintains a local SQL database (SQLDelight —
see [ADR-0005](decisions/0005-local-library-index-database.md)) that mirrors
`manifest.json` fields for fast list/search/sort/filter. That database is a
**derived, disposable cache**, never a second source of truth:

- It is rebuilt by rescanning the library's `.smpk` files whenever it's
  missing or stale — never manually reconciled.
- It is never synced directly; a device that receives a new/updated `.smpk`
  via sync re-indexes it locally.
- Losing it is a "rebuild" event, never a data-loss event. Any format or
  client change that would make the index un-rebuildable from files alone
  is a regression of ADR-0005 and needs to be treated as one.
- It stores only what's queried against (manifest-level fields) — page
  images, raw captures, and annotation content are never duplicated into
  it.

## Versioning & migration policy

- `manifest.json.formatVersion` gates the whole package.
- Additive changes (new optional field) do not bump the version.
- Breaking changes (removing/renaming a field, changing a type) bump it and
  require a migration function in both the Kotlin and Python
  readers/writers, tested round-trip against a fixture of the previous
  version.
- Unknown fields are always preserved on read-modify-write, never dropped —
  this is what lets, e.g., a future OMR engine attach data
  (`parts/<part-id>/omr.json`, hypothetically) without every older client
  needing to understand it to avoid destroying it.

## Reserved for later (not built now)

- An OMR data file per part (e.g. `parts/<part-id>/notation.musicxml` or
  similar) — deliberately not designed in detail yet per ADR-0004, but the
  directory structure already has an obvious place for it to live without
  restructuring the container.
- A `sync/` manifest for cloud/relay transport bookkeeping (ADR-0003) if
  that's ever added — kept out of `manifest.json` itself so local-only users
  never carry that data.

## Ownership

This spec is owned by the `score-format` agent
(`.claude/agents/score-format.md`); the actual JSON Schemas and
Kotlin/Python reader-writer implementations live in `format/` once M0
scaffolds them. Both implementations are typed models mirroring these
schemas, not raw dict/map parsing — see `docs/coding-standards.md`.
