package fr.husi.bg

import android.net.Network
import android.os.Build
import fr.husi.repository.resolveAndroidRepository
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal data class NetworkReachability(
    val googleReachable: Boolean,
    val dzenReachable: Boolean,
    val yaReachable: Boolean,
    val whitelistSourceReachable: Boolean,
) {
    val hasInternet: Boolean
        get() = googleReachable || dzenReachable || yaReachable || whitelistSourceReachable

    val whitelistOnly: Boolean
        get() = !googleReachable && (dzenReachable || whitelistSourceReachable)
}

internal object NetworkReachabilityProbe {
    private const val GOOGLE_PROBE_URL = "http://www.google.com/generate_204"
    private const val DZEN_PROBE_URL = "http://dzen.ru"
    private const val YA_PROBE_URL = "https://ya.ru"
    private val WHITELIST_PROBE_URLS = listOf(
        "https://gitverse.ru/api/repos/bywarm/rser/raw/branch/master/selected.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist%20%E2%84%962.txt",
        "https://raw.githubusercontent.com/Mihuil121/vpn-checker-backend-fox/main/checked/RU_Best/ru_white.txt",
        "https://storage.yandexcloud.net/wall-breaker-c0de-a666/config.txt",
    )
    private const val TIMEOUT_MS = 1800
    private const val FAST_TIMEOUT_MS = 1200
    private const val PROBE_TOTAL_TIMEOUT_MS = 6000L
    private const val FAST_PROBE_TOTAL_TIMEOUT_MS = 4000L

    suspend fun probe(fast: Boolean = false): NetworkReachability = coroutineScope {
        val probeNetwork = resolveProbeNetwork()
        if (probeNetwork == null && resolveAndroidRepository().connectivity.activeNetwork == null) {
            return@coroutineScope NetworkReachability(
                googleReachable = false,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = false,
            )
        }
        val timeoutMs = if (fast) FAST_TIMEOUT_MS else TIMEOUT_MS
        val totalTimeoutMs = if (fast) FAST_PROBE_TOTAL_TIMEOUT_MS else PROBE_TOTAL_TIMEOUT_MS

        var googleOk = false
        var dzenOk = false
        var yaOk = false
        var whitelistOk = false

        val google = async(Dispatchers.IO) {
            probeUrl(GOOGLE_PROBE_URL, timeoutMs, probeNetwork).also { googleOk = it }
        }
        val dzen = async(Dispatchers.IO) {
            probeUrl(DZEN_PROBE_URL, timeoutMs, probeNetwork).also { dzenOk = it }
        }
        val ya = async(Dispatchers.IO) {
            probeUrl(YA_PROBE_URL, timeoutMs, probeNetwork).also { yaOk = it }
        }
        val whitelist = async(Dispatchers.IO) {
            anyWhitelistSourceReachable(timeoutMs, fast, probeNetwork).also { whitelistOk = it }
        }

        val completed = withTimeoutOrNull(totalTimeoutMs) {
            if (google.await()) {
                return@withTimeoutOrNull NetworkReachability(
                    googleReachable = true,
                    dzenReachable = dzenOk,
                    yaReachable = yaOk,
                    whitelistSourceReachable = whitelistOk,
                )
            }
            dzen.await()
            ya.await()
            whitelist.await()
            NetworkReachability(
                googleReachable = googleOk,
                dzenReachable = dzenOk,
                yaReachable = yaOk,
                whitelistSourceReachable = whitelistOk,
            )
        }

        val result = if (completed != null) {
            completed
        } else {
            google.cancel()
            dzen.cancel()
            ya.cancel()
            whitelist.cancel()
            NetworkReachability(
                googleReachable = googleOk,
                dzenReachable = dzenOk,
                yaReachable = yaOk,
                whitelistSourceReachable = whitelistOk,
            )
        }
        logReachabilityResult(fast, probeNetwork != null, result)
        return@coroutineScope result
    }

    private fun logReachabilityResult(
        fast: Boolean,
        probeNetBound: Boolean,
        r: NetworkReachability,
    ) {
        simpleModeLog(
            "SimpleMode",
            "H37 reachability_route fast=$fast underlyingNet=$probeNetBound route=direct_probe " +
                "google=${r.googleReachable} dzen=${r.dzenReachable} ya=${r.yaReachable} " +
                "wlSource=${r.whitelistSourceReachable} wlOnly=${r.whitelistOnly}",
        )
    }

    private suspend fun resolveProbeNetwork(): Network? {
        DefaultNetworkMonitor.defaultNetwork?.let { return it }
        return resolveAndroidRepository().connectivity.activeNetwork
    }

    private suspend fun anyWhitelistSourceReachable(
        timeoutMs: Int,
        fast: Boolean,
        network: Network?,
    ): Boolean = coroutineScope {
        val urls = if (fast) {
            WHITELIST_PROBE_URLS.take(2)
        } else {
            WHITELIST_PROBE_URLS
        }
        urls
            .map { url -> async(Dispatchers.IO) { probeUrl(url, timeoutMs, network) } }
            .awaitAll()
            .any { it }
    }

    private suspend fun probeUrl(
        url: String,
        timeoutMs: Int,
        network: Network? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val connection = openHttpConnection(url, network).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        runCatching {
            connection.connect()
            connection.responseCode in 200..399
        }.getOrElse {
            false
        }.also {
            connection.disconnect()
        }
    }

    private fun openHttpConnection(url: String, network: Network?): HttpURLConnection {
        val target = URL(url)
        return if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            network.openConnection(target) as HttpURLConnection
        } else {
            target.openConnection() as HttpURLConnection
        }
    }
}
