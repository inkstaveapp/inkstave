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
     * (`PairingScreen`): the first [groupCount] colon-separated groups (8 hex chars each,
     * `groupCount` = 4 by default = 32 of the fingerprint's 256 bits). This is a real, deliberate
     * security trade-off, not a cosmetic shortcut -- `docs/sync-protocol.md` calls for a "short
     * pairing code," and a full 64-hex-character SHA-256 string is impractical for two people to
     * read aloud and compare by eye. 32 bits of a value an active attacker would need to find a
     * second preimage for (not merely observe) is judged adequate friction for this threat model
     * (a human physically present on the same LAN, actively trying to substitute their own
     * device's certificate during the brief pairing window) without becoming unusable UX; anyone
     * who wants the full, uncompromised fingerprint can still read it via [sha256] directly.
     */
    fun shortCode(
        certificate: Certificate,
        groupCount: Int = 4,
    ): String = sha256(certificate).split(":").take(groupCount).joinToString(" ")
}
