# Sync protocol — draft design

**Status: proposal, not yet implemented.** Covers v1's LAN-first scope
(ADR-0003). Cloud/relay transport is explicitly future work; this design
must not preclude it.

## Goals for v1

- A phone and a desktop on the same local network find each other without
  manual IP entry.
- A user explicitly pairs two devices once; unpaired devices never receive
  data.
- Captured photos stream from phone to desktop live, as a **capture
  session**, with low enough latency that "take a photo, see it appear
  processed on the desktop moments later" feels immediate.
- A finished `.smpk` syncs back to the originating device(s) after
  processing.
- The transport is behind an interface general enough that a future
  cloud/relay transport is a new implementation, not a redesign.

## Discovery

- **mDNS/DNS-SD** (Zeroconf) service advertisement, service type
  `_smreader._tcp.local.`.
- TXT record advertises: device display name, a stable device ID (generated
  on first run, not tied to hardware identifiers that could leak PII), and
  role hints (`capture`, `processing`, or both — a laptop can be both).
- Discovery only lists devices; it does not by itself grant any access.

## Pairing & trust

Unauthenticated LAN discovery is not sufficient trust to accept a device's
data or let it write to a library — anyone on the same Wi-Fi shouldn't be
able to push files. Pairing establishes trust once, explicitly:

1. One device displays a short pairing code (and/or QR code); the other
   enters/scans it. This is a standard "human-in-the-loop" pairing pattern,
   not a novel protocol.
2. On successful pairing, the two devices exchange and persist a shared key
   used to authenticate future sessions (exact primitive TBD in
   implementation — a modern AEAD handshake such as Noise is the leading
   candidate; do not hand-roll crypto here).
3. Paired-device records are stored locally per device and can be revoked
   by the user at any time.

Every subsequent connection between two devices must be from an already
-paired device; discovery of an unpaired device only offers "pair with this
device," never any data operation.

## Transport

- Once paired, devices connect directly over the LAN (WebSocket over TLS
  using the paired session key, or an equivalent authenticated channel —
  exact choice is implementation detail for the `sync-network` agent, not
  fixed here).
- **Capture session:** the mobile app opens a session tied to a target
  score (new or existing); each captured photo is sent as it's taken, with
  sequence metadata (session ID, capture index, timestamp) so the desktop
  can show live progress and the processing service can process photos as
  they arrive rather than waiting for the whole batch.
- **Processed result delivery:** once the desktop has assembled the
  finished `.smpk` (or an update to an existing one), it sends it back to
  the originating device(s) as a normal sync payload, not a special case of
  the capture session.
- **General library sync** (M6, beyond a single capture session — new
  scores, annotation edits, deletions across paired devices) reuses the same
  authenticated channel and transfers `.smpk` files/diffs; exact
  change-tracking mechanism is designed when M6 starts, informed by whatever
  the format's per-file layout (`docs/format-spec.md`) makes cheap to diff.

## Transport abstraction

Client code depends on a `SyncTransport` interface (exact shape defined when
M0/M4 implementation starts), not directly on "LAN socket." The LAN
implementation is the only one built for v1; a future relay/cloud
implementation satisfies the same interface so upper-layer sync logic
(sessions, conflict handling, format-aware diffing) doesn't need to change
to support it.

## Explicit non-goals for v1

- No internet-relay fallback when devices aren't on the same network.
- No multi-user / multi-account sharing model — pairing is between a single
  user's own devices.
- No background/always-on sync daemon — sync is user-initiated per session
  for v1 (M4); an always-on story is a candidate for M6 or later.

## Ownership

Owned by the `sync-network` agent (`.claude/agents/sync-network.md`).
