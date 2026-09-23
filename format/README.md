# `format/`

Shared schemas and reader/writer tooling for the `.smpk` score format
(`../docs/format-spec.md`), used by both `client/` (Kotlin) and
`processing-service/` (Python) so the two never drift out of sync.

## Layout

```
format/
├── schema/     JSON Schema (2020-12) for manifest.json, part.json,
│               page metadata, and the annotation layer -- format v1,
│               matching ../docs/format-spec.md field-for-field
├── fixtures/   One valid example JSON instance per schema, used by both
│               languages' tests
└── python/     inkstave-format: typed Pydantic v2 models + JSON Schema
                validation helpers, pip-installable (see below)
```

**Kotlin implementation note:** the Kotlin side of the format
(`Manifest`/`ManifestSource` + `ManifestJson` reader/writer) lives directly
in `client/shared/src/commonMain/kotlin/app/inkstave/shared/format/`
rather than in a separate `format/kotlin` Gradle module wired in via a
composite build. That's a deliberate M0 scope call: a composite build adds
real Gradle complexity, and `client/shared` is already the only Kotlin
consumer of this format. If a second Kotlin consumer ever needs it
independently of `client/shared`, that's the trigger to extract it into its
own module -- not before.

Both language implementations are typed models mirroring the same JSON
Schemas in `schema/` (see `docs/coding-standards.md`): Pydantic models with
`extra="allow"` on the Python side, a hand-rolled unknown-field-preserving
`ManifestJson` object on the Kotlin side (kotlinx.serialization doesn't have
Pydantic's `extra="allow"` equivalent). Keeping them in sync when the format
changes is `score-format`'s job (`../.claude/agents/score-format.md`) --
there is no code generation from `schema/` to either language yet; both are
hand-written against the same spec and schema, checked against the same
fixtures.

## `format/python` (`inkstave-format`)

```
cd format/python
python -m venv .venv
.venv/bin/pip install -e ".[dev]"
.venv/bin/ruff check . && .venv/bin/mypy src/inkstave_format tests && .venv/bin/python -m pytest -q
```

`processing-service` depends on this package as a local editable install
(see `../processing-service/README.md`) -- it's not published anywhere.

**Setting up one IDE for both Python packages (and `client/`) at once?**
See `../docs/ide-setup.md` -- a single shared venv at the repo root
works for `format/python` and `processing-service` together, instead of
the two separate ones below.

## Cross-language round-trip check

Each language's round-trip test (`ManifestJsonTest.kt`,
`test_manifest_round_trip_preserves_unknown_fields` in
`format/python/tests/test_format.py`) only proves that language reads back
what it itself wrote. `format/scripts/cross_lang_roundtrip.sh` proves the
stronger claim `docs/testing-strategy.md` actually asks for: a
`manifest.json` written by **Kotlin** is read identically -- including its
one deliberately-unrecognized field -- by **Python**, and vice versa,
against a single canonical fixture
(`format/fixtures/manifest.v1.cross-lang.json`) both `CrossLangRoundtripCli.kt`
(`client/shared/src/jvmCommonTest/.../format/crosslang/`) and
`inkstave_format.cross_lang_cli` verify against, so neither language's
notion of "correct" can drift from the other's unnoticed.

```
# requires client/'s Gradle build resolvable and format/python's venv set up (above)
./scripts/cross_lang_roundtrip.sh
```

Wired into `.github/workflows/ci.yml` as its own job. Deliberately
verified (by temporarily breaking Kotlin's unknown-field preservation,
confirming the script fails, then reverting) to actually catch a real
cross-language mismatch, not just pass the happy path -- see the M0 entry
in `ROADMAP.md`.

Owned by the `score-format` agent — see `../.claude/agents/score-format.md`.
