# Sync protocol — draft design

**Status: implemented for v1** (`ROADMAP.md` M4, `client/shared/src/.../sync/`).
Covers v1's LAN-first scope (ADR-0003). Cloud/relay transport is explicitly
future work; this design must not preclude it. Concrete choices this doc
originally left as "TBD in implementation" are now made and documented
below, next to what they replace.

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
  `_inkstave._tcp.local.` (this doc originally said `_smreader`, a leftover
  from before the project's Inkstave rename — corrected here to match).
  Implemented with [JmDNS](https://github.com/jmdns/jmdns) (Apache-2.0, pure
  Java, `NOTICE.md`) — the one dependency this slice genuinely needed; there's
  no JDK-builtin mDNS/DNS-SD support, and JmDNS works unmodified on both
  Android and desktop (`JmDnsSyncDiscovery`).
- TXT record advertises: a stable device ID (`deviceId`, generated on first
  run, not tied to hardware identifiers that could leak PII — see
  "Pairing & trust" below for where it actually comes from) and role hints
  (`roles`, comma-joined `capture`/`processing`/both). Display name is the
  mDNS service's own advertised name, not a separate TXT field.
- Discovery only lists devices; it does not by itself grant any access —
  `JmDnsSyncDiscovery.browse` surfaces a `DiscoveredDevice`, nothing more.

## Pairing & trust

Unauthenticated LAN discovery is not sufficient trust to accept a device's
data or let it write to a library — anyone on the same Wi-Fi shouldn't be
able to push files. Pairing establishes trust once, explicitly:

1. **Each device generates a persistent identity on first run**: a random
   `deviceId` and a self-signed TLS certificate/keypair whose subject CN
   *is* that `deviceId` (`DeviceIdentityProvisioning.kt`'s `expect`/`actual`
   pair). Desktop shells out to the JDK's own `keytool` into a PKCS12
   keystore (a standard, always-present JDK tool — not a new dependency, not
   hand-rolled crypto); Android uses `AndroidKeyStore`'s own built-in
   self-signed-certificate generation
   (`KeyGenParameterSpec.setCertificateSubject`), hardware-backed where the
   device supports it, with the private key never leaving the platform
   keystore.
2. **The two devices connect over TLS trusting any certificate for this one
   exchange** (`TrustAnyPeerCertificate` — the standard trust-on-first-use
   pattern, not a weakening of the model: no data operation happens over
   this connection, only the identity exchange below) and each sends its own
   `DeviceIdentity` (`deviceId` + display name) as one framed message
   (`PairingSession`/`MessageFraming`).
3. **Both devices' certificates are combined into one short,
   human-comparable code, shown identically on both screens**
   (`CertificateFingerprint.combinedShortCode` — the two certificates' SHA-256
   digests sorted into a fixed byte order, concatenated, hashed again, then
   the first 32 bits formatted as space-separated hex groups). The user
   confirms the two screens show the *same* code. This is "a short pairing
   code" as originally specified, derived from the real certificates rather
   than a separately-generated value. It is deliberately a *combination* of
   both certificates, not the fingerprint of just the peer's: each device
   pins the *other's* certificate, so a per-side "fingerprint of what I just
   received" is a different certificate on each screen and can never match —
   found via a real phone-to-desktop pairing where the two screens showed
   different codes. A man-in-the-middle necessarily terminates two separate
   TLS sessions with different certificate pairs, so the two victims' screens
   would differ, which is what makes the comparison meaningful. What actually
   gets pinned in step 4 is still the peer's own certificate fingerprint
   (`CertificateFingerprint.sha256`), not this combined code. No QR code in
   v1 (real, valid future UX work; not needed for the security property).
4. **Only once the user explicitly confirms** does the fingerprint get
   persisted as a `TrustedPeer` (`PeerTrustStore`, a local JSON file per
   device — the same local-settings-file pattern `PedalSettingsStore`
   already established). This fingerprint, not `deviceId`/display name (both
   self-reported and freely spoofable), is what every future connection is
   actually authenticated against.
5. Paired-device records are stored locally per device and can be revoked by
   the user at any time (`PeerTrustStore.remove`, surfaced in
   `PairingScreen`'s "Trusted devices" list).

Every subsequent connection between two devices must be from an already
-paired device — enforced by TLS itself: `PinnedFingerprintTrustManager`
rejects the handshake outright (before any application data is exchanged) if
the peer's certificate fingerprint isn't in `PeerTrustStore`. Discovery of an
unpaired device only offers "pair with this device," never any data
operation.

## Transport

- Once paired, devices connect directly over the LAN via a **plain TLS
  socket** (`javax.net.ssl.SSLSocket`, `LanSyncTransport`/`SyncServer`),
  authenticated by the pinned certificate fingerprint above — not
  WebSocket, and not a separate "session key": the same identity
  certificate pairing pinned *is* what authenticates every later
  connection, so there's no additional shared secret to generate, exchange,
  or rotate. Messages are length-prefixed (`MessageFraming`: a 4-byte
  big-endian length, then that many payload bytes) with a 1-byte type
  discriminant inside each frame (`CaptureSessionWire`) — no message
  framework, no compression; TLS already provides integrity/confidentiality
  and a phone photo is single-digit megabytes at most.
- **Capture session:** the mobile app opens a session tied to a target
  score (new or existing) by sending a `SessionStart` message (session ID +
  score title); each captured photo is sent as its own `Photo` message
  (session ID, sequence index, raw bytes), so the desktop can process
  photos individually as they arrive rather than only once the whole batch
  is present; a `SessionEnd` message closes it out (`CaptureSessionMessage`,
  `docs/image-pipeline.md`'s `/process-page` endpoint is the natural next
  hop for each received `Photo`, not yet wired to it — see `ROADMAP.md`'s
  M4 entry).
  **Implemented (M4, `CaptureSessionSender`/`CaptureSessionReceiver`,
  `client/shared/src/.../sync/`):** the sender finds the peer's current
  address via a fresh discovery lookup (pairing only pins a fingerprint,
  never a live address) and sends the messages above over
  `LanSyncTransport`; the receiver reads them into a `.smpk` via the same
  local-import path M1 already uses. One honest gap from this section's
  phrasing above: the *client* currently sends a capture session's photos
  as one batch once the user finishes capturing (`CaptureActivity`'s
  "Done"), not truly live, one `Photo` message per shutter press during
  capture — the wire protocol and receiver already support that (nothing
  about `Photo`'s shape assumes batching), but `CaptureActivity` isn't
  wired to stream mid-capture yet. Real, low-effort follow-up work, not
  attempted in this pass.
- **Processed result delivery:** once the desktop has assembled the
  finished `.smpk` (or an update to an existing one), it sends it back to
  the originating device(s) as a normal sync payload, not a special case of
  the capture session. Not yet designed in detail — real work for whenever
  this is picked back up, same as M6 below.
- **General library sync** (M6, beyond a single capture session — new
  scores, annotation edits, deletions across paired devices) reuses the same
  authenticated channel and transfers `.smpk` files/diffs; exact
  change-tracking mechanism is designed when M6 starts, informed by whatever
  the format's per-file layout (`docs/format-spec.md`) makes cheap to diff.

## Transport abstraction

Client code depends on a `SyncTransport` interface (`commonMain`,
`client/shared/src/commonMain/.../sync/SyncTransport.kt`), not directly on
"LAN socket": `connect(peer, host, port): SyncConnection`, where
`SyncConnection` is `send(CaptureSessionMessage)`/`receive(): CaptureSessionMessage`.
`LanSyncTransport` (`jvmCommon`) is the only implementation for v1; a future
relay/cloud implementation satisfies the same interface so upper-layer sync
logic (sessions, conflict handling, format-aware diffing) doesn't need to
change to support it.

## Explicit non-goals for v1

- No internet-relay fallback when devices aren't on the same network.
- No multi-user / multi-account sharing model — pairing is between a single
  user's own devices.
- No background/always-on sync daemon — sync is user-initiated per session
  for v1 (M4); an always-on story is a candidate for M6 or later.

## Ownership

Owned by the `sync-network` agent (`.claude/agents/sync-network.md`).
