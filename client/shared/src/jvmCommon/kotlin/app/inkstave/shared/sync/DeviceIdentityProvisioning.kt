package app.inkstave.shared.sync

import java.io.File
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * This device's persistent identity (`DeviceIdentity`) for sync, generating one on first use and
 * reusing it thereafter -- `docs/sync-protocol.md`: "a stable device ID (generated on first run
 * ... )". [settingsDirectory] is where platform-specific identity material lives (see the
 * `.desktop.kt`/`.android.kt` actuals for exactly what) -- the same per-platform-directory wiring
 * `PedalSettingsStore`'s callers already use, just a directory rather than one file, since the
 * two platforms need different files/keystore mechanisms underneath.
 *
 * Deliberately **not** implemented with a third-party crypto library
 * (`docs/sync-protocol.md`: "do not hand-roll crypto here") or JDK-internal APIs
 * (`sun.security.x509`, unavailable on Android's runtime and not a stable contract even on
 * desktop): desktop shells out to the JDK's own `keytool` (a standard, always-present part of
 * every JDK distribution) to generate a self-signed certificate into a PKCS12 keystore; Android
 * uses `AndroidKeyStore`'s own built-in self-signed-certificate generation
 * (`KeyGenParameterSpec.Builder.setCertificateSubject`), the platform-idiomatic, hardware
 * -backed-where-available mechanism for exactly this. See each actual's own doc.
 */
expect fun getOrCreateDeviceIdentity(settingsDirectory: File): DeviceIdentity

/**
 * An [SSLContext] presenting this device's own identity certificate (from
 * [getOrCreateDeviceIdentity]) and verifying a peer's certificate via [trustManager] -- callers
 * pass [PinnedFingerprintTrustManager] for a real authenticated connection, or
 * [TrustAnyPeerCertificate] for the pairing exchange itself (see that object's doc for why that's
 * the *only* legitimate use of it).
 */
expect fun deviceIdentitySslContext(
    settingsDirectory: File,
    trustManager: X509TrustManager,
): SSLContext
