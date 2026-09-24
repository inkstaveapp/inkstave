package app.inkstave.shared.sync

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure-logic tests for the multi-interface discovery pieces: merging one device's sightings from
 * several responders, and trying its addresses in order. The real multi-interface behaviour was
 * verified on hardware (a desktop on Ethernet + Wi-Fi, a tablet on Wi-Fi); these pin down the
 * rules that make it safe.
 */
class MultiAddressDiscoveryTest {
    private class Recording : SyncDeviceListener {
        val found = mutableListOf<DiscoveredDevice>()
        val lost = mutableListOf<String>()

        override fun onDeviceFound(device: DiscoveredDevice) {
            found += device
        }

        override fun onDeviceLost(deviceId: String) {
            lost += deviceId
        }
    }

    private fun device(
        host: String,
        port: Int = 4000,
        id: String = "desk",
    ) = DiscoveredDevice(id, "Desktop", listOf(host), port, setOf(DeviceRole.PROCESSING))

    @Test
    fun `sightings of one listener on different interfaces merge into one device with every address`() {
        val out = Recording()
        val merging = MergingDeviceListener(out)
        merging.onDeviceFound(device("192.168.2.162"))
        merging.onDeviceFound(device("192.168.2.163"))
        merging.onDeviceFound(device("192.168.2.162"))

        assertEquals(listOf("192.168.2.162", "192.168.2.163"), out.found.last().hosts)
    }

    @Test
    fun `the same device on another port stays a separate listener`() {
        val out = Recording()
        val merging = MergingDeviceListener(out)
        merging.onDeviceFound(device("192.168.2.162", port = 4000))
        merging.onDeviceFound(device("192.168.2.163", port = 5000))

        assertEquals(listOf("192.168.2.163"), out.found.last().hosts)
    }

    @Test
    fun `losing a device forgets its addresses, so a later sighting starts fresh`() {
        val out = Recording()
        val merging = MergingDeviceListener(out)
        merging.onDeviceFound(device("192.168.2.162"))
        merging.onDeviceLost("desk")
        merging.onDeviceFound(device("192.168.2.163"))

        assertEquals(listOf("desk"), out.lost)
        assertEquals(listOf("192.168.2.163"), out.found.last().hosts)
    }

    @Test
    fun `an unreachable address falls through to the next one`() {
        val tried = mutableListOf<String>()
        val result =
            firstReachable(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")) { host ->
                tried += host
                if (host == "10.0.0.1") throw ConnectException("refused")
                if (host == "10.0.0.2") throw NoRouteToHostException("no route")
                "connected to $host"
            }

        assertEquals("connected to 10.0.0.3", result)
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), tried)
    }

    @Test
    fun `a device that was reached but refused is not retried on its other addresses`() {
        val tried = mutableListOf<String>()
        assertFailsWith<SSLHandshakeException> {
            firstReachable(listOf("10.0.0.1", "10.0.0.2")) { host ->
                tried += host
                throw SSLHandshakeException("certificate rejected")
            }
        }
        assertEquals(listOf("10.0.0.1"), tried)
    }

    @Test
    fun `when every address is unreachable the last reason is reported`() {
        val failure =
            assertFailsWith<IOException> {
                firstReachable(listOf("10.0.0.1", "10.0.0.2")) { host -> throw ConnectException("refused by $host") }
            }
        assertEquals("refused by 10.0.0.2", failure.message)
    }
}
