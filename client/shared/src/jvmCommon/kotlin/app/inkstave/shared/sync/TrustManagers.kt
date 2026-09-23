package app.inkstave.shared.sync

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The real trust decision for every connection *after* pairing (`docs/sync-protocol.md`:
 * "Every subsequent connection between two devices must be from an already-paired device"):
 * accepts a peer's certificate chain iff the leaf certificate's SHA-256 fingerprint
 * ([CertificateFingerprint.sha256]) is in [trustStore] -- nothing else about the certificate
 * (its subject name, its validity dates in the usual CA-trust sense, any issuer chain) is
 * consulted, since these are self-signed identity certificates with no CA behind them; the
 * fingerprint *is* the trust anchor, pinned by a human during pairing (`PairingSession`), not
 * derived from any PKI hierarchy.
 */
class PinnedFingerprintTrustManager(
    private val trustStore: PeerTrustStore,
) : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = checkTrusted(chain)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = checkTrusted(chain)

    private fun checkTrusted(chain: Array<out X509Certificate>) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fingerprint = CertificateFingerprint.sha256(leaf)
        if (!trustStore.isTrusted(fingerprint)) {
            throw CertificateException(
                "certificate fingerprint $fingerprint is not a paired peer -- pair this device first",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * **Pairing only -- never used for an authenticated data connection.** Accepts any certificate
 * whatsoever, so the initial TLS handshake with a not-yet-trusted device can complete at all
 * (there is, by definition, nothing to pin yet the first time two devices meet). `PairingSession`
 * is the only caller of this: it uses the connection this trust manager permits solely to let a
 * human *look at* the presented certificate's fingerprint and decide whether to trust it --
 * before that confirmation, the connection carries no data operation
 * (`docs/sync-protocol.md`: "discovery of an unpaired device only offers 'pair with this
 * device,' never any data operation"). Using this trust manager for anything other than the
 * pairing exchange itself would defeat the entire pinning model.
 */
object TrustAnyPeerCertificate : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
