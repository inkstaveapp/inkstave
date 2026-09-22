# Third-party dependencies and licenses

This file is the running ledger of every third-party dependency used by this
project (libraries, models, fonts, sample data) and its license. It must be
kept in sync with what's actually declared in `client/`'s Gradle build files
and `processing-service/`'s Python dependency manifest.

Maintained by / consult: [.claude/agents/licensing-compliance.md](.claude/agents/licensing-compliance.md).

Rules:

- Every dependency added to the build must have a row here in the same
  change.
- Prefer permissive licenses (MIT, Apache-2.0, BSD) and weak-copyleft
  (LGPL, MPL-2.0) consumed as unmodified dynamic dependencies. Anything
  GPL/AGPL-licensed needs an explicit note on how it's isolated (e.g. run as
  a separate process, not statically linked) and the human's sign-off before
  being added.
- If a dependency's license requires attribution text in end-user-facing
  "about"/licenses screens, note that in the Notes column so the client app
  can surface it.

## Client (Kotlin Multiplatform / Android / Linux desktop)

| Dependency | Version | License | Notes |
|---|---|---|---|
| _none yet_ | | | |

## Processing service (Python)

| Dependency | Version | License | Notes |
|---|---|---|---|
| _none yet_ | | | |

## Format tooling

| Dependency | Version | License | Notes |
|---|---|---|---|
| _none yet_ | | | |

## Fonts / assets / models

| Dependency | Version | License | Notes |
|---|---|---|---|
| _none yet_ | | | |
