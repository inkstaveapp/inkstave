package app.inkstave.shared.sync

/**
 * A device advertised on the local network (`docs/sync-protocol.md`'s "Discovery"), before any
 * trust decision has been made about it -- discovery alone never grants access
 * ("Discovery only lists devices; it does not by itself grant any access"). [hosts]/[port] are
 * where to connect to actually pair with or sync to this device; [roles] mirrors the discovered
 * service's advertised roles (`"capture"`, `"processing"`, or both -- a laptop can be both, per
 * that doc).
 *
 * [hosts] is a list because one device is often reachable at several addresses at once (a
 * desktop with both Ethernet and Wi-Fi advertises on each). It is never empty, in preference
 * order; callers try each in turn until one is reachable (`firstReachable`).
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

/**
 * Discovery events as they arrive (`SyncDiscovery.browse`) -- a callback interface rather than a
 * `kotlinx.coroutines.Flow`, matching this codebase's existing preference for the simplest
 * mechanism that fits (`docs/coding-standards.md`'s "don't build speculative abstractions") over
 * a heavier reactive-stream API two callback methods don't need.
 */
interface SyncDeviceListener {
    /** [device] was just advertised, or its advertised info changed. */
    fun onDeviceFound(device: DiscoveredDevice)

    /** The device previously advertised as [deviceId] is no longer on the network. */
    fun onDeviceLost(deviceId: String)
}

/**
 * LAN device discovery (`docs/sync-protocol.md`'s "Discovery") -- kept as an interface, the same
 * `SyncTransport`-shaped spirit ADR-0003 asks for at the transport layer, so a future discovery
 * mechanism (relevant only if a future relay/cloud transport ever needs its own device-listing
 * story) could satisfy this without upper-layer pairing/UI code changing. `JmDnsSyncDiscovery`
 * (`jvmCommon`) is the only implementation for v1.
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
