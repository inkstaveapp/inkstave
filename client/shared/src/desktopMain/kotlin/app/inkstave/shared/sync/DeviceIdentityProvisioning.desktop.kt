package app.inkstave.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** [DeviceIdentity] plus the PKCS12 keystore password only the desktop actual needs. */
@Serializable
private data class DesktopIdentityMetadata(
    val deviceId: String,
    val displayName: String,
    val keystorePassword: String,
)

private const val KEYSTORE_ALIAS = "inkstave"
private const val KEY_VALIDITY_DAYS = 3650

private fun metadataFile(settingsDirectory: File) = File(settingsDirectory, "device-identity.json")

private fun keystoreFile(settingsDirectory: File) = File(settingsDirectory, "device-identity.p12")

private fun loadMetadata(settingsDirectory: File): DesktopIdentityMetadata? {
    val file = metadataFile(settingsDirectory)
    if (!file.exists()) return null
    return try {
        Json.decodeFromString(DesktopIdentityMetadata.serializer(), file.readText())
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }
}

/**
 * Generates a fresh identity: a random device ID, the hostname as display name (with a fallback),
 * and a self-signed EC certificate created by `keytool`. The random keystore password exists only
 * because PKCS12 requires one; the real protection is the user-only settings directory.
 */
private fun provisionNewIdentity(settingsDirectory: File): DesktopIdentityMetadata {
    settingsDirectory.mkdirs()
    val deviceId = UUID.randomUUID().toString()
    val displayName =
        try {
            InetAddress.getLocalHost().hostName
        } catch (e: java.net.UnknownHostException) {
            "Inkstave Desktop"
        }
    val password = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val metadata = DesktopIdentityMetadata(deviceId, displayName, password)

    val keystore = keystoreFile(settingsDirectory)
    keystore.delete()
    val process =
        ProcessBuilder(
            "keytool",
            "-genkeypair",
            "-alias",
            KEYSTORE_ALIAS,
            "-keyalg",
            "EC",
            "-groupname",
            "secp256r1",
            "-sigalg",
            "SHA256withECDSA",
            "-validity",
            KEY_VALIDITY_DAYS.toString(),
            "-keystore",
            keystore.absolutePath,
            "-storetype",
            "PKCS12",
            "-storepass",
            metadata.keystorePassword,
            "-keypass",
            metadata.keystorePassword,
            "-dname",
            "CN=$deviceId",
            "-noprompt",
        ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "keytool -genkeypair failed (exit $exitCode): $output" }

    metadataFile(settingsDirectory).writeText(Json.encodeToString(DesktopIdentityMetadata.serializer(), metadata))
    return metadata
}

private fun metadataOrProvision(settingsDirectory: File): DesktopIdentityMetadata =
    loadMetadata(settingsDirectory) ?: provisionNewIdentity(settingsDirectory)

actual fun getOrCreateDeviceIdentity(settingsDirectory: File): DeviceIdentity {
    val metadata = metadataOrProvision(settingsDirectory)
    return DeviceIdentity(metadata.deviceId, metadata.displayName)
}

actual fun deviceIdentitySslContext(
    settingsDirectory: File,
    trustManager: X509TrustManager,
): SSLContext {
    val metadata = metadataOrProvision(settingsDirectory)
    val keyStore =
        KeyStore.getInstance("PKCS12").apply {
            keystoreFile(settingsDirectory).inputStream().use { load(it, metadata.keystorePassword.toCharArray()) }
        }
    val keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, metadata.keystorePassword.toCharArray())
        }
    return SSLContext.getInstance("TLSv1.3").apply {
        init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}
