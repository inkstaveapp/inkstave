# Contributing

This project is in its earliest stages (see [ROADMAP.md](ROADMAP.md)). The
repository is hosted at <https://github.com/inkstaveapp/inkstave>; the pull
request process below is still being worked out, so please open an issue to
discuss a change before investing in a large one.

## AI-assisted, human-supervised

Development here uses AI coding agents (Claude Code subagents defined in
`.claude/agents/`) for a large share of the implementation, under active
human review. That means:

- Commits may come from an agent session or from a manual edit — both are
  normal. Don't assume a file's current state was AI-authored just because
  an agent touched this repo recently.
- Agents should prefer the specialist subagent that matches the area of the
  change (see the table in [CLAUDE.md](CLAUDE.md)) rather than making broad,
  cross-cutting changes in one general-purpose pass.
- Architectural decisions are recorded as ADRs in `docs/decisions/`. Propose
  changes to a standing decision explicitly rather than drifting away from
  it silently.

## Workflow

1. Work happens on local branches or directly reviewed by the repo owner.
2. Keep commits scoped and the message focused on *why* the change was
   made.
3. Any new third-party dependency must be recorded in
   [NOTICE.md](NOTICE.md) with its license, in the same change that
   introduces it.
4. Update the relevant doc (`docs/architecture.md`,
   `docs/format-spec.md`, `docs/sync-protocol.md`, `docs/image-pipeline.md`,
   or `ROADMAP.md`) in the same change if it alters behavior those docs
   describe.

## Pull requests (still being defined)

The exact PR process, branch naming, and CI requirements will be written
down here as they settle. Expect standard practice: feature branches, PRs
reviewed before merge, CI green before merge.

## Code style

Full standards live in [docs/coding-standards.md](docs/coding-standards.md)
— summary:

- **Kotlin:** follow the [official Kotlin coding
  conventions](https://kotlinlang.org/docs/coding-conventions.html);
  formatting/linting tooling gets pinned in M0. No `Any`/unchecked casts/`!!`
  as a way to dodge modeling a type properly.
- **Python:** follow [PEP 8](https://peps.python.org/pep-0008/) with
  complete type hints, checked with `mypy --strict` (or equivalent) as a
  required CI gate, not an optional lint — Python code here is held to the
  same typesafety bar as the Kotlin side, not a looser one.
- **Documentation is not optional:** every public function/class/module gets
  a real doc comment (KDoc/docstring), and any `docs/*.md` that describes
  behavior you changed gets updated in the same change.
- **Tests are not optional either:** see
  [docs/testing-strategy.md](docs/testing-strategy.md) — every functional
  scenario worth testing gets a test (unit/integration/e2e/spec, whichever
  fits) written alongside the change that introduces it.

## Licensing

By contributing, you agree your contribution is licensed under this
project's [Apache License 2.0](LICENSE).
