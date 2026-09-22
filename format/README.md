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

Owned by the `score-format` agent — see `../.claude/agents/score-format.md`.
