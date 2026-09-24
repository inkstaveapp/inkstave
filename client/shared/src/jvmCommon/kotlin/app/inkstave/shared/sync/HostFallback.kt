package app.inkstave.shared.sync

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Calls [connect] with each of [hosts] in order and returns the first result, moving on to the
 * next address only when the current one is *unreachable* (refused, no route, timed out, or
 * unresolvable). Any other failure -- a TLS handshake rejection, a fingerprint mismatch -- is
 * rethrown immediately: that means the device *was* reached and said no, and retrying the same
 * device on another of its addresses would only repeat the same answer.
 *
 * Exists because a device can advertise several addresses (one per network interface, see
 * `JmDnsSyncDiscovery`), and not every one is necessarily reachable from where the caller is.
 */
internal inline fun <T> firstReachable(
    hosts: List<String>,
    connect: (host: String) -> T,
): T {
    var lastUnreachable: IOException? = null
    for (host in hosts) {
        try {
            return connect(host)
        } catch (e: IOException) {
            if (!e.isUnreachable()) throw e
            lastUnreachable = e
        }
    }
    throw lastUnreachable ?: IOException("no address to connect to")
}

internal fun IOException.isUnreachable(): Boolean =
    this is ConnectException || this is NoRouteToHostException || this is SocketTimeoutException || this is UnknownHostException
