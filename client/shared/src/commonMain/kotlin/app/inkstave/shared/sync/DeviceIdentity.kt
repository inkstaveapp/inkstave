package app.inkstave.shared.sync

import kotlinx.serialization.Serializable

/**
 * This device's own identity, as advertised to other devices during discovery
 * (`docs/sync-protocol.md`) and shown to a human during pairing.
 *
 * [deviceId] is a random UUID generated once on first run and persisted
 * thereafter (see `DeviceIdentityStore`), deliberately *not* derived from any
 * hardware identifier (a MAC address, an Android ID, a serial number) --
 * `docs/sync-protocol.md`'s discovery section explicitly requires this ("not
 * tied to hardware identifiers that could leak PII"). [displayName] is
 * whatever the user's device calls itself (a hostname, a product name) --
 * cosmetic only, never used for trust decisions; trust is entirely a
 * function of the certificate fingerprint pinned during pairing (see
 * `TrustedPeer`), not of [deviceId] or [displayName], both of which a
 * malicious device could freely lie about.
 */
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val displayName: String,
)
