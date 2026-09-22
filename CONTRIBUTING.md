# Contributing

This project is in its earliest stages (see [ROADMAP.md](ROADMAP.md)) and
does not yet have a public remote or issue tracker — that gets set up once
there's something runnable (end of M0/start of M1). Until then, this file
describes how work happens locally.

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

## Workflow (pre-remote)

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

## Workflow (post-remote, future)

Once a remote exists, this section will be updated with the actual PR
process, branch naming, and CI requirements. Expect standard practice:
feature branches, PRs reviewed before merge, CI green before merge.

## Code style

- **Kotlin:** follow the [official Kotlin coding
  conventions](https://kotlinlang.org/docs/coding-conventions.html);
  formatting/linting tooling gets pinned in M0.
- **Python:** follow [PEP 8](https://peps.python.org/pep-0008/) with type
  hints; formatting/linting tooling gets pinned in M0.

## Licensing

By contributing, you agree your contribution is licensed under this
project's [Apache License 2.0](LICENSE).
