package app.inkstave.shared.sync

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tries [connect] on each of [hosts] in order, moving on only when an address is unreachable (refused,
 * no route, timeout, unresolvable). Any other failure, such as a TLS rejection, is rethrown at once:
 * the device was reached and said no, and its other addresses would say the same.
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
