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

/** [DeviceIdentity] as persisted on Android. No keystore password: AndroidKeyStore gates access itself. */
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
 * Generates a fresh identity in `AndroidKeyStore`. Setting a certificate subject makes key generation
 * produce the self-signed certificate too, and the private key never leaves the (hardware-backed where
 * available) keystore.
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
            // EC P-256, matching desktop. Not RSA: AndroidKeyStore only allows the signature
            // paddings a key was created for, and TLS 1.3's RSA-PSS wasn't one of them.
            // DIGEST_NONE is required: Conscrypt hashes the handshake transcript itself and asks
            // the keystore for a raw ECDSA signature ("Incompatible digest" otherwise).
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
 * Re-provisions when the `AndroidKeyStore` entry is missing even though [metadataFile] exists (keystore
 * reset, or app data restored without hardware-backed keys). The new identity means re-pairing with
 * every peer, which is recoverable, unlike crashing sync.
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
            // No password: AndroidKeyStore entries are protected by the platform, not per entry.
            init(androidKeyStore(), null)
        }
    return SSLContext.getInstance("TLSv1.3").apply {
        init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}
