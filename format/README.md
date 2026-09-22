# `format/`

Shared schemas and reader/writer tooling for the `.smpk` score format
(`../docs/format-spec.md`) and the client↔processing-service API contract
(`../docs/image-pipeline.md`), used by both `client/` (Kotlin) and
`processing-service/` (Python) so the two never drift out of sync.

**Not yet scaffolded** — this is a placeholder for M0 (`../ROADMAP.md`).
Planned contents: JSON Schema definitions for `manifest.json`, `part.json`,
page metadata, and annotation layers; a minimal Kotlin reader/writer; a
minimal Python reader/writer; round-trip fixtures used by both.

Owned by the `score-format` agent — see `../.claude/agents/score-format.md`.
