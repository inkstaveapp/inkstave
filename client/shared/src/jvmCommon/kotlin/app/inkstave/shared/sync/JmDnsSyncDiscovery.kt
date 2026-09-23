package app.inkstave.shared.sync

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
private const val ACTIVE_QUERY_TIMEOUT_MILLIS = 3_000L
private const val ACTIVE_QUERY_INTERVAL_MILLIS = 2_000L

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

    @Volatile private var closed = false

    /**
     * Reports devices via JmDNS's passive listener *and* an active re-query loop. The listener
     * alone proved unreliable on real hardware: a phone and a desktop both running this class,
     * both plainly visible to `avahi-browse`, often never saw each other in-app (minutes, or
     * never) because a single dropped multicast packet -- routine on Wi-Fi -- means the passive
     * listener simply never fires. [JmDNS.list] sends a fresh query and waits for answers, so
     * polling it every few seconds turns "one lost packet = never discovered" into "found on the
     * next round". Reporting the same device repeatedly is harmless: [SyncDeviceListener.onDeviceFound]
     * is documented as "advertised, or its info changed."
     */
    override fun browse(listener: SyncDeviceListener) {
        jmdns.addServiceListener(SYNC_SERVICE_TYPE, JmDnsListenerAdapter(jmdns, listener))
        Thread {
            while (!closed) {
                try {
                    jmdns.list(SYNC_SERVICE_TYPE, ACTIVE_QUERY_TIMEOUT_MILLIS).forEach { info ->
                        info.toDiscoveredDevice()?.let(listener::onDeviceFound)
                    }
                    Thread.sleep(ACTIVE_QUERY_INTERVAL_MILLIS)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: RuntimeException) {
                    if (closed) return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "inkstave-mdns-poll"
            start()
        }
    }

    /** Unregisters this device's own advertisement (if any) and shuts down the underlying [JmDNS] instance. */
    override fun close() {
        closed = true
        registeredService?.let { jmdns.unregisterService(it) }
        jmdns.close()
    }

    companion object {
        /**
         * A fresh [JmDnsSyncDiscovery], bound to [preferredLanAddress] when one can be found, or
         * the platform's own default-interface guess otherwise (`JmDNS.create()`'s no-arg form,
         * `InetAddress.getLocalHost()` under the hood).
         *
         * That default guess is not reliable enough to trust as-is: on real hardware, with real
         * Docker/Kubernetes-style virtual networking present (this project's own dev machine has
         * a dozen `br-*`/`docker0` bridge interfaces alongside its two real LAN ones), it produced
         * a `.smpk`-irrelevant but very real failure -- the desktop's advertisement carried no
         * usable IPv4 address at all, only an IPv6 link-local one JmDNS's own resolution didn't
         * attach a working network-interface scope to, so a phone trying to pair got `connect()
         * failed: EINVAL` on every attempt. [preferredLanAddress] sidesteps needing the platform's
         * own guess to be right at all.
         */
        fun create(): JmDnsSyncDiscovery =
            JmDnsSyncDiscovery(
                (parseAddressOverride(System.getenv(ADDRESS_OVERRIDE_ENV)) ?: preferredLanAddress())?.let { JmDNS.create(it) }
                    ?: JmDNS.create(),
            )

        /**
         * Name of the environment variable that forces the interface address mDNS binds to,
         * bypassing [preferredLanAddress]'s guess -- for machines with several real interfaces, and
         * for testing against an emulator on a private virtual bridge, where the "right" interface
         * is one no heuristic could know about.
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
         * The first real, LAN-routable IPv4 address this device has, or `null` if none is found
         * (falls back to the platform default in [create]) -- deliberately excludes loopback,
         * link-local, and interfaces whose name matches common virtual/container-networking
         * patterns (`docker`, `br-`, `veth`, `virbr`, `vboxnet`, `vmnet`, `tun`, `tap`): a name
         * -based heuristic, not a perfect one, but real container/VM networking tooling
         * overwhelmingly uses these conventions, and a private (RFC 1918) address on one of them is
         * never going to be the LAN a phone is actually trying to reach this device over.
         */
        private fun preferredLanAddress(): Inet4Address? =
            NetworkInterface
                .getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !isLikelyVirtual(it.name) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .firstOrNull()

        private val VIRTUAL_INTERFACE_NAME_PREFIXES =
            listOf("docker", "br-", "veth", "virbr", "vboxnet", "vmnet", "tun", "tap")

        private fun isLikelyVirtual(interfaceName: String): Boolean {
            val lower = interfaceName.lowercase()
            return VIRTUAL_INTERFACE_NAME_PREFIXES.any { lower.startsWith(it) }
        }
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
    val roles =
        getPropertyString(PROPERTY_ROLES)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    return DiscoveredDevice(deviceId = deviceId, displayName = name, host = host, port = port, roles = roles)
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
