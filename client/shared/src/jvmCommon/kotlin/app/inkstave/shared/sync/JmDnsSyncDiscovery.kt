package app.inkstave.shared.sync

import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
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
private const val PROPERTY_DISPLAY_NAME = "name"
private const val ACTIVE_QUERY_TIMEOUT_MILLIS = 3_000L
private const val ACTIVE_QUERY_INTERVAL_MILLIS = 2_000L

/**
 * The only [SyncDiscovery] implementation for v1 (`docs/decisions/0003-sync-approach.md`), backed
 * by [JmDNS] -- pure Java, no native code, no platform-specific mDNS binding needed, so this one
 * class works unmodified on both `androidMain` and `desktopMain` (unlike, say, `PageBitmap`'s
 * genuinely platform-divergent decoding, this doesn't need an `expect`/`actual` split at all).
 *
 * Runs one [JmDNS] responder per real LAN interface ([lanAddresses]), advertising and browsing on
 * all of them. A single responder bound to one interface proved unreliable on real hardware: a
 * desktop with both Ethernet and Wi-Fi picked Wi-Fi, and a tablet on the same Wi-Fi then saw the
 * desktop on only 2 of 5 cold starts (multicast between two Wi-Fi clients through the access
 * point was being lost); bound to Ethernet it found the desktop on 8 of 8. Covering every
 * interface avoids having to guess which one is reliable on a given network.
 */
class JmDnsSyncDiscovery private constructor(
    private val responders: List<JmDNS>,
) : SyncDiscovery {
    private val registered = mutableListOf<Pair<JmDNS, ServiceInfo>>()

    @Volatile private var closed = false

    /**
     * Advertises [identity] under [SYNC_SERVICE_TYPE], reachable on [port], offering [roles] in its
     * TXT record, on every responder. The display name also travels in the TXT record: separate
     * responders in one process (and the separate pairing/sync listeners) register the same
     * instance name, which JmDNS resolves by renaming to "Name (2)", "Name (3)" -- an internal
     * detail that shouldn't reach the UI.
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
     * Reports devices via JmDNS's passive listener *and* an active re-query loop, on every
     * responder, merging each device's addresses across responders ([MergingDeviceListener]). The
     * passive listener alone proved unreliable on real hardware: a single dropped multicast packet
     * -- routine on Wi-Fi -- means it simply never fires. [JmDNS.list] sends a fresh query and waits
     * for answers, so polling it every few seconds turns "one lost packet = never discovered" into
     * "found on the next round". Reporting the same device repeatedly is harmless:
     * [SyncDeviceListener.onDeviceFound] is documented as "advertised, or its info changed."
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
         * A fresh [JmDnsSyncDiscovery] with one responder per address in [lanAddresses] (or just the
         * [ADDRESS_OVERRIDE_ENV] address when that is set), falling back to the platform's own
         * default-interface guess (`JmDNS.create()`'s no-arg form) only when no usable address
         * exists at all. An interface whose responder fails to start is skipped rather than
         * failing discovery as a whole.
         *
         * The default guess is not reliable enough to trust as-is: on real hardware, with
         * Docker-style virtual networking present (this project's own dev machine has a dozen
         * `br-*`/`docker0` bridges alongside its two real LAN interfaces), it produced an
         * advertisement carrying only an IPv6 link-local address without a usable scope, so a
         * phone trying to pair got `connect() failed: EINVAL` on every attempt.
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

        /**
         * Name of the environment variable that restricts mDNS to one interface address, bypassing
         * [lanAddresses] -- for testing against an emulator on a private virtual bridge, or for
         * pinning discovery to one interface when diagnosing a network.
         */
        const val ADDRESS_OVERRIDE_ENV = "INKSTAVE_MDNS_ADDRESS"

        private val IPV4_LITERAL = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

        /** The IPv4 address in [raw], or `null` when unset, blank, or not a literal IPv4 address. */
        internal fun parseAddressOverride(raw: String?): Inet4Address? {
            val text = raw?.trim().orEmpty()
            if (!IPV4_LITERAL.matches(text)) return null
            return java.net.InetAddress.getByName(text) as? Inet4Address
        }

        /**
         * Every real, LAN-routable IPv4 address this device has: interfaces that are up, support
         * multicast, aren't loopback, and aren't named like virtual/container networking or
         * cellular data ([isLikelyVirtual]); loopback and link-local addresses excluded. A name
         * heuristic, not a perfect one, but container/VM tooling overwhelmingly uses these
         * conventions, and a phone is never going to reach this device over one of them.
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
 * Combines the per-responder sightings of one device into a single [DiscoveredDevice] whose
 * [DiscoveredDevice.hosts] lists every address it has been seen at, in first-seen order. Keyed by
 * (deviceId, port): the same deviceId on another port is a genuinely different listener (a
 * desktop's pairing server vs. its sync server) and must stay a separate entry.
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
 * Bridges [JmDNS]'s own [ServiceListener] callback shape to [SyncDeviceListener], and does the
 * one piece of real translation work: [ServiceListener.serviceAdded] fires with a bare name and
 * no resolved address/TXT-record data yet (per JmDNS's own contract), so this only reports a
 * device via [SyncDeviceListener.onDeviceFound] once [ServiceListener.serviceResolved] actually
 * has that data -- reporting on `serviceAdded` instead would hand callers a [DiscoveredDevice]
 * with no real host/port/roles to act on.
 */
private class JmDnsListenerAdapter(
    private val jmdns: JmDNS,
    private val listener: SyncDeviceListener,
) : ServiceListener {
    /**
     * Explicitly asks JmDNS to resolve the service (address, port, TXT record). Ignoring this
     * callback left resolution to chance: JmDNS only fires `serviceResolved` on its own when the
     * full record set happens to arrive alongside the announcement, so on real hardware (a phone
     * and a desktop, both running this class, both visible to `avahi-browse` the whole time)
     * neither app's in-process browse would find the other for minutes, or at all, depending on
     * packet timing. `requestServiceInfo` is the documented way to make resolution deterministic.
     */
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

/** `null` if [ServiceInfo] is missing the [PROPERTY_DEVICE_ID] property or has no resolved address -- an
 * advertisement this app itself didn't create (or one that hasn't finished resolving), not a valid discovery
 * result to surface. */
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
 * An IPv4 address, if this service resolved one at all; only falls back to IPv6 if it truly
 * didn't. [ServiceInfo.getHostAddresses] (what this used to use, via `.firstOrNull()`) returns
 * whatever mix of IPv4/IPv6 addresses JmDNS resolved in whatever order it happened to produce
 * them -- on real hardware this picked an IPv6 address literally the first time two real devices
 * (a phone and a desktop) ever tried to pair, and `Socket.connect()` to it failed outright
 * (`EINVAL` on Android, a terminated TLS handshake on desktop) -- consistent with a link-local
 * IPv6 address JmDNS resolved without the network-interface scope id a bare address string needs
 * to actually be routable (the same class of Android/JmDNS network-interface friction the
 * `NetworkTopologyDiscoveryImpl` crash already found). A typical home LAN always has IPv4
 * available, so preferring it sidesteps needing to get IPv6 scope-id handling right at all,
 * rather than attempting that and hoping it's correct.
 */
private fun ServiceInfo.preferredHostAddress(): String? = inet4Addresses.firstOrNull()?.hostAddress ?: hostAddresses.firstOrNull()
