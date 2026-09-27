package app.inkstave.shared.sync

/**
 * A device advertised on the local network, before any trust decision: discovery never grants
 * access by itself. [roles] are the advertised roles (`capture`, `processing`, or both).
 *
 * [hosts] lists every address the device was seen at (one per network interface), never empty, in
 * preference order; callers try each until one is reachable (`firstReachable`).
 */
data class DiscoveredDevice(
    val deviceId: String,
    val displayName: String,
    val hosts: List<String>,
    val port: Int,
    val roles: Set<String>,
) {
    init {
        require(hosts.isNotEmpty()) { "a discovered device needs at least one address" }
    }
}

/** Advertised device roles (`docs/sync-protocol.md`'s discovery TXT record). */
object DeviceRole {
    const val CAPTURE = "capture"
    const val PROCESSING = "processing"
}

/** Discovery events from `SyncDiscovery.browse`, delivered as they arrive. */
interface SyncDeviceListener {
    /** [device] was just advertised, or its advertised info changed. */
    fun onDeviceFound(device: DiscoveredDevice)

    /** The device previously advertised as [deviceId] is no longer on the network. */
    fun onDeviceLost(deviceId: String)
}

/**
 * LAN device discovery (`docs/sync-protocol.md`). An interface, like [SyncTransport], so another
 * discovery mechanism could replace mDNS without changing pairing or UI code; `JmDnsSyncDiscovery`
 * is the implementation.
 */
interface SyncDiscovery : AutoCloseable {
    /** Advertises this device as [identity], reachable on [port], offering [roles]. */
    fun advertise(
        identity: DeviceIdentity,
        port: Int,
        roles: Set<String>,
    )

    /** Starts reporting other advertised devices to [listener] as they appear/disappear. */
    fun browse(listener: SyncDeviceListener)
}
