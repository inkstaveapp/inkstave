package app.inkstave.shared.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real two-JmDNS-instance discovery over this machine's actual multicast (loopback/local
 * interface) -- **not guaranteed to work in every sandboxed/containerized environment**, since
 * some restrict multicast entirely; `docs/testing-strategy.md` calls for verifying this honestly
 * rather than assuming, so this test's own pass/fail result (see `ROADMAP.md`'s M4 entry for what
 * actually happened when this was run) is the verification, not a claim made in this comment.
 */
class JmDnsSyncDiscoveryTest {
    private val discoveryInstances = mutableListOf<JmDnsSyncDiscovery>()

    @AfterTest
    fun tearDown() {
        discoveryInstances.forEach { it.close() }
    }

    private fun create(): JmDnsSyncDiscovery = JmDnsSyncDiscovery.create().also { discoveryInstances.add(it) }

    @Test
    fun `one instance's advertised device is found by another instance browsing on the same host`() {
        val advertiser = create()
        val identity = DeviceIdentity(deviceId = "test-device-${System.nanoTime()}", displayName = "Test Desktop")
        advertiser.advertise(identity, port = ADVERTISED_PORT, roles = setOf(DeviceRole.PROCESSING))

        val browser = create()
        val found = CountDownLatch(1)
        var discovered: DiscoveredDevice? = null
        browser.browse(
            object : SyncDeviceListener {
                override fun onDeviceFound(device: DiscoveredDevice) {
                    if (device.deviceId == identity.deviceId) {
                        discovered = device
                        found.countDown()
                    }
                }

                override fun onDeviceLost(deviceId: String) = Unit
            },
        )

        val sawIt = found.await(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(sawIt, "expected to discover the advertised device within $DISCOVERY_TIMEOUT_SECONDS s")
        assertEquals(ADVERTISED_PORT, discovered?.port)
        assertEquals(setOf(DeviceRole.PROCESSING), discovered?.roles)
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_SECONDS = 15L
        const val ADVERTISED_PORT = 47823
    }
}
