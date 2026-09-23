#!/usr/bin/env bash
# Cross-language `.smpk` manifest.json round-trip check
# (docs/testing-strategy.md's "cross-language format round-trip" integration
# test -- closes the gap ROADMAP.md's M0 section used to note as open).
#
# Runs both directions against the single canonical fixture
# format/fixtures/manifest.v1.cross-lang.json (the one source of truth for
# "correct" both CLIs verify against, so the two languages' expectations
# can't drift apart unnoticed):
#   1. Kotlin writes -> Python reads and verifies
#   2. Python writes -> Kotlin reads and verifies
# Exits non-zero, with the failing side's output, on any mismatch in either
# direction -- including the fixture's deliberately-unrecognized
# `omrPreview` field, which is how this proves unknown-field preservation
# survives the language boundary, not just a single language's own tests.
#
# Requires: client/'s Gradle build already resolvable (JDK 17+, Android SDK
# per client/README.md) and format/python's dev venv set up:
#   cd format/python && python -m venv .venv && .venv/bin/pip install -e ".[dev]"
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$REPO_ROOT/format/fixtures/manifest.v1.cross-lang.json"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

PYTHON_CLI="$REPO_ROOT/format/python/.venv/bin/inkstave-format-cross-lang-cli"
if [ ! -x "$PYTHON_CLI" ]; then
  echo "error: $PYTHON_CLI not found -- set up format/python's venv first (see this script's header comment)" >&2
  exit 2
fi

echo "== direction 1: Kotlin writes, Python reads =="
export CROSS_LANG_FIXTURE_PATH="$FIXTURE"
export CROSS_LANG_TARGET_PATH="$WORKDIR/from-kotlin.manifest.json"
( cd "$REPO_ROOT/client" && ./gradlew -q :shared:crossLangManifestWrite )
"$PYTHON_CLI" read

echo
echo "== direction 2: Python writes, Kotlin reads =="
export CROSS_LANG_TARGET_PATH="$WORKDIR/from-python.manifest.json"
"$PYTHON_CLI" write
( cd "$REPO_ROOT/client" && ./gradlew -q :shared:crossLangManifestRead )

echo
echo "cross-language round-trip: OK (both directions)"
