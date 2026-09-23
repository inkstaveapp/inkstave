package app.inkstave.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.inkstave.shared.sync.DeviceIdentity
import app.inkstave.shared.sync.DeviceRole
import app.inkstave.shared.sync.DiscoveredDevice
import app.inkstave.shared.sync.JmDnsSyncDiscovery
import app.inkstave.shared.sync.PairingOutcome
import app.inkstave.shared.sync.PairingServer
import app.inkstave.shared.sync.PairingSession
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.SyncDeviceListener
import app.inkstave.shared.sync.SyncDiscovery
import app.inkstave.shared.sync.TrustedPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discover, pair with, and manage trust for sync peers (`ROADMAP.md` M4, `docs/sync-protocol.md`)
 * -- the UI half of `app.inkstave.shared.sync`'s pairing protocol (`PairingSession`/
 * `PairingServer`/`JmDnsSyncDiscovery`/`PeerTrustStore`, all thoroughly tested at the protocol
 * level in `jvmCommonTest`/`desktopTest`; this screen is the thin orchestration layer on top).
 *
 * While this screen is open, this device does two things at once, deliberately symmetric rather
 * than a mode the user picks (`docs/sync-protocol.md` describes pairing as "one device displays a
 * ... code, the other enters/scans it" without mandating which role either device takes): it
 * advertises itself and listens for an incoming pairing attempt ([PairingServer]), and it browses
 * for other advertised devices the user can tap "Pair" on to initiate a connection themselves
 * ([PairingSession.initiate]). Whichever happens first surfaces the same confirmation UI.
 *
 * **Not yet wired into a UI test**: real Compose UI interaction testing hits the same pre-existing
 * `compose.uiTest`/Skiko native-library issue every other UI test in this codebase already does
 * (`client/README.md`'s "Known rough edges") -- not a new gap this screen introduces. The
 * pairing/discovery/trust logic it orchestrates is fully tested at the protocol level instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    settingsDirectory: File,
    localIdentity: DeviceIdentity,
    trustStore: PeerTrustStore,
    onBack: () -> Unit,
    acquireMulticastLock: (() -> AutoCloseable)? = null,
) {
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var trustedPeers by remember { mutableStateOf(trustStore.list()) }
    var pendingConfirmation by remember { mutableStateOf<PairingOutcome.AwaitingConfirmation?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Advertise + accept + browse for the lifetime of this screen; torn down on dispose so this
    // device stops being discoverable/pairable the moment the user navigates away, per
    // docs/sync-protocol.md's "discovery of an unpaired device only offers 'pair with this
    // device'" -- that offer itself shouldn't stand indefinitely in the background either.
    DisposableEffect(Unit) {
        val acceptLoopRunning = AtomicBoolean(true)
        val disposed = AtomicBoolean(false)
        var discovery: SyncDiscovery? = null
        var server: PairingServer? = null
        var acceptThread: Thread? = null
        // Android only (desktop's default null means "nothing to hold") -- must be acquired
        // before JmDnsSyncDiscovery.create()'s advertise/browse calls have anything meaningful
        // to do, and released on dispose alongside discovery/server. See App's own doc on this
        // parameter for why it exists at all: a real-device test caught that without it, mDNS
        // replies would plausibly be silently dropped by Android's WiFi stack.
        var multicastLock: AutoCloseable? = null

        // Everything below -- JmDnsSyncDiscovery.create() especially -- does blocking I/O
        // (JmDNS's own setup does a DNS lookup via InetAddress.getLocalHost(); PairingServer's
        // construction touches the on-disk TLS keystore). A DisposableEffect body runs
        // synchronously on the composing/UI thread and can't use withContext the way a suspend
        // call site (e.g. LibraryScreen's sendCaptureSession wiring) already correctly does --
        // calling JmDnsSyncDiscovery.create() directly here crashed on real Android hardware with
        // NetworkOnMainThreadException, a bug only real-device testing could catch (every prior
        // test of this exercised it from a plain JVM test, which has no such restriction). Running
        // all of this setup on its own background thread instead fixes it.
        val setupThread =
            Thread {
                val createdLock = acquireMulticastLock?.invoke()
                val createdDiscovery = JmDnsSyncDiscovery.create()
                val createdServer = PairingServer(settingsDirectory)
                createdDiscovery.advertise(localIdentity, createdServer.boundPort, setOf(DeviceRole.CAPTURE, DeviceRole.PROCESSING))
                createdDiscovery.browse(
                    object : SyncDeviceListener {
                        override fun onDeviceFound(device: DiscoveredDevice) {
                            if (device.deviceId == localIdentity.deviceId) return
                            discoveredDevices = discoveredDevices.filterNot { it.deviceId == device.deviceId } + device
                        }

                        override fun onDeviceLost(deviceId: String) {
                            discoveredDevices = discoveredDevices.filterNot { it.deviceId == deviceId }
                        }
                    },
                )

                if (disposed.get()) {
                    // The screen was already left by the time setup finished -- onDispose ran
                    // against still-null discovery/server/multicastLock, so close what was just
                    // opened here instead of leaking it.
                    createdServer.close()
                    createdDiscovery.close()
                    createdLock?.close()
                    return@Thread
                }
                discovery = createdDiscovery
                server = createdServer
                multicastLock = createdLock
                acceptThread =
                    Thread {
                        while (acceptLoopRunning.get()) {
                            val outcome =
                                try {
                                    createdServer.acceptOne(localIdentity)
                                } catch (e: IOException) {
                                    // server.close() (below, on dispose) unblocks the in-progress
                                    // accept() call with exactly this exception -- the expected
                                    // way this loop ends, not a failure to report.
                                    break
                                }
                            pendingConfirmation = outcome as? PairingOutcome.AwaitingConfirmation
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
            }.apply {
                isDaemon = true
                start()
            }

        onDispose {
            disposed.set(true)
            acceptLoopRunning.set(false)
            server?.close()
            discovery?.close()
            multicastLock?.close()
            acceptThread?.interrupt()
            setupThread.interrupt()
        }
    }

    fun confirmPending() {
        val outcome = pendingConfirmation ?: return
        trustStore.add(TrustedPeer(outcome.peerIdentity.deviceId, outcome.peerIdentity.displayName, outcome.fingerprintSha256))
        trustedPeers = trustStore.list()
        statusMessage = "Paired with ${outcome.peerIdentity.displayName}"
        pendingConfirmation = null
    }

    fun startPairingWith(device: DiscoveredDevice) {
        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { PairingSession.initiate(device, localIdentity, settingsDirectory) }) {
                is PairingOutcome.AwaitingConfirmation -> pendingConfirmation = outcome
                is PairingOutcome.Failed -> statusMessage = "Pairing with ${device.displayName} failed: ${outcome.reason}"
            }
        }
    }

    fun revoke(deviceId: String) {
        trustStore.remove(deviceId)
        trustedPeers = trustStore.list()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Sync Pairing") }) }) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            Text("This device: ${localIdentity.displayName}", style = MaterialTheme.typography.bodyMedium)

            pendingConfirmation?.let { confirmation ->
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(TestTags.PAIRING_CONFIRMATION),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Pair with ${confirmation.peerIdentity.displayName}?", style = MaterialTheme.typography.titleSmall)
                        Text("Confirm this code matches on both devices:")
                        Text(
                            confirmation.shortCode,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag(TestTags.PAIRING_SHORT_CODE),
                        )
                        Row {
                            TextButton(onClick = ::confirmPending, modifier = Modifier.testTag(TestTags.PAIRING_CONFIRM)) {
                                Text("Confirm")
                            }
                            TextButton(
                                onClick = { pendingConfirmation = null },
                                modifier = Modifier.testTag(TestTags.PAIRING_REJECT),
                            ) { Text("Reject") }
                        }
                    }
                }
            }

            statusMessage?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }

            Text("Nearby devices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            if (discoveredDevices.isEmpty()) {
                Text("Looking for devices on the same network...", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.testTag(TestTags.PAIRING_DEVICE_LIST)) {
                    items(discoveredDevices, key = DiscoveredDevice::deviceId) { device ->
                        ListItem(
                            headlineContent = { Text(device.displayName) },
                            trailingContent = {
                                TextButton(
                                    onClick = { startPairingWith(device) },
                                    modifier = Modifier.testTag(TestTags.pairingDeviceButton(device.deviceId)),
                                ) { Text("Pair") }
                            },
                        )
                    }
                }
            }

            Text("Trusted devices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            if (trustedPeers.isEmpty()) {
                Text("No paired devices yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.testTag(TestTags.PAIRING_TRUSTED_LIST)) {
                    items(trustedPeers, key = TrustedPeer::deviceId) { peer ->
                        ListItem(
                            headlineContent = { Text(peer.displayName) },
                            trailingContent = {
                                TextButton(
                                    onClick = { revoke(peer.deviceId) },
                                    modifier = Modifier.testTag(TestTags.pairingRevokeButton(peer.deviceId)),
                                ) { Text("Revoke") }
                            },
                        )
                    }
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp).testTag(TestTags.PAIRING_BACK)) { Text("Back") }
        }
    }
}
