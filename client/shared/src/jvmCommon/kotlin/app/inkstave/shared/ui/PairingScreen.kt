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
 * Discovers, pairs with, and manages trust for sync peers: the UI over `PairingSession`, `PairingServer`,
 * `JmDnsSyncDiscovery` and `PeerTrustStore` (`docs/sync-protocol.md`).
 *
 * While open, the device both advertises and accepts incoming pairing attempts ([PairingServer]) and browses for
 * devices the user can pair with ([PairingSession.initiate]); either direction leads to the same confirmation UI.
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

    // Advertise, accept and browse only while this screen is open, so the device stops being pairable
    // the moment the user leaves.
    DisposableEffect(Unit) {
        val acceptLoopRunning = AtomicBoolean(true)
        val disposed = AtomicBoolean(false)
        var discovery: SyncDiscovery? = null
        var server: PairingServer? = null
        var acceptThread: Thread? = null
        // Android only: must be held before advertising/browsing, or incoming mDNS replies are dropped.
        var multicastLock: AutoCloseable? = null

        // Setup does blocking I/O (JmDNS resolves the local host name; PairingServer reads the keystore), and a
        // DisposableEffect body runs on the UI thread, where Android throws NetworkOnMainThreadException. So it
        // runs on its own thread.
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
                            // Keyed by (deviceId, port): one device can advertise two listeners under one deviceId, such as
                            // this ephemeral PairingServer and a desktop's permanent pinned-trust SyncServer. Deduping by
                            // deviceId alone let one overwrite the other, so a tap could pair with the wrong listener.
                            discoveredDevices =
                                discoveredDevices.filterNot { it.deviceId == device.deviceId && it.port == device.port } + device
                        }

                        override fun onDeviceLost(deviceId: String) {
                            discoveredDevices = discoveredDevices.filterNot { it.deviceId == deviceId }
                        }
                    },
                )

                if (disposed.get()) {
                    // The screen was left before setup finished, so onDispose saw nothing to close: close it here.
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
                                    // server.close() on dispose unblocks accept() with this exception: the normal way out.
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
                    items(discoveredDevices, key = { "${it.deviceId}:${it.port}" }) { device ->
                        ListItem(
                            headlineContent = { Text(device.displayName) },
                            // Shows roles so two rows for the same device (see onDeviceFound) can be told apart.
                            supportingContent = { Text("roles: ${device.roles.sorted().joinToString(", ")}") },
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
