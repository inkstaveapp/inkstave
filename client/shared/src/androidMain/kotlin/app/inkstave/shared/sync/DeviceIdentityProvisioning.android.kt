package app.inkstave.shared.sync

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** [DeviceIdentity] as persisted on Android -- no keystore password here, see this file's doc. */
@Serializable
private data class AndroidIdentityMetadata(
    val deviceId: String,
    val displayName: String,
)

private const val KEYSTORE_ALIAS = "inkstave-sync-identity"
private const val KEY_VALIDITY_DAYS = 3650L
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

private fun metadataFile(settingsDirectory: File) = File(settingsDirectory, "device-identity.json")

private fun loadMetadata(settingsDirectory: File): AndroidIdentityMetadata? {
    val file = metadataFile(settingsDirectory)
    if (!file.exists()) return null
    return try {
        Json.decodeFromString(AndroidIdentityMetadata.serializer(), file.readText())
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }
}

private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

/**
 * Generates a fresh identity using `AndroidKeyStore`'s own built-in self-signed-certificate
 * generation (`KeyGenParameterSpec.Builder.setCertificateSubject`, standard since API 23, well
 * below this app's `minSdk` 26) -- the Android-idiomatic mechanism for exactly this, hardware
 * -backed where the device supports it, and the reason this platform needs no `keytool`-equivalent
 * subprocess the way desktop does (see `DeviceIdentityProvisioning.desktop.kt`): generating a
 * keypair *in* `AndroidKeyStore` with a certificate subject configured produces the self-signed
 * certificate as a side effect of key generation itself, with the private key never leaving the
 * keystore (not even to this process' own memory) in the first place.
 */
private fun provisionNewIdentity(settingsDirectory: File): AndroidIdentityMetadata {
    settingsDirectory.mkdirs()
    val deviceId = UUID.randomUUID().toString()
    val displayName = Build.MODEL ?: "Inkstave Android"

    val notBefore = Date()
    val notAfter = Date(notBefore.time + KEY_VALIDITY_DAYS * MILLIS_PER_DAY)
    val spec =
        KeyGenParameterSpec
            .Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            // EC (P-256/secp256r1), not RSA -- matching DeviceIdentityProvisioning.desktop.kt's
            // own keytool invocation. This was RSA originally; a real cross-device pairing attempt
            // on real hardware failed the TLS handshake with a BoringSSL "RSA routines ...
            // internal error", consistent with this KeyGenParameterSpec never declaring
            // .setSignaturePaddings(...) -- AndroidKeyStore cryptographically enforces that a key
            // is only usable for the exact signature scheme(s) its spec authorized, and without
            // one declared, whatever RSA padding TLS 1.3 actually negotiated for CertificateVerify
            // (RSA-PSS by default) wasn't necessarily one this key was ever authorized to use.
            // EC signing in TLS has no equivalent multi-padding-scheme ambiguity to get wrong.
            //
            // DIGEST_NONE is required alongside DIGEST_SHA256, also found on real hardware:
            // Conscrypt/BoringSSL computes the TLS handshake transcript hash itself and asks
            // AndroidKeyStore to perform a *raw* ECDSA sign over that already-computed digest --
            // it never asks the key to hash-and-sign a message the way DIGEST_SHA256 alone
            // authorizes. Without DIGEST_NONE, every handshake needing this key to sign (both as
            // the connecting side's CertificateVerify and the accepting side's own) failed with
            // `InvalidKeyException: ... KeyStoreException: Incompatible digest`, confirmed via a
            // real phone-to-desktop pairing attempt's logcat stack trace through
            // ConscryptEngineSocket$SSLInputStream -> PairingSession.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setCertificateSubject(X500Principal("CN=$deviceId"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
        initialize(spec)
        generateKeyPair()
    }

    val metadata = AndroidIdentityMetadata(deviceId, displayName)
    metadataFile(settingsDirectory).writeText(Json.encodeToString(AndroidIdentityMetadata.serializer(), metadata))
    return metadata
}

/**
 * Re-provisions even if [metadataFile] already exists, when the `AndroidKeyStore` entry it refers
 * to is missing -- a real, if rare, recoverable state (e.g. the keystore was reset by the OS, or
 * app data was restored from a backup that couldn't include hardware-backed key material) rather
 * than one worth crashing sync over. A regenerated identity means re-pairing with every peer,
 * the same user-visible, recoverable consequence `PeerTrustStore`'s own doc describes for a lost
 * trust store, just on the other side of the same relationship.
 */
private fun metadataOrProvision(settingsDirectory: File): AndroidIdentityMetadata {
    val existing = loadMetadata(settingsDirectory)
    if (existing != null && androidKeyStore().containsAlias(KEYSTORE_ALIAS)) return existing
    return provisionNewIdentity(settingsDirectory)
}

actual fun getOrCreateDeviceIdentity(settingsDirectory: File): DeviceIdentity {
    val metadata = metadataOrProvision(settingsDirectory)
    return DeviceIdentity(metadata.deviceId, metadata.displayName)
}

actual fun deviceIdentitySslContext(
    settingsDirectory: File,
    trustManager: X509TrustManager,
): SSLContext {
    metadataOrProvision(settingsDirectory)
    val keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            // AndroidKeyStore entries aren't individually password-protected the way a PKCS12
            // keystore's are -- the platform keystore itself gates access, so `null` here is
            // correct, not a placeholder standing in for a password this code forgot to supply.
            init(androidKeyStore(), null)
        }
    return SSLContext.getInstance("TLSv1.3").apply {
        init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}
