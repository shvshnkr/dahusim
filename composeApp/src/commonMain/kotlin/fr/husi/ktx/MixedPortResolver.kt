package fr.husi.ktx

import fr.husi.database.DataStore
import fr.husi.fmt.LOCALHOST4
import fr.husi.utils.simpleModeLog
import java.net.InetSocketAddress
import java.net.ServerSocket

private const val MAX_PORT_OFFSET = 32

fun mixedInboundBindHost(): String =
    if (DataStore.allowAccess) "0.0.0.0" else LOCALHOST4

fun isLocalPortFree(host: String, port: Int): Boolean {
    if (port <= 0 || port > 65535) return false
    return runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(host, port))
        }
        true
    }.getOrDefault(false)
}

/**
 * Picks a listen port for mixed inbound, starting at [DataStore.mixedPort].
 * Persists the first free port so SOCKS clients and the next start stay aligned.
 */
fun ensureMixedPortAvailable(maxOffset: Int = MAX_PORT_OFFSET): Int {
    val host = mixedInboundBindHost()
    val base = DataStore.mixedPort
    for (offset in 0 until maxOffset) {
        val candidate = base + offset
        if (!isLocalPortFree(host, candidate)) continue
        if (candidate != DataStore.mixedPort) {
            Logs.w("Mixed port $base busy, using $candidate on $host")
            simpleModeLog("SimpleMode", "H32 mixed_port_fallback from=$base to=$candidate host=$host")
            DataStore.mixedPort = candidate
        }
        return candidate
    }
    val ephemeral = mkPort()
    Logs.w("Mixed port scan from $base exhausted on $host, using ephemeral $ephemeral")
    simpleModeLog("SimpleMode", "H32 mixed_port_ephemeral from=$base to=$ephemeral host=$host")
    DataStore.mixedPort = ephemeral
    return ephemeral
}

fun isMixedPortBindFailure(throwable: Throwable): Boolean {
    val message = throwable.readableMessage.lowercase()
    return message.contains("bind") &&
        (
            message.contains("mixed-in") ||
                message.contains("mixed[") ||
                message.contains("listen tcp") ||
                message.contains("socket address")
            )
}
