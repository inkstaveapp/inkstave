# Coding standards

Project-wide standards that apply regardless of which part of Inkstave
you're working on. Every specialist agent (`.claude/agents/`) is expected to
follow these; this doc is the canonical version — agent files link here
rather than restating it.

## Typesafety, in every language

This is a hard requirement, not a preference — including in Python, where
it's easy to let it slide.

- **Kotlin** (`client/`): avoid `Any`, unchecked casts, and `!!` non-null
  assertions except at a verified boundary (e.g. immediately after a null
  check) with a comment explaining why it's safe. Model states and variants
  with sealed classes/interfaces, not string/int flags. Data crossing the
  client↔service or client↔format boundary is represented as a typed model,
  never a raw `Map<String, Any>` or untyped JSON tree.
- **Python** (`processing-service/`): full type hints on every function
  signature and class — parameters, return type, and public attributes. Run
  `mypy --strict` (or an equivalent strict static checker) as a CI gate, not
  an optional lint. No `Any` used to paper over a type you haven't modeled
  yet — model it. Structured data (the processing-service API request/
  response, page metadata, OCR results) is represented with typed
  dataclasses or Pydantic models generated from or validated against the
  schemas in `format/`, never passed around as raw `dict`.
- **Shared contracts** (`format/`): because the same schema is consumed from
  both Kotlin and Python, a schema change must force a compile-time-visible
  change in both typed representations — if only one side notices, the
  typed models have drifted from being a real mirror of the schema and that
  drift is itself a bug to fix, not something to `Any`-cast around.

The point of this isn't dogma for its own sake: this project has two
languages agreeing on one wire/file format across a process and device
boundary, exactly the situation where "it happened to work at runtime"
silently rots. Typed code makes a schema mismatch a build failure on both
sides instead of a bug report from a user's malformed `.smpk`.

## Documentation, in code and in the docs

Both layers are required; neither substitutes for the other.

- **In code:** every public/exported function, class, and module gets a doc
  comment (KDoc `/** ... */` in Kotlin, a docstring in Python) that explains
  *what it's for and any non-obvious behavior or invariant* — not a
  restatement of the signature (`/** Returns the title. */` on
  `fun title(): String` is noise, not documentation). Document *why* a
  non-obvious choice was made inline, the same standard applied throughout
  this repo's own docs. Undocumented public API is treated as incomplete
  work, not a follow-up ticket.
- **In static docs:** `docs/architecture.md`, `docs/format-spec.md`,
  `docs/sync-protocol.md`, `docs/image-pipeline.md`, `docs/performance.md`,
  and the ADRs in `docs/decisions/` describe the *why* and the cross-cutting
  shape of the system — the layer code comments can't carry on their own.
  When a change alters behavior one of these docs describes, the doc is
  updated in the same change (this was already the rule in
  `CONTRIBUTING.md`; it's restated here because it's part of the same
  discipline as code-level documentation, not a separate concern).
- If you're genuinely unsure whether something needs a doc comment, the
  test is: would a reader who didn't write this line be surprised by its
  behavior, or need to read the implementation to trust it? If yes, it
  needs one.

## Performance

See `docs/performance.md` for the concrete requirements (annotation
rendering under high annotation density, library scaling) — those are
specific enough to warrant their own doc rather than living here.
