"""Python half of the cross-language `.smpk` round-trip check
(`format/scripts/cross_lang_roundtrip.sh`); the Kotlin half is
`CrossLangRoundtripCli.kt`. Not part of the public API.

`CROSS_LANG_FIXTURE_PATH` is the known-good manifest both languages compare
against; `CROSS_LANG_TARGET_PATH` is the file written (`write`) or checked
(`read`).
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
    """`write` re-encodes the fixture to the target; `read` checks the target
    equals the fixture, including unknown fields (models keep them), so a lost
    `omrPreview` field fails too. Exits non-zero on mismatch or misuse.
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
