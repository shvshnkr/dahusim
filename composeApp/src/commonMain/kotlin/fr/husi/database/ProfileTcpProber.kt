package fr.husi.database

import fr.husi.libcore.Libcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

internal object ProfileTcpProber {

    suspend fun probeTcpBatch(
        proxies: List<ProxyEntity>,
        concurrency: Int,
        timeoutMs: Int = Probe2kDefaults.TCP_PROBE_TIMEOUT_MS,
        isActive: () -> Boolean = { true },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, Int> = coroutineScope {
        val total = proxies.size
        if (total == 0) return@coroutineScope emptyMap()
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val result = HashMap<Long, Int>()
        val done = AtomicInteger(0)
        var lastReported = 0
        fun reportProgress() {
            val count = done.incrementAndGet()
            if (count == total || count - lastReported >= 8) {
                lastReported = count
                onProgress(count, total)
            }
        }
        onProgress(0, total)
        proxies.map { proxy ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!isActive() || !currentCoroutineContext().isActive) return@withPermit
                    try {
                        val bean = runCatching { proxy.requireBean() }.getOrNull() ?: return@withPermit
                        val address = bean.serverAddress.takeIf { it.isNotBlank() } ?: return@withPermit
                        val port = bean.serverPort
                        if (port <= 0) return@withPermit
                        val ping = runCatching {
                            Libcore.tcpPing(address, port.toString(), timeoutMs)
                        }.getOrNull() ?: return@withPermit
                        if (ping > 0) {
                            synchronized(result) {
                                result[proxy.id] = ping
                            }
                        }
                    } finally {
                        reportProgress()
                    }
                }
            }
        }.awaitAll()
        result.toMap()
    }
}
