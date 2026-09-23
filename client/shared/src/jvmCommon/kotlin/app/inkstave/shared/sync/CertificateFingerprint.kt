package app.inkstave.shared.sync

import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * The SHA-256 fingerprint of a TLS certificate, as compared by a human during
 * pairing (`PairingSession`) and pinned thereafter (`TrustedPeer`,
 * `PinnedFingerprintTrustManager`) -- `docs/sync-protocol.md`'s trust model
 * hinges entirely on this being the same standard "hash the DER encoding"
 * fingerprint convention TLS tooling (`openssl x509 -fingerprint`, browser
 * certificate-details panels) already uses, not a custom scheme: a technical
 * user who wants to double-check it independently can.
 */
object CertificateFingerprint {
    /**
     * SHA-256 of [certificate]'s DER encoding ([Certificate.getEncoded]), formatted as
     * colon-separated uppercase hex pairs (`AB:CD:EF:...`) -- the conventional TLS fingerprint
     * display format, chosen specifically so it's recognisable to anyone who's seen a browser's
     * "certificate details" panel or `openssl x509 -fingerprint` output before, not invented for
     * this project.
     */
    fun sha256(certificate: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    /**
     * A short, easier-to-read-aloud-and-compare form of [sha256]'s output for the pairing UI
     * (`PairingScreen`): the first [groupCount] colon-separated groups (2 hex chars each,
     * `groupCount` = 4 by default = 32 of the fingerprint's 256 bits). This is a real, deliberate
     * security trade-off, not a cosmetic shortcut -- `docs/sync-protocol.md` calls for a "short
     * pairing code," and a full 64-hex-character SHA-256 string is impractical for two people to
     * read aloud and compare by eye. 32 bits of a value an active attacker would need to find a
     * second preimage for (not merely observe) is judged adequate friction for this threat model
     * (a human physically present on the same LAN, actively trying to substitute their own
     * device's certificate during the brief pairing window) without becoming unusable UX; anyone
     * who wants the full, uncompromised fingerprint can still read it via [sha256] directly.
     *
     * **Not used for the pairing confirmation UI's "matches on both devices" code** -- see
     * [combinedShortCode]'s doc for why a single certificate's fingerprint can never actually
     * match between the two devices being paired. Still used standalone by callers that want one
     * specific certificate's own short form (e.g. showing a `TrustedPeer`'s pinned fingerprint).
     */
    fun shortCode(
        certificate: Certificate,
        groupCount: Int = 4,
    ): String = sha256(certificate).split(":").take(groupCount).joinToString(" ")

    /**
     * The pairing confirmation code shown to the human, computed so **both devices arrive at the
     * exact same value** -- unlike [shortCode] of a single certificate, which necessarily differs
     * between the two sides (`PairingSession.outcomeFor` calls this with each side's own
     * certificate and the peer's: the initiator's "peer" is the acceptor and vice versa, so
     * [shortCode] of just the peer's certificate is fingerprinting a *different* certificate on
     * each screen, and can never match no matter how correctly everything else works -- a real bug
     * found via an actual phone-to-desktop pairing attempt, where the two screens legitimately
     * showed different codes despite nothing being wrong). Combining both certificates' digests
     * fixes this: sorting them into a fixed byte order first (rather than "local then peer" or
     * "peer then local") means the combined result doesn't depend on which side of the handshake a
     * device was on, so the initiator and the acceptor -- each holding the same two certificates,
     * just with opposite ideas of which one is "mine" -- independently compute identical bytes.
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

    /** Unsigned, index-by-index byte comparison -- [ByteArray] has no natural ordering in Kotlin, and a signed
     * comparison would put e.g. 0x80 before 0x7F, which isn't the fixed, side-independent order [combinedShortCode]
     * needs (only that both sides agree on *some* fixed order, not what that order is). */
    private fun ByteArray.isLexicographicallyLessThan(other: ByteArray): Boolean {
        for (index in indices) {
            val thisByte = this[index].toUByte()
            val otherByte = other[index].toUByte()
            if (thisByte != otherByte) return thisByte < otherByte
        }
        return false
    }
}
