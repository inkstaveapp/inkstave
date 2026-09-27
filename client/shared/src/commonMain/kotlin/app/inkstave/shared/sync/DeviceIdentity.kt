package app.inkstave.shared.sync

import kotlinx.serialization.Serializable

/**
 * This device's identity as advertised during discovery and shown during pairing.
 *
 * [deviceId] is a random UUID created on first run, never derived from a hardware identifier (that
 * could leak PII). [displayName] is cosmetic. Neither is ever used for trust decisions: both are
 * self-reported and freely spoofable; trust comes only from the pinned certificate fingerprint
 * (`TrustedPeer`).
 */
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val displayName: String,
)
