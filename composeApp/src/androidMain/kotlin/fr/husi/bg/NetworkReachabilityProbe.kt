package fr.husi.bg

import fr.husi.repository.resolveAndroidRepository
import java.net.HttpURLConnection
import java.net.URL

internal data class NetworkReachability(
    val googleReachable: Boolean,
    val dzenReachable: Boolean,
    val whitelistSourceReachable: Boolean,
) {
    val hasInternet: Boolean
        get() = googleReachable || dzenReachable || whitelistSourceReachable

    val whitelistOnly: Boolean
        get() = !googleReachable && (dzenReachable || whitelistSourceReachable)
}

internal object NetworkReachabilityProbe {
    private const val GOOGLE_PROBE_URL = "http://www.google.com/generate_204"
    private const val DZEN_PROBE_URL = "http://dzen.ru"
    private val WHITELIST_PROBE_URLS = listOf(
        "https://gitverse.ru/api/repos/bywarm/rser/raw/branch/master/selected.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist%20%E2%84%962.txt",
        "https://storage.yandexcloud.net/wall-breaker-c0de-a666/config.txt",
    )
    private const val TIMEOUT_MS = 1800

    fun probe(): NetworkReachability {
        val activeNetwork = resolveAndroidRepository().connectivity.activeNetwork
        if (activeNetwork == null) {
            return NetworkReachability(
                googleReachable = false,
                dzenReachable = false,
                whitelistSourceReachable = false,
            )
        }
        val google = probeUrl(GOOGLE_PROBE_URL)
        val dzen = probeUrl(DZEN_PROBE_URL)
        val whitelistSourceReachable = if (google) {
            false
        } else {
            WHITELIST_PROBE_URLS.any { probeUrl(it) }
        }
        return NetworkReachability(
            googleReachable = google,
            dzenReachable = dzen,
            whitelistSourceReachable = whitelistSourceReachable,
        )
    }

    private fun probeUrl(url: String): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return runCatching {
            connection.connect()
            connection.responseCode in 200..399
        }.getOrElse {
            false
        }.also {
            connection.disconnect()
        }
    }
}
