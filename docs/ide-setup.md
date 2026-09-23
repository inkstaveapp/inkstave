# Opening the whole repo in one IDE

Inkstave is genuinely polyglot: a Gradle/Kotlin Multiplatform project
(`client/`) plus two separately-installable Python packages
(`format/python`, `processing-service`). Historically each Python package
documented its own separate virtual environment, which made loading
everything into one IDE window (rather than switching between an IDE for
Kotlin and a separate one for Python) more annoying than it needed to be —
most IDEs that support both Gradle and Python (IntelliJ IDEA Ultimate,
which is what these instructions cover; PyCharm Professional can attach a
Gradle-aware Kotlin facet too) want one Python interpreter per project/
module, and two undocumented, independently-created venvs don't give you
that for free.

## The fix: one shared virtual environment for both Python packages

`processing-service` already depends on `inkstave-format`
(`format/python`) as a local editable install — the two are not
independent in practice, so there's no real isolation benefit to giving
them separate environments for day-to-day development (CI still installs
them separately per job, which *is* worth keeping — see "What CI still
does differently" below). One venv at the repo root, with both packages
installed into it editable, works for both:

```
python3 -m venv .venv
.venv/bin/pip install -e format/python
.venv/bin/pip install -e "processing-service[dev]"
```

That's it — `inkstave-format` and `inkstave-processing` (with its `[dev]`
extras: mypy, ruff, pytest, and everything `format/python`'s own dev
extras pulled in too, all resolved to one consistent version set) are now
both live-editable from this one environment. Verify it actually works
exactly the way the per-package instructions in `format/README.md` and
`processing-service/README.md` do, just pointing both at `.venv/bin/`
instead of two separate ones:

```
(cd format/python && ../../.venv/bin/ruff check . && ../../.venv/bin/mypy src/inkstave_format tests && ../../.venv/bin/python -m pytest -q)
(cd processing-service && ../.venv/bin/ruff check . && ../.venv/bin/mypy src/inkstave_processing tests && ../.venv/bin/python -m pytest -q)
```

`.venv/` at the repo root is already covered by the existing blanket
`.venv/` pattern in `.gitignore` — nothing extra to add there.

**A real bug this surfaced:** setting this up for the first time, `mypy
--strict` failed on `format/python` with "Library stubs not installed for
jsonschema" — `types-jsonschema` had been installed into one already-set
-up `.venv` at some point but was never actually declared in
`format/python/pyproject.toml`'s `dev` dependencies or recorded in
`NOTICE.md`, so a genuinely fresh environment (exactly what creating this
shared venv did) failed a check that had been silently passing only
because of that one machine's undeclared, untracked state. Fixed by
declaring it properly — see `format/python/pyproject.toml` and
`NOTICE.md`.

## IntelliJ IDEA Ultimate: one project, both languages

IntelliJ IDEA Ultimate supports Gradle/Kotlin and Python (via its bundled
Python plugin) in the same project window, with **per-module** SDKs — the
Gradle module doesn't need to "agree" with the Python modules on a single
interpreter, so this works cleanly:

1. **Open the repo root** as the project folder (`File → Open`, select this
   repo's top-level directory — not `client/`).
2. **Attach the Gradle project**: IntelliJ should detect
   `client/settings.gradle.kts` and offer to import it as a linked Gradle
   project; accept it (or `File → New → Module from Existing Sources...`,
   pointing at `client/build.gradle.kts`, if it doesn't prompt
   automatically). This gives you the Kotlin Multiplatform module
   (`shared`/`androidApp`/`desktopApp`) with full Gradle-aware
   indexing/run configurations, unaffected by anything below.
3. **Attach the shared Python interpreter**: `File → Project Structure →
   Platform Settings → SDKs → +  → Add Python SDK → Existing environment`,
   and point it at `.venv/bin/python` (the one created above, at the repo
   root). Name it something recognizable, e.g. "Python 3.12 (Inkstave)".
4. **Mark `format/python` and `processing-service` as source roots using
   that SDK**: still in `Project Structure → Modules`, either let
   IntelliJ auto-detect them as Python modules once the interpreter above
   is attached and you right-click each directory → `Mark Directory as →
   Sources Root`, or add each explicitly as a module pointing at that
   directory with the SDK from step 3 selected as its module SDK. Both
   `format/python/src` and `processing-service/src` (plus each package's
   `tests/`) should resolve imports correctly against the one shared
   interpreter once this is done — including `inkstave_format` imports
   from inside `processing-service`, since it's installed editable into
   that same environment.
5. **Run configurations**: pytest run configurations for either package
   now just work by right-clicking a test file/directory, since IntelliJ
   picks up the attached SDK per-module; same for `ruff`/`mypy` if you use
   IntelliJ's external-tool integration for them, or just run the exact
   commands in `format/README.md`/`processing-service/README.md`/above
   from IntelliJ's built-in terminal.

This is genuinely one IDE window: Kotlin Multiplatform code, gradle tasks,
and both Python packages, all with correct indexing/navigation/run
configurations, no switching to a second IDE for the Python half.

(These are the manual setup steps, not committed `.idea/` project files —
IntelliJ's exact XML schema for SDK/module configuration shifts often
enough between versions that committing it risked being wrong for
whatever version you're actually running, and it's a five-minute one-time
setup either way.)

## What CI still does differently, on purpose

`.github/workflows/ci.yml` still installs `format/python` and
`processing-service` into **separate** venvs per job. That's deliberate,
not an oversight this doc's shared venv should "fix" too: it's what
actually proves `format/python` stands on its own without silently
depending on something `processing-service`'s environment happens to
provide (and vice versa isn't claimed — `processing-service` has always
depended on `format/python`). The shared venv above is a **local
development / IDE convenience only**; it doesn't change what's verified
in CI.
