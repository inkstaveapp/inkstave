package app.inkstave.shared.sync

import kotlinx.serialization.Serializable

/**
 * A device this device has paired with, persisted by `PeerTrustStore`.
 *
 * Only [certificateFingerprintSha256] grants trust: the SHA-256 fingerprint of the certificate the
 * user confirmed during pairing, checked on every later connection (`PinnedFingerprintTrustManager`).
 * [deviceId] and [displayName] are for display and revocation only and must never be used for the
 * trust decision, since the peer reports them itself.
 */
@Serializable
data class TrustedPeer(
    val deviceId: String,
    val displayName: String,
    val certificateFingerprintSha256: String,
)
