# `processing-service/`

Python image-processing and OCR pipeline that runs on the desktop side
(ADR-0002, `../docs/decisions/0002-processing-engine-language.md`). See
`../docs/image-pipeline.md` for what it does.

**Not yet scaffolded** — this is a placeholder for M0
(`../ROADMAP.md`). Not intended to run on Android; the desktop client is its
only caller, over a local-only API defined in `../format/`.

Owned by the `image-pipeline` agent — see `../.claude/agents/image-pipeline.md`.
