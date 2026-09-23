"""Command-line entry point for the cross-language `.smpk` format round-trip
check (`format/scripts/cross_lang_roundtrip.sh`).

Not part of the public `inkstave_format` API surface `processing-service`
imports -- exists purely as the Python half of a two-language integration
test (`docs/testing-strategy.md`'s "cross-language format round-trip"
requirement, previously an open gap noted in `ROADMAP.md`'s M0 section),
mirroring the Kotlin CLI in
`client/shared/src/jvmCommonTest/.../CrossLangRoundtripCli.kt`. Installed as
the `inkstave-format-cross-lang-cli` console script (see `pyproject.toml`).

Controlled by the same two environment variables as the Kotlin side:
`CROSS_LANG_FIXTURE_PATH` (the canonical known-good manifest.json both
languages treat as the single source of truth for "correct", rather than
each hardcoding its own copy of the expected values) and
`CROSS_LANG_TARGET_PATH` (the file this run writes to, in `write` mode, or
reads from and compares against the fixture, in `read` mode).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from inkstave_format.io import dump_json, parse_json
from inkstave_format.models import Manifest


def _require_env(name: str) -> str:
    """Returns the environment variable `name`, or exits with status 2 if unset/empty."""
    value = os.environ.get(name)
    if not value:
        print(f"missing required env var {name}", file=sys.stderr)
        sys.exit(2)
    return value


def main() -> None:
    """Runs the CLI. `write` re-encodes the fixture to the target path;
    `read` decodes both the fixture and the target path with `Manifest` and
    asserts they're equal -- Pydantic model equality, which includes extra
    (unrecognised) fields since every `_InkstaveModel` sets
    `extra="allow"` (see `models.py`), so a mismatch in the fixture's
    deliberately-unrecognized `omrPreview` field fails this check too, not
    just a mismatch in a known field. Exits non-zero on any mismatch or
    usage error.
    """
    mode = sys.argv[1] if len(sys.argv) > 1 else None
    fixture_path = Path(_require_env("CROSS_LANG_FIXTURE_PATH"))
    target_path = Path(_require_env("CROSS_LANG_TARGET_PATH"))

    if mode == "write":
        fixture = parse_json(Manifest, fixture_path.read_text())
        target_path.parent.mkdir(parents=True, exist_ok=True)
        target_path.write_text(dump_json(fixture))
        print(f"[python] wrote {target_path} from fixture {fixture_path}")
    elif mode == "read":
        expected = parse_json(Manifest, fixture_path.read_text())
        actual = parse_json(Manifest, target_path.read_text())
        if expected == actual:
            print(f"[python] OK: {target_path} matches {fixture_path} (unknown fields included)")
        else:
            print(
                f"[python] MISMATCH between {target_path} and fixture {fixture_path}",
                file=sys.stderr,
            )
            print(f"  expected: {expected!r}", file=sys.stderr)
            print(f"  actual:   {actual!r}", file=sys.stderr)
            sys.exit(1)
    else:
        print(
            "usage: <write|read> (needs CROSS_LANG_FIXTURE_PATH, CROSS_LANG_TARGET_PATH env vars)",
            file=sys.stderr,
        )
        sys.exit(2)


if __name__ == "__main__":
    main()
