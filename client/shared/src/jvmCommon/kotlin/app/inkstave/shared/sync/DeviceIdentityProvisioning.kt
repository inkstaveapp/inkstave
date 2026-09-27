package app.inkstave.shared.sync

import java.io.File
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * This device's persistent sync identity, created on first use and reused afterwards. Identity
 * material lives under [settingsDirectory].
 *
 * No hand-rolled crypto and no JDK-internal APIs (`sun.security.x509` doesn't exist on Android):
 * desktop uses the JDK's `keytool` to create a self-signed certificate in a PKCS12 keystore; Android
 * uses `AndroidKeyStore`'s built-in self-signed certificate generation.
 */
expect fun getOrCreateDeviceIdentity(settingsDirectory: File): DeviceIdentity

/**
 * An [SSLContext] that presents this device's identity certificate and checks peers with
 * [trustManager]: [PinnedFingerprintTrustManager] for every real connection, [TrustAnyPeerCertificate]
 * only for the pairing exchange itself.
 */
expect fun deviceIdentitySslContext(
    settingsDirectory: File,
    trustManager: X509TrustManager,
): SSLContext
