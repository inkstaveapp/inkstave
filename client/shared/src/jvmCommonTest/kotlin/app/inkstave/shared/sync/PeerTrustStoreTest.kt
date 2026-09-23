package app.inkstave.shared.sync

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerTrustStoreTest {
    private lateinit var directory: java.io.File

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("peer-trust-store-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store() = PeerTrustStore(java.io.File(directory, "trusted-peers.json"))

    @Test
    fun `no file yet means no trusted peers, not an error`() {
        assertEquals(emptyList(), store().list())
        assertFalse(store().isTrusted("AA:BB"))
    }

    @Test
    fun `add persists and is visible to a fresh store instance over the same file`() {
        val peer = TrustedPeer(deviceId = "device-1", displayName = "Phone", certificateFingerprintSha256 = "AA:BB:CC")
        store().add(peer)

        val reloaded = store()
        assertEquals(listOf(peer), reloaded.list())
        assertTrue(reloaded.isTrusted("AA:BB:CC"))
        assertFalse(reloaded.isTrusted("00:00:00"))
    }

    @Test
    fun `re-adding the same deviceId replaces its fingerprint rather than duplicating`() {
        val store = store()
        store.add(TrustedPeer("device-1", "Phone", "OLD:FINGERPRINT"))
        store.add(TrustedPeer("device-1", "Phone (re-paired)", "NEW:FINGERPRINT"))

        val peers = store.list()
        assertEquals(1, peers.size)
        assertEquals("NEW:FINGERPRINT", peers.single().certificateFingerprintSha256)
        assertFalse(store.isTrusted("OLD:FINGERPRINT"))
    }

    @Test
    fun `remove revokes trust without disturbing other peers`() {
        val store = store()
        store.add(TrustedPeer("device-1", "Phone", "AA"))
        store.add(TrustedPeer("device-2", "Desktop", "BB"))

        store.remove("device-1")

        val peers = store.list()
        assertEquals(listOf(TrustedPeer("device-2", "Desktop", "BB")), peers)
        assertFalse(store.isTrusted("AA"))
        assertTrue(store.isTrusted("BB"))
    }

    @Test
    fun `a corrupted trust store file falls back to no trusted peers rather than crashing`() {
        val file = java.io.File(directory, "trusted-peers.json")
        file.parentFile.mkdirs()
        file.writeText("not valid json { [ }")

        assertEquals(emptyList(), PeerTrustStore(file).list())
    }
}
