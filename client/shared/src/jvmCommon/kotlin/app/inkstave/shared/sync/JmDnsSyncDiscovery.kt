package app.inkstave.shared.sync

import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Service type per `docs/sync-protocol.md`'s "Discovery" -- `_inkstave`, matching this project's
 * name (the doc previously said `_smreader`, a leftover from before the Inkstave rename;
 * corrected here and in that doc together).
 */
const val SYNC_SERVICE_TYPE = "_inkstave._tcp.local."

private const val PROPERTY_DEVICE_ID = "deviceId"
private const val PROPERTY_ROLES = "roles"

/**
 * The only [SyncDiscovery] implementation for v1 (`docs/decisions/0003-sync-approach.md`), backed
 * by [JmDNS] -- pure Java, no native code, no platform-specific mDNS binding needed, so this one
 * class works unmodified on both `androidMain` and `desktopMain` (unlike, say, `PageBitmap`'s
 * genuinely platform-divergent decoding, this doesn't need an `expect`/`actual` split at all).
 */
class JmDnsSyncDiscovery private constructor(
    private val jmdns: JmDNS,
) : SyncDiscovery {
    private var registeredService: ServiceInfo? = null

    /** Advertises [identity] under [SYNC_SERVICE_TYPE], reachable on [port], offering [roles] in its TXT record. */
    override fun advertise(
        identity: DeviceIdentity,
        port: Int,
        roles: Set<String>,
    ) {
        val props =
            mapOf(
                PROPERTY_DEVICE_ID to identity.deviceId,
                PROPERTY_ROLES to roles.joinToString(","),
            )
        val info = ServiceInfo.create(SYNC_SERVICE_TYPE, identity.displayName, port, 0, 0, props)
        jmdns.registerService(info)
        registeredService = info
    }

    override fun browse(listener: SyncDeviceListener) {
        jmdns.addServiceListener(SYNC_SERVICE_TYPE, JmDnsListenerAdapter(listener))
    }

    /** Unregisters this device's own advertisement (if any) and shuts down the underlying [JmDNS] instance. */
    override fun close() {
        registeredService?.let { jmdns.unregisterService(it) }
        jmdns.close()
    }

    companion object {
        /** A fresh [JmDnsSyncDiscovery] bound to the platform's default network interface. */
        fun create(): JmDnsSyncDiscovery = JmDnsSyncDiscovery(JmDNS.create())
    }
}

/**
 * Bridges [JmDNS]'s own [ServiceListener] callback shape to [SyncDeviceListener], and does the
 * one piece of real translation work: [ServiceListener.serviceAdded] fires with a bare name and
 * no resolved address/TXT-record data yet (per JmDNS's own contract), so this only reports a
 * device via [SyncDeviceListener.onDeviceFound] once [ServiceListener.serviceResolved] actually
 * has that data -- reporting on `serviceAdded` instead would hand callers a [DiscoveredDevice]
 * with no real host/port/roles to act on.
 */
private class JmDnsListenerAdapter(
    private val listener: SyncDeviceListener,
) : ServiceListener {
    override fun serviceAdded(event: ServiceEvent) = Unit

    override fun serviceResolved(event: ServiceEvent) {
        event.info.toDiscoveredDevice()?.let(listener::onDeviceFound)
    }

    override fun serviceRemoved(event: ServiceEvent) {
        val deviceId = event.info.getPropertyString(PROPERTY_DEVICE_ID) ?: event.name
        listener.onDeviceLost(deviceId)
    }
}

/** `null` if [ServiceInfo] is missing the [PROPERTY_DEVICE_ID] property or has no resolved address -- an
 * advertisement this app itself didn't create (or one that hasn't finished resolving), not a valid discovery
 * result to surface. */
private fun ServiceInfo.toDiscoveredDevice(): DiscoveredDevice? {
    val deviceId = getPropertyString(PROPERTY_DEVICE_ID) ?: return null
    val host = hostAddresses.firstOrNull() ?: return null
    val roles =
        getPropertyString(PROPERTY_ROLES)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    return DiscoveredDevice(deviceId = deviceId, displayName = name, host = host, port = port, roles = roles)
}
