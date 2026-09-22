# `processing-service/`

Python image-processing and OCR pipeline that runs on the desktop side
(ADR-0002, `../docs/decisions/0002-processing-engine-language.md`). See
`../docs/image-pipeline.md` for what it does.

**M0 scaffolding only:** the actual pipeline (perspective correction,
dewarping, crop, contrast/B&W cleanup, OCR) isn't implemented yet -- that's
M4 (`../ROADMAP.md`), owned by the `image-pipeline` agent. What exists so
far is the service shell: a FastAPI app with a `/health` endpoint, bound to
loopback only, with its own test suite and strict typing wired up. Not
intended to run on Android; the desktop client is its only caller.

## Setup

Depends on `inkstave-format` (`../format/python`) as a local, editable
package -- install that first, into the same virtual environment:

```
python -m venv .venv
.venv/bin/pip install -e ../format/python
.venv/bin/pip install -e ".[dev]"
```

## Running

```
.venv/bin/python -m inkstave_processing.server
```

Binds to `127.0.0.1:8787` only -- never a network-reachable interface, per
`docs/image-pipeline.md`. Verify with `curl http://127.0.0.1:8787/health`.

## Checks

```
.venv/bin/ruff check .
.venv/bin/ruff format --check .   # --check omitted, to auto-format
.venv/bin/mypy src/inkstave_processing tests   # mypy --strict, per docs/coding-standards.md
.venv/bin/python -m pytest -q
```

All four are required to pass before a change here is done -- see
`docs/coding-standards.md` and `docs/testing-strategy.md`.

Owned by the `image-pipeline` agent — see `../.claude/agents/image-pipeline.md`.
