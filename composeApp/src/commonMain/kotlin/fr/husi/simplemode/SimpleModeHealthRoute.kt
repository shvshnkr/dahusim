package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.utils.simpleModeLog
import java.net.URL

internal object SimpleModeHealthRoute {

    const val WL_WHITELIST_TXT_URL =
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt"

    /** Works on open networks; avoids broken HTTP probes to cp.cloudflare.com. */
    const val OPEN_NET_HEALTH_URL = "https://www.gstatic.com/generate_204"

    fun healthCheckUrls(whitelistOnly: Boolean): List<String> = if (whitelistOnly) {
        listOf(WL_WHITELIST_TXT_URL, DataStore.connectionTestURL)
    } else {
        listOf(OPEN_NET_HEALTH_URL, DataStore.connectionTestURL).distinct()
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

    private fun urlHost(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}
