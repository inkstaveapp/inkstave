package app.inkstave.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/** On-disk shape of the trusted peers: local, single-reader state, so a plain list suffices. */
@Serializable
private data class PeerTrustStoreDto(
    val peers: List<TrustedPeer> = emptyList(),
)

/**
 * The [TrustedPeer]s this device has paired with, stored at [file] and revocable by the user.
 *
 * A peer not in this store must always be treated as untrusted. This class only records decisions a
 * human made during pairing; it never decides trust itself.
 */
class PeerTrustStore(
    private val file: File,
) {
    private val json = kotlinx.serialization.json.Json { prettyPrint = true }

    /** Every trusted peer; empty if [file] is missing or unreadable. A corrupt file falls back to "no
     * trusted peers" (re-pair, recoverable) rather than crashing sync, and never to trusting anyone. */
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

    /** Trusts [peer], replacing any entry with the same device ID so re-pairing updates the fingerprint. */
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
