package app.inkstave.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/**
 * The on-disk shape of [PeerTrustStore]'s saved peers -- a plain serializable list, the same
 * local-single-device-preference ceremony level `PedalSettingsStore`'s `PedalSettingsDto` uses
 * and for the identical reason: this is per-device trust state with exactly one reader (this app,
 * this device), not a portable cross-device file, so it doesn't need `.smpk`'s unknown-field
 * -preserving pattern.
 */
@Serializable
private data class PeerTrustStoreDto(
    val peers: List<TrustedPeer> = emptyList(),
)

/**
 * Reads/writes the set of [TrustedPeer]s this device has paired with (`docs/sync-protocol.md`'s
 * "Paired-device records are stored locally per device and can be revoked by the user at any
 * time") at [file] -- desktop and Android entry points each pass a different, platform
 * -appropriate path, the same wiring pattern `PedalSettingsStore` already established.
 *
 * Every connection attempt from a peer not in this store must be treated as untrusted
 * (`docs/sync-protocol.md`: "discovery of an unpaired device only offers 'pair with this device,'
 * never any data operation") -- this class only stores the trust decisions a human already made
 * during pairing; it does not itself decide who to trust.
 */
class PeerTrustStore(
    private val file: File,
) {
    private val json = kotlinx.serialization.json.Json { prettyPrint = true }

    /**
     * Every currently-trusted peer, or an empty list if [file] doesn't exist yet (no peer has
     * ever been paired) or can't be parsed. A corrupted trust-store file must never crash sync --
     * falling back to "no trusted peers" (which just means every peer needs re-pairing, a
     * recoverable, user-visible state) is always safer than propagating the error, the same
     * reasoning `PedalSettingsStore.load` uses.
     */
    fun list(): List<TrustedPeer> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(PeerTrustStoreDto.serializer(), file.readText()).peers
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Whether a certificate with [fingerprintSha256] belongs to an already-trusted peer. */
    fun isTrusted(fingerprintSha256: String): Boolean = list().any { it.certificateFingerprintSha256 == fingerprintSha256 }

    /**
     * Adds [peer] as trusted (replacing any existing entry for the same [TrustedPeer.deviceId],
     * so re-pairing after a peer regenerates its identity updates the stored fingerprint rather
     * than accumulating a stale second entry).
     */
    fun add(peer: TrustedPeer) {
        val updated = list().filterNot { it.deviceId == peer.deviceId } + peer
        save(updated)
    }

    /** Revokes trust in the peer identified by [deviceId], if one is currently trusted. */
    fun remove(deviceId: String) {
        save(list().filterNot { it.deviceId == deviceId })
    }

    private fun save(peers: List<TrustedPeer>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(PeerTrustStoreDto.serializer(), PeerTrustStoreDto(peers)))
    }
}
