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
 * Generates a fresh identity: a random device ID, a best-effort display name (this machine's own
 * hostname, falling back to a generic name if that's unavailable -- e.g. no reverse-DNS/hosts
 * entry in a sandboxed environment -- since a missing display name shouldn't block identity
 * creation entirely), a random keystore password (this keystore protects a *sync* identity key,
 * not anything more sensitive, and lives in a directory only this user account can already read,
 * the same trust model `PedalSettingsStore`'s unencrypted settings file already relies on -- the
 * password exists because `keytool`/`KeyStore` require one, not as a meaningful secret boundary
 * on its own), and a self-signed certificate via `keytool` (this module's doc explains why
 * `keytool` specifically, not a library).
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
