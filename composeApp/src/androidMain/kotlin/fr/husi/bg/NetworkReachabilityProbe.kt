package fr.husi.bg

import fr.husi.repository.resolveAndroidRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    suspend fun probe(): NetworkReachability = coroutineScope {
        if (resolveAndroidRepository().connectivity.activeNetwork == null) {
            return@coroutineScope NetworkReachability(
                googleReachable = false,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = false,
            )
        }
        val google = async(Dispatchers.IO) { probeUrl(GOOGLE_PROBE_URL) }
        val dzen = async(Dispatchers.IO) { probeUrl(DZEN_PROBE_URL) }
        val ya = async(Dispatchers.IO) { probeUrl(YA_PROBE_URL) }
        if (google.await()) {
            return@coroutineScope NetworkReachability(
                googleReachable = true,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = false,
            )
        }
        val dzenOk = dzen.await()
        val yaOk = ya.await()
        val whitelistSourceReachable = anyWhitelistSourceReachable()
        NetworkReachability(
            googleReachable = false,
            dzenReachable = dzenOk,
            yaReachable = yaOk,
            whitelistSourceReachable = whitelistSourceReachable,
        )
    }

    private suspend fun anyWhitelistSourceReachable(): Boolean = coroutineScope {
        WHITELIST_PROBE_URLS
            .map { url -> async(Dispatchers.IO) { probeUrl(url) } }
            .awaitAll()
            .any { it }
    }

    private suspend fun probeUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
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
}
