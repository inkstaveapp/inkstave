# `processing-service/`

Python image-processing and OCR pipeline that runs on the desktop side
(ADR-0002, `../docs/decisions/0002-processing-engine-language.md`). See
`../docs/image-pipeline.md` for what it does.

**M4, first slice:** the image-cleanup pipeline itself
(`src/inkstave_processing/pipeline/`) is implemented and tested -- page
detection, geometric correction (perspective + dewarping + cropping,
combined -- see `pipeline/geometry.py`'s module doc for why), contrast/B&W
cleanup, and aspect-ratio normalization. **Not yet built:** camera capture,
LAN sync, OCR, and a real HTTP endpoint wiring the pipeline into the
service (the existing FastAPI app still only has `/health` -- see
`ROADMAP.md`'s M4 entry for exactly what's done vs. still ahead). Not
intended to run on Android; the desktop client is its only caller.

## The pipeline (`src/inkstave_processing/pipeline/`)

```
pipeline/
├── cv_backend.py   Every OpenCV call, behind fully-typed wrappers (see its
│                   module doc -- opencv-python-headless ships real .pyi
│                   stubs, but not precise ones; this is where that gets
│                   narrowed down to this package's own exact types)
├── geometry.py     Page detection + perspective correction + dewarping +
│                   cropping, combined into one operation (see its module
│                   doc for why chaining them separately would be wrong,
│                   and its honest scope: smooth boundary curl, not folds/
│                   creases)
├── contrast.py     Tunable CLAHE/adaptive-threshold blend (docs/image-pipeline.md's
│                   "faint pencil marks stay legible" requirement)
├── normalize.py    Aspect-ratio classification + padding + resize
├── types.py        Shared result types -- shaped to drop straight into
│                   inkstave_format's PageMeta/PageProcessing fields
└── run.py          process_page(): runs all of the above in order
```

No real camera-captured test photos exist yet (that flow isn't built). Tests
run against synthetic images generated in `tests/pipeline/fixtures.py`
(a flat "page" with a border, horizontal rule lines, and vertical
"barlines", composed onto a background and optionally bowed with a known
displacement field) -- the same spirit as the client side generating
synthetic PDFs with PDFBox rather than needing real files.

```
.venv/bin/python -m pytest tests/pipeline -q
```

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
