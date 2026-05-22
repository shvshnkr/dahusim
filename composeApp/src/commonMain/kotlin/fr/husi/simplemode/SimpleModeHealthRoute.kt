package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.utils.simpleModeLog
import java.net.URL

internal object SimpleModeHealthRoute {

    const val WL_WHITELIST_TXT_URL =
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt"

    /** Works on open networks; avoids broken HTTP probes to cp.cloudflare.com. */
    const val OPEN_NET_HEALTH_URL = "https://www.gstatic.com/generate_204"

    /** Reachable on many RU whitelist uplinks; exercised through tunnel routing. */
    private const val WL_DZEN_URL = "http://dzen.ru"
    private const val WL_YA_URL = "https://ya.ru"
    private const val WL_TELEGRAM_URL = "https://web.telegram.org"

    fun healthCheckUrls(whitelistOnly: Boolean): List<String> = if (whitelistOnly) {
        buildList {
            add(WL_DZEN_URL)
            add(WL_YA_URL)
            add(WL_TELEGRAM_URL)
            add(WL_WHITELIST_TXT_URL)
            val custom = DataStore.connectionTestURL
            if (custom.isNotBlank() && !isBlockedHealthHost(custom)) {
                add(custom)
            }
        }.distinct()
    } else {
        listOf(OPEN_NET_HEALTH_URL, DataStore.connectionTestURL).distinct()
    }

    fun postConnectWarmupMs(whitelistOnly: Boolean): Long = if (whitelistOnly) 1_500L else 400L

    /**
     * sing-box urlTest on Android often logs `dial rmnet_* → proxy:port` while the tunnel is still
     * coming up. That is not proof the outbound is dead on whitelist networks.
     */
    fun isLikelyUnderlyingProxyDialFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        return error.contains("dial rmnet", ignoreCase = true) ||
            error.contains("dial wlan", ignoreCase = true) ||
            error.contains("dial eth", ignoreCase = true)
    }

    fun logInconclusivePass(phase: String, whitelistOnly: Boolean, reason: String) {
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly inconclusive=$reason",
        )
    }

    enum class Route {
        DIRECT_PROFILE,
        TUNNEL_OUTBOUND,
    }

    fun logProbeConfig(
        phase: String,
        whitelistOnly: Boolean,
        route: Route,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
    ) {
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly route=${route.name.lowercase()} " +
                "outboundTag=${outboundTag.ifBlank { "-" }} timeoutMs=$timeoutMs " +
                "urls=${urls.joinToString(",") { urlHost(it) }}",
        )
    }

    fun logProbeAttempt(
        phase: String,
        whitelistOnly: Boolean,
        route: Route,
        outboundTag: String,
        url: String,
        ok: Boolean,
        delayMs: Int = 0,
        error: String? = null,
    ) {
        val result = if (ok) "ok delayMs=$delayMs" else "fail error=${error.orEmpty()}"
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly route=${route.name.lowercase()} " +
                "outboundTag=${outboundTag.ifBlank { "-" }} url=${urlHost(url)} $result",
        )
    }

    private fun isBlockedHealthHost(url: String): Boolean {
        val host = urlHost(url)
        return host.contains("cloudflare.com", ignoreCase = true) ||
            host.equals("cp.cloudflare.com", ignoreCase = true) ||
            host.contains("google.com", ignoreCase = true) ||
            host.contains("gstatic.com", ignoreCase = true)
    }

    private fun urlHost(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}
