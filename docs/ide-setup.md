# Opening the whole repo in one IDE

Inkstave has three parts: a Gradle/Kotlin Multiplatform project (`client/`)
and two Python packages (`format/python`, `processing-service`). IntelliJ
IDEA Ultimate can hold all three in one window, because it gives each module
its own SDK.

## Python environments

Each Python package has its own virtual environment inside its directory.
This is the one layout used everywhere: the package READMEs, CI,
pre-commit, and the desktop app (`ProcessingServiceLauncher` starts the
service from `processing-service/.venv`).

```
(cd format/python && python3 -m venv .venv && .venv/bin/pip install -e ".[dev]")
(cd processing-service && python3 -m venv .venv && .venv/bin/pip install -e ../format/python && .venv/bin/pip install -e ".[dev]")
```

`processing-service` also needs the Tesseract OCR binary; see
`processing-service/README.md`.

Keeping them separate proves `format/python` stands on its own:
nothing it needs can leak in from `processing-service`'s dependencies.

If you set up the older single `.venv` at the repo root, nothing uses it any
more and it can be deleted.

## IntelliJ IDEA Ultimate

1. **Open the repo root** (`File → Open`, the top-level directory, not
   `client/`).
2. **Link the Gradle project.** Accept IntelliJ's prompt to import
   `client/settings.gradle.kts` (or `File → New → Module from Existing
   Sources…` and pick `client/build.gradle.kts`).
3. **Add both interpreters.** `File → Project Structure → SDKs → + → Add
   Python SDK → Existing environment`, once for
   `format/python/.venv/bin/python` and once for
   `processing-service/.venv/bin/python`.
4. **Assign them per module.** In `Project Structure → Modules`, add
   `format/python` and `processing-service` as Python modules, each with its
   own interpreter from step 3. Mark each package's `src/` as a sources root
   and `tests/` as a test root.
5. **Run tests** by right-clicking a test file or directory; IntelliJ uses
   the module's interpreter. The lint and type-check commands are in each
   package's README.

These are manual steps rather than committed `.idea/` files, because
IntelliJ's project-file format changes between versions.
