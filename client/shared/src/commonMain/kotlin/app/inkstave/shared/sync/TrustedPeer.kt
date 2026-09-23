package app.inkstave.shared.sync

import kotlinx.serialization.Serializable

/**
 * A peer device this device has paired with (`docs/sync-protocol.md`'s
 * "Pairing & trust"), persisted locally by `PeerTrustStore`.
 *
 * [certificateFingerprintSha256] -- not [deviceId] -- is what actually grants
 * trust: it's the SHA-256 fingerprint (`CertificateFingerprint.sha256`) of
 * the TLS certificate the peer presented and the user visually confirmed
 * during pairing (`PairingSession`). Every future connection re-derives the
 * presented certificate's fingerprint and compares it against this stored
 * value (`PinnedFingerprintTrustManager`) -- [deviceId]/[displayName] are
 * carried along purely for the human-readable trust-store UI (`docs/
 * sync-protocol.md`: "Paired-device records ... can be revoked by the user
 * at any time"), never consulted for the trust decision itself, since both
 * are self-reported by the peer and a malicious device could claim any
 * value for either.
 */
@Serializable
data class TrustedPeer(
    val deviceId: String,
    val displayName: String,
    val certificateFingerprintSha256: String,
)
