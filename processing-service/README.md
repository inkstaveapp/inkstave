# `processing-service/`

Python image-processing and OCR pipeline that runs on the desktop side
(ADR-0002, `../docs/decisions/0002-processing-engine-language.md`). See
`../docs/image-pipeline.md` for what it does.

**M4:** the full image-cleanup + OCR pipeline (`src/inkstave_processing/pipeline/`)
is implemented and tested -- page detection, geometric correction
(perspective + dewarping + cropping, combined -- see `pipeline/geometry.py`'s
module doc for why), contrast/B&W cleanup, aspect-ratio normalization, and
OCR metadata extraction (`pipeline/ocr.py`) -- exposed end to end via a real
`POST /process-page` endpoint. The desktop client now actually calls this
for real, for every photo in a received capture session
(`client/shared/.../processing/ProcessingServiceClient.kt`,
`.../sync/CaptureSessionReceiver.kt`) -- see `ROADMAP.md`'s M4 entry for the
full picture, including its graceful-fallback-to-raw-import policy when this
service isn't reachable. Not intended to run on Android; the desktop client
is its only caller.

Nothing here needs to change to be called this way -- the client is a
consumer of the contract this service already exposed, not something this
service had to be modified for. One practical consequence worth knowing:
whoever runs the desktop client expects to be able to find this directory
with its `.venv` already set up nearby (a few directory levels around
wherever the desktop app's own working directory is) -- see
`ProcessingServiceLauncher`'s own doc in `client/README.md` for exactly what
it looks for and why that's a dev-checkout-only mechanism, not yet a real
release-packaging story (`ROADMAP.md` M7).

**System prerequisite: Tesseract OCR.** Unlike every other dependency this
service uses, Tesseract is a **system binary**, not something `pip install`
can provide (`pytesseract` is only a thin Python wrapper around it -- see
`NOTICE.md`). Install it via your OS package manager before running this
service or its tests, e.g. on Debian/Ubuntu:

```
sudo apt-get install tesseract-ocr
```

`which tesseract` should then succeed. This mirrors how `client/README.md`
documents needing an Android SDK installed outside Gradle -- a real,
external setup step, not something scaffolding can paper over.

## The pipeline (`src/inkstave_processing/pipeline/`)

```
pipeline/
├── cv_backend.py   Every OpenCV call, behind fully-typed wrappers (see its
│                   module doc -- opencv-python-headless ships real .pyi
│                   stubs, but not precise ones; this is where that gets
│                   narrowed down to this package's own exact types).
│                   Also image decode/encode (decode_image/encode_png),
│                   used by the /process-page endpoint (app.py).
├── geometry.py     Page detection + perspective correction + dewarping +
│                   cropping, combined into one operation (see its module
│                   doc for why chaining them separately would be wrong,
│                   and its honest scope: smooth boundary curl, not folds/
│                   creases)
├── contrast.py     Tunable CLAHE/adaptive-threshold blend (docs/image-pipeline.md's
│                   "faint pencil marks stay legible" requirement)
├── normalize.py    Aspect-ratio classification + padding + resize
├── ocr.py          Tesseract-backed title/subtitle/composer/arranger
│                   candidate extraction (M4 slice 2) -- always proposals,
│                   never auto-committed; see its module doc for the
│                   layout heuristic and why lyricist is deliberately not
│                   classified
├── types.py        Shared result types -- shaped to drop straight into
│                   inkstave_format's PageMeta/PageProcessing/PageOcr fields
└── run.py          process_page(): runs all of the above in order, OCR last
```

## The HTTP API

`GET /health` -- liveness check.

`POST /process-page?session_id=<id>&sequence_index=<n>&contrast_strength=<0-1>`
-- body is the raw image bytes (no wrapper/multipart encoding); runs the
full pipeline above and returns JSON (camelCase, matching `inkstave_format`'s
own convention throughout -- see `models.ProcessPageResponse`'s doc) with
the cleaned image (base64-encoded PNG), processing parameters, and OCR
candidates. Returns HTTP 422 (not 500) if no page can be detected in the
submitted image -- a real, expected outcome for a bad photo, not a server
error. `session_id`/`sequence_index` are accepted per
`docs/image-pipeline.md`'s documented contract but not yet used by anything
-- they exist for a later M4 slice (LAN capture-session handling) to make
use of. See `tests/test_process_page.py` for real request/response examples
exercised through `TestClient`.

No real camera-captured test photos exist yet (that flow isn't built). Tests
run against synthetic images: `tests/pipeline/fixtures.py` generates a flat
"page" with a border, horizontal rule lines, and vertical "barlines",
composed onto a background and optionally bowed with a known displacement
field, for the geometry/contrast/normalize stages; `tests/pipeline/test_ocr.py`
renders synthetic title-page images (real text, via PIL, at known positions/
sizes) for the OCR classification heuristic. Same spirit either way as the
client side generating synthetic PDFs with PDFBox rather than needing real
files.

```
.venv/bin/python -m pytest tests/pipeline -q          # pipeline unit/integration tests
.venv/bin/python -m pytest tests/test_process_page.py -q   # /process-page endpoint
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
