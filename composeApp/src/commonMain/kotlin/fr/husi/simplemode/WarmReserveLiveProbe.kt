package fr.husi.simplemode

import fr.husi.database.DirectProfileUrlProbe
import fr.husi.database.Probe2kDefaults
import fr.husi.database.ProxyProbeStateStore
import fr.husi.database.SagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object WarmReserveLiveProbe {

    suspend fun probeUrlDelaysParallel(
        profileIds: List<Long>,
        whitelistOnly: Boolean,
        onProgress: (done: Int, total: Int, profileId: Long, urlMs: Int?) -> Unit = { _, _, _, _ -> },
    ): Map<Long, Int?> {
        val uniqueIds = profileIds.distinct().filter { it > 0L }
        if (uniqueIds.isEmpty()) return emptyMap()
        val results = ConcurrentHashMap<Long, Int>()
        val total = uniqueIds.size
        val doneCounter = AtomicInteger(0)
        val semaphore = Semaphore(Probe2kDefaults.WARM_SWITCH_LIVE_PARALLELISM)
        withTimeoutOrNull(Probe2kDefaults.WARM_SWITCH_LIVE_BUDGET_MS) {
            coroutineScope {
                uniqueIds.map { id ->
                    async {
                        semaphore.withPermit {
                            val proxy = SagerDatabase.proxyDao.getById(id)
                            val ms = if (proxy != null) {
                                withContext(Dispatchers.IO) {
                                    DirectProfileUrlProbe.urlTestDelay(proxy, whitelistOnly = whitelistOnly)
                                }
                            } else {
                                null
                            }
                            if (ms != null) {
                                ProxyProbeStateStore.recordUrlSuccess(id, ms)
                                results[id] = ms
                            } else if (proxy != null) {
                                ProxyProbeStateStore.recordFailure(id, errorClass = "warm_live_probe_fail")
                            }
                        }
                        onProgress(doneCounter.incrementAndGet(), total, id, results[id])
                    }
                }.awaitAll()
            }
        }
        return uniqueIds.associateWith { results[it] }
    }
}
