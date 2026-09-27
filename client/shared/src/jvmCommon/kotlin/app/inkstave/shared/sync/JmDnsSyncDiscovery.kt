package app.inkstave.shared.sync

import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** mDNS service type for Inkstave (`docs/sync-protocol.md`). */
const val SYNC_SERVICE_TYPE = "_inkstave._tcp.local."

private const val PROPERTY_DEVICE_ID = "deviceId"
private const val PROPERTY_ROLES = "roles"
private const val PROPERTY_DISPLAY_NAME = "name"
private const val ACTIVE_QUERY_TIMEOUT_MILLIS = 3_000L
private const val ACTIVE_QUERY_INTERVAL_MILLIS = 2_000L

/**
 * [SyncDiscovery] backed by [JmDNS] (pure Java, so it works unchanged on Android and desktop).
 *
 * Runs one responder per real LAN interface ([lanAddresses]) rather than guessing one: multicast
 * between two Wi-Fi clients is often lost at the access point, so the interface that happens to be
 * chosen may never see the other device.
 */
class JmDnsSyncDiscovery private constructor(
    private val responders: List<JmDNS>,
) : SyncDiscovery {
    private val registered = mutableListOf<Pair<JmDNS, ServiceInfo>>()

    @Volatile private var closed = false

    /**
     * Advertises [identity] on every responder. The display name also goes in the TXT record,
     * because JmDNS renames duplicate instance names ("Name (2)") and that must not reach the UI.
     */
    override fun advertise(
        identity: DeviceIdentity,
        port: Int,
        roles: Set<String>,
    ) {
        val props =
            mapOf(
                PROPERTY_DEVICE_ID to identity.deviceId,
                PROPERTY_ROLES to roles.joinToString(","),
                PROPERTY_DISPLAY_NAME to identity.displayName,
            )
        responders.forEach { responder ->
            // A ServiceInfo holds per-responder registration state, so each needs its own.
            val info = ServiceInfo.create(SYNC_SERVICE_TYPE, identity.displayName, port, 0, 0, props)
            responder.registerService(info)
            synchronized(registered) { registered += responder to info }
        }
    }

    /**
     * Reports devices via the passive listener plus an active re-query every few seconds, on every
     * responder, merging addresses per device. The passive listener alone misses devices after a
     * single dropped multicast packet. Repeated reports of the same device are harmless.
     */
    override fun browse(listener: SyncDeviceListener) {
        val merged = MergingDeviceListener(listener)
        responders.forEach { it.addServiceListener(SYNC_SERVICE_TYPE, JmDnsListenerAdapter(it, merged)) }
        Thread {
            while (!closed) {
                responders.forEach { responder ->
                    try {
                        responder.list(SYNC_SERVICE_TYPE, ACTIVE_QUERY_TIMEOUT_MILLIS).forEach { info ->
                            info.toDiscoveredDevice()?.let(merged::onDeviceFound)
                        }
                    } catch (e: RuntimeException) {
                        if (closed) return@Thread
                    }
                }
                try {
                    Thread.sleep(ACTIVE_QUERY_INTERVAL_MILLIS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "inkstave-mdns-poll"
            start()
        }
    }

    /** Unregisters this device's own advertisements (if any) and shuts down every responder. */
    override fun close() {
        closed = true
        synchronized(registered) { registered.forEach { (responder, info) -> responder.unregisterService(info) } }
        responders.forEach { it.close() }
    }

    companion object {
        /**
         * One responder per address in [lanAddresses] (or only the [ADDRESS_OVERRIDE_ENV] address),
         * falling back to JmDNS's own interface guess only when none exists. Responders that fail to
         * start are skipped. The default guess is unreliable with container bridges present: it can pick
         * an unroutable IPv6 link-local address.
         */
        fun create(): JmDnsSyncDiscovery {
            val addresses = parseAddressOverride(System.getenv(ADDRESS_OVERRIDE_ENV))?.let(::listOf) ?: lanAddresses()
            val responders =
                addresses.mapNotNull { address ->
                    try {
                        JmDNS.create(address)
                    } catch (e: IOException) {
                        null
                    }
                }
            return JmDnsSyncDiscovery(responders.ifEmpty { listOf(JmDNS.create()) })
        }

        /** Environment variable restricting mDNS to one IPv4 address (emulator testing, diagnosis). */
        const val ADDRESS_OVERRIDE_ENV = "INKSTAVE_MDNS_ADDRESS"

        private val IPV4_LITERAL = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

        /** The IPv4 address in [raw], or `null` when unset, blank, or not a literal IPv4 address. */
        internal fun parseAddressOverride(raw: String?): Inet4Address? {
            val text = raw?.trim().orEmpty()
            if (!IPV4_LITERAL.matches(text)) return null
            return java.net.InetAddress.getByName(text) as? Inet4Address
        }

        /**
         * Every LAN-routable IPv4 address: interfaces that are up and multicast-capable, excluding
         * loopback, link-local, and interfaces named like container/VM or cellular networking.
         */
        private fun lanAddresses(): List<Inet4Address> =
            NetworkInterface
                .getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && !isLikelyVirtual(it.name) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .distinct()
                .toList()

        // rmnet/ccmni are Android cellular-data interfaces: LAN discovery is meaningless on them.
        private val VIRTUAL_INTERFACE_NAME_PREFIXES =
            listOf("docker", "br-", "veth", "virbr", "vboxnet", "vmnet", "tun", "tap", "rmnet", "ccmni", "dummy")

        private fun isLikelyVirtual(interfaceName: String): Boolean {
            val lower = interfaceName.lowercase()
            return VIRTUAL_INTERFACE_NAME_PREFIXES.any { lower.startsWith(it) }
        }
    }
}

/**
 * Merges one device's sightings across responders into a single [DiscoveredDevice] listing every
 * address, in first-seen order. Keyed by (deviceId, port): another port on the same device is a
 * different listener (pairing vs. sync server) and stays a separate entry.
 */
internal class MergingDeviceListener(
    private val delegate: SyncDeviceListener,
) : SyncDeviceListener {
    private val hostsByListener = HashMap<Pair<String, Int>, LinkedHashSet<String>>()

    @Synchronized
    override fun onDeviceFound(device: DiscoveredDevice) {
        val hosts = hostsByListener.getOrPut(device.deviceId to device.port) { LinkedHashSet() }
        hosts.addAll(device.hosts)
        delegate.onDeviceFound(device.copy(hosts = hosts.toList()))
    }

    @Synchronized
    override fun onDeviceLost(deviceId: String) {
        hostsByListener.keys.removeAll { it.first == deviceId }
        delegate.onDeviceLost(deviceId)
    }
}

/**
 * Adapts JmDNS's [ServiceListener] to [SyncDeviceListener], reporting a device only once it has
 * resolved (`serviceAdded` carries no address, port or TXT data yet).
 */
private class JmDnsListenerAdapter(
    private val jmdns: JmDNS,
    private val listener: SyncDeviceListener,
) : ServiceListener {
    /** Requests resolution explicitly; otherwise JmDNS may never fire `serviceResolved`. */
    override fun serviceAdded(event: ServiceEvent) {
        jmdns.requestServiceInfo(event.type, event.name)
    }

    override fun serviceResolved(event: ServiceEvent) {
        event.info.toDiscoveredDevice()?.let(listener::onDeviceFound)
    }

    override fun serviceRemoved(event: ServiceEvent) {
        val deviceId = event.info.getPropertyString(PROPERTY_DEVICE_ID) ?: event.name
        listener.onDeviceLost(deviceId)
    }
}

/** `null` when the service has no [PROPERTY_DEVICE_ID] (not ours) or no resolved address yet. */
private fun ServiceInfo.toDiscoveredDevice(): DiscoveredDevice? {
    val deviceId = getPropertyString(PROPERTY_DEVICE_ID) ?: return null
    val host = preferredHostAddress() ?: return null
    val displayName = getPropertyString(PROPERTY_DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: name
    val roles =
        getPropertyString(PROPERTY_ROLES)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    return DiscoveredDevice(deviceId = deviceId, displayName = displayName, hosts = listOf(host), port = port, roles = roles)
}

/**
 * Prefers IPv4, falling back to IPv6 only if none resolved: bare IPv6 link-local addresses carry no
 * scope id and can't be connected to.
 */
private fun ServiceInfo.preferredHostAddress(): String? = inet4Addresses.firstOrNull()?.hostAddress ?: hostAddresses.firstOrNull()
