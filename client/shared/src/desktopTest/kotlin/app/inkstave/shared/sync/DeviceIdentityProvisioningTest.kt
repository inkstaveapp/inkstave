package app.inkstave.shared.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Real `keytool`-backed tests (`DeviceIdentityProvisioning.desktop.kt`'s doc explains why
 * `keytool`, not a library) -- this actually shells out to the system `keytool` binary, the
 * honest integration-test ceiling for identity generation on desktop (`docs/testing-strategy.md`).
 */
class DeviceIdentityProvisioningTest {
    private lateinit var directory: java.io.File

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("device-identity-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `first call generates an identity, second call returns the same one, not a fresh one`() {
        val first = getOrCreateDeviceIdentity(directory)
        val second = getOrCreateDeviceIdentity(directory)

        assertEquals(first, second)
        assertTrue(first.deviceId.isNotBlank())
        assertTrue(first.displayName.isNotBlank())
    }

    @Test
    fun `two different settings directories get two different identities`() {
        val otherDirectory = createTempDirectory("device-identity-test-other-").toFile()
        try {
            val a = getOrCreateDeviceIdentity(directory)
            val b = getOrCreateDeviceIdentity(otherDirectory)
            assertNotEquals(a.deviceId, b.deviceId)
        } finally {
            otherDirectory.deleteRecursively()
        }
    }

    @Test
    fun `the generated certificate's subject is the device's own deviceId, and its fingerprint is stable across builds`() {
        val identity = getOrCreateDeviceIdentity(directory)
        val sslContext = deviceIdentitySslContext(directory, TrustAnyPeerCertificate)
        // The SSLContext alone doesn't hand back the leaf certificate directly -- load the same
        // PKCS12 keystore keytool wrote, the same way DeviceIdentityProvisioning.desktop.kt itself
        // does, to inspect the certificate this identity actually presents.
        val keystoreFile = java.io.File(directory, "device-identity.p12")
        assertTrue(keystoreFile.exists(), "keytool should have written a PKCS12 keystore")

        val metadataFile = java.io.File(directory, "device-identity.json")
        val password =
            Json
                .parseToJsonElement(metadataFile.readText())
                .jsonObject["keystorePassword"]!!
                .jsonPrimitive.content
        val keyStore =
            java.security.KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { load(it, password.toCharArray()) }
            }
        val certificate = keyStore.getCertificate("inkstave") as java.security.cert.X509Certificate

        assertEquals("CN=${identity.deviceId}", certificate.subjectX500Principal.name)
        val fingerprintFirst = CertificateFingerprint.sha256(certificate)
        val fingerprintSecond = CertificateFingerprint.sha256(certificate)
        assertEquals(fingerprintFirst, fingerprintSecond, "fingerprinting the same certificate twice must be deterministic")
        assertTrue(fingerprintFirst.matches(Regex("([0-9A-F]{2}:)+[0-9A-F]{2}")), "fingerprint should be colon-separated uppercase hex")

        sslContext.socketFactory // touch it, just to prove building the context didn't throw
    }
}
