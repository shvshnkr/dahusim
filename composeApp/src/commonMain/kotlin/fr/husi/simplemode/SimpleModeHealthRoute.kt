package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.utils.simpleModeLog
import java.net.URL

internal object SimpleModeHealthRoute {

    const val WL_WHITELIST_TXT_URL =
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt"

    /** Foreign target; must go through tunnel on whitelist networks. */
    const val TUNNEL_HEALTH_CLOUDFLARE_HTTPS = "https://cp.cloudflare.com/"

    const val TUNNEL_HEALTH_GSTATIC = "https://www.gstatic.com/generate_204"

    const val TUNNEL_HEALTH_TELEGRAM = "https://web.telegram.org"

    /** Reachable on many RU whitelist subnets at L3/L7 (same probes as [NetworkReachabilityProbe]). */
    const val WL_YA_HTTPS = "https://ya.ru/"

    const val WL_DZEN_HTTP = "http://dzen.ru/"

    fun wlCoreHealthUrls(): List<String> = listOf(
        WL_YA_HTTPS,
        WL_DZEN_HTTP,
        TUNNEL_HEALTH_GSTATIC,
        TUNNEL_HEALTH_CLOUDFLARE_HTTPS,
        TUNNEL_HEALTH_TELEGRAM,
        WL_WHITELIST_TXT_URL,
    )

    fun healthCheckUrls(whitelistOnly: Boolean): List<String> = if (whitelistOnly) {
        buildList {
            addAll(wlCoreHealthUrls())
            add(normalizeTunnelHealthUrl(DataStore.connectionTestURL))
        }.distinct().filter { it.isNotBlank() }
    } else {
        listOf(TUNNEL_HEALTH_GSTATIC, normalizeTunnelHealthUrl(DataStore.connectionTestURL))
            .distinct()
            .filter { it.isNotBlank() }
    }

    /** Post-connect on WL: ya/dzen/gstatic — fewer round-trips than the full prepare list. */
    fun postConnectProbeUrls(whitelistOnly: Boolean): List<String> =
        if (whitelistOnly) {
            wlCoreHealthUrls().take(3)
        } else {
            healthCheckUrls(false)
        }

    fun postConnectTimeoutMs(whitelistOnly: Boolean, baseTimeoutMs: Int): Int =
        if (whitelistOnly) {
            (baseTimeoutMs * 2).coerceIn(4_000, 10_000)
        } else {
            (baseTimeoutMs * 2).coerceIn(5_000, 20_000)
        }

    fun postConnectWarmupMs(whitelistOnly: Boolean): Long = if (whitelistOnly) 1_500L else 400L

    /**
     * sing-box urlTest on Android often logs `dial rmnet_* → proxy:port` while the tunnel is still
     * coming up. That is not proof the outbound is dead on whitelist networks.
     */
    fun isLikelyUnderlyingProxyDialFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val hasUplinkIface = error.contains("dial rmnet", ignoreCase = true) ||
            error.contains("dial wlan", ignoreCase = true) ||
            error.contains("dial eth", ignoreCase = true)
        if (!hasUplinkIface) return false
        // dial rmnet → proxy:port i/o timeout means the selected server is unreachable, not bootstrap noise.
        val e = error.lowercase()
        if (e.contains("i/o timeout") ||
            e.contains("connection timed out") ||
            e.contains("context deadline exceeded")
        ) {
            return false
        }
        return true
    }

    /** Passed to [fr.husi.database.AutoServerSelector.recordProbeFailure] when health failed inconclusively. */
    fun probeFailureSkipReason(error: String?): String? =
        if (isProbeFailureInconclusive(error, whitelistOnly = true)) "underlying_proxy_dial" else null

    /**
     * WL tunnel health during bootstrap / handoff: uplink dial and short transport timeouts
     * are not proof the selected outbound is dead.
     */
    fun isProbeFailureInconclusive(
        error: String?,
        whitelistOnly: Boolean,
        phase: String = "",
    ): Boolean {
        if (error.isNullOrBlank()) return false
        if (phase == "post_connect") return false
        if (!whitelistOnly) return false
        if (isLikelyUnderlyingProxyDialFailure(error)) return true
        if (phase != "session_periodic" && phase.isNotBlank()) return false
        val e = error.lowercase()
        return e.contains("no recent network activity")
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

    private fun normalizeTunnelHealthUrl(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith("http://cp.cloudflare.com", ignoreCase = true)) {
            return TUNNEL_HEALTH_CLOUDFLARE_HTTPS
        }
        return raw
    }

    private fun urlHost(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}
