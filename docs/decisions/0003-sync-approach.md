# ADR-0003: LAN-first, direct device-to-device sync for v1

**Status:** accepted
**Date:** 2026-09-23

## Context

The core differentiating feature is live phone → desktop photo streaming
during capture, plus getting the processed score back onto the phone (and
other devices). This needs to work reliably and with low latency during a
capture session, and shouldn't require standing up infrastructure before
the project has proven the core viewer/annotator/pipeline experience.

## Options considered

- **LAN-first, direct device link** — devices discover each other on the
  local network (mDNS/Zeroconf) and connect directly (see
  `docs/sync-protocol.md`). No server to run or maintain. Only works when
  devices share a network, which is the common case for the flagship
  "photograph paper music, watch it appear cleaned up on the desktop"
  workflow.
- **Cloud-relay first (self-hosted sync server)** — works even when devices
  aren't on the same network (e.g. syncing a library while traveling), but
  requires building and operating a server component before v1's core
  workflow is even validated — real scope added to the riskiest, least
  proven part of the project.
- **Hybrid abstraction from day one** — build both transports behind one
  interface immediately. More correct long-term but meaningfully more
  upfront design/testing surface for a v1 that hasn't yet validated the
  core UX.

## Decision

LAN-first for v1: mDNS discovery, explicit pairing, direct authenticated
connection. The sync layer is built behind a `SyncTransport` interface
(`docs/sync-protocol.md`) specifically so a relay/cloud transport can be
added later as a second implementation, not a redesign.

## Consequences

- v1 sync only works when devices are on the same local network — off-LAN
  sync (e.g. syncing a personal library while traveling) is explicitly out
  of scope until a relay transport is built (candidate for post-M6 work).
- No server infrastructure to build, deploy, or pay for during the phase of
  the project where the core UX is still being validated.
- Pairing/trust model (`docs/sync-protocol.md`) must be solid on its own —
  it isn't backstopped by server-side auth, since there is no server.
- Adding relay/cloud sync later must not require changing the sync-session
  or capture-session logic above the transport interface — that's the test
  of whether the abstraction boundary was drawn correctly.
