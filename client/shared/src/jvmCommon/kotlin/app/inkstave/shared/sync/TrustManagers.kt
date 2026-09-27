package app.inkstave.shared.sync

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The trust decision for every connection after pairing: accepts a chain only if the leaf
 * certificate's SHA-256 fingerprint is in [trustStore]. Subject, validity and issuer are not
 * consulted; these are self-signed certificates and the pinned fingerprint is the trust anchor.
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
 * **Pairing only; never use for a data connection.** Accepts any certificate, because on first
 * contact there is nothing to pin yet. `PairingSession` uses it only so a human can compare the
 * confirmation code; the connection carries no data. Using it anywhere else defeats pinning.
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
