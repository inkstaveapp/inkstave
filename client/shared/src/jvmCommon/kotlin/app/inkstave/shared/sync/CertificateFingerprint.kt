package app.inkstave.shared.sync

import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * Certificate fingerprints used for pairing and pinning. Uses the standard "SHA-256 of the DER encoding"
 * convention, so it can be checked independently with `openssl x509 -fingerprint -sha256`.
 */
object CertificateFingerprint {
    /** SHA-256 of [certificate]'s DER encoding as colon-separated uppercase hex (`AB:CD:...`), the usual
     * TLS fingerprint format. */
    fun sha256(certificate: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    /**
     * The first [groupCount] bytes of [sha256] (32 bits by default), short enough to compare by eye.
     * A deliberate trade-off: an attacker on the LAN would need a second preimage of those 32 bits
     * within the pairing window.
     *
     * Not the pairing confirmation code; see [combinedShortCode].
     */
    fun shortCode(
        certificate: Certificate,
        groupCount: Int = 4,
    ): String = sha256(certificate).split(":").take(groupCount).joinToString(" ")

    /**
     * The pairing confirmation code, identical on both devices: a hash of both certificates' digests in
     * a fixed byte order, so it doesn't depend on which side is "local". A per-side fingerprint of the
     * peer's certificate would differ between the two screens and could never be compared.
     */
    fun combinedShortCode(
        localCertificate: Certificate,
        peerCertificate: Certificate,
        groupCount: Int = 4,
    ): String {
        val localDigest = MessageDigest.getInstance("SHA-256").digest(localCertificate.encoded)
        val peerDigest = MessageDigest.getInstance("SHA-256").digest(peerCertificate.encoded)
        val orderedPair =
            if (localDigest.isLexicographicallyLessThan(peerDigest)) {
                localDigest + peerDigest
            } else {
                peerDigest + localDigest
            }
        val combinedDigest = MessageDigest.getInstance("SHA-256").digest(orderedPair)
        return combinedDigest.take(groupCount).joinToString(" ") { byte -> "%02X".format(byte) }
    }

    /** Unsigned lexicographic comparison; both sides only need to agree on some fixed order. */
    private fun ByteArray.isLexicographicallyLessThan(other: ByteArray): Boolean {
        for (index in indices) {
            val thisByte = this[index].toUByte()
            val otherByte = other[index].toUByte()
            if (thisByte != otherByte) return thisByte < otherByte
        }
        return false
    }
}
