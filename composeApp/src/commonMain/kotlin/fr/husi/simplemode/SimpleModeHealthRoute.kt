package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.utils.simpleModeLog
import java.net.URL

internal object SimpleModeHealthRoute {

    const val WL_WHITELIST_TXT_URL =
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt"

    /** Open-network tunnel sanity (CS/open), not for WL BS verification. */
    const val TUNNEL_HEALTH_CLOUDFLARE_HTTPS = "https://cp.cloudflare.com/"

    const val TUNNEL_HEALTH_GSTATIC = "https://www.gstatic.com/generate_204"

    /** BS target: blocked on WL uplink L3; must work via VPN exit. */
    const val TUNNEL_HEALTH_TELEGRAM = "https://web.telegram.org"

    /** Uplink-only on WL (reachable without VPN). Never use for tunnel urlTest. */
    const val WL_YA_HTTPS = "https://ya.ru/"

    const val WL_DZEN_HTTP = "http://dzen.ru/"

    fun tunnelBsProbeUrls(): List<String> = listOf(TUNNEL_HEALTH_TELEGRAM)

    fun healthCheckUrls(whitelistOnly: Boolean): List<String> = if (whitelistOnly) {
        tunnelBsProbeUrls()
    } else {
        listOf(TUNNEL_HEALTH_GSTATIC, normalizeTunnelHealthUrl(DataStore.connectionTestURL))
            .distinct()
            .filter { it.isNotBlank() }
    }

    fun postConnectProbeUrls(whitelistOnly: Boolean): List<String> =
        if (whitelistOnly) {
            tunnelBsProbeUrls()
        } else {
            healthCheckUrls(false)
        }

    fun prepareProbeUrls(whitelistOnly: Boolean): List<String> =
        if (whitelistOnly) {
            tunnelBsProbeUrls()
        } else {
            healthCheckUrls(false)
        }

    /** WL: skip sing-box tunnel urlTest after connect (prepare TCP/BS direct probe already ran). */
    fun skipTunnelHealthCheck(
        whitelistOnly: Boolean,
        wlSkipTunnelHealthCheck: Boolean = DataStore.simpleModeWlSkipTunnelHealthCheck,
    ): Boolean = whitelistOnly && wlSkipTunnelHealthCheck

    fun postConnectTimeoutMs(whitelistOnly: Boolean, baseTimeoutMs: Int): Int =
        if (whitelistOnly) {
            (baseTimeoutMs * 2).coerceIn(4_000, 10_000)
        } else {
            (baseTimeoutMs * 2).coerceIn(5_000, 20_000)
        }

    fun postConnectWarmupMs(whitelistOnly: Boolean): Long = if (whitelistOnly) 2_500L else 400L

    fun postConnectMaxAttempts(whitelistOnly: Boolean): Int = if (whitelistOnly) 3 else 1

    fun isWlTunnelBootstrapFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val hasUplinkIface = error.contains("dial rmnet", ignoreCase = true) ||
            error.contains("dial wlan", ignoreCase = true) ||
            error.contains("dial eth", ignoreCase = true)
        if (!hasUplinkIface) return false
        val e = error.lowercase()
        return e.contains("i/o timeout") ||
            e.contains("connection timed out") ||
            e.contains("context deadline exceeded") ||
            e.contains("no recent network activity") ||
            e.contains("operation was canceled")
    }

    fun isLikelyUnderlyingProxyDialFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val hasUplinkIface = error.contains("dial rmnet", ignoreCase = true) ||
            error.contains("dial wlan", ignoreCase = true) ||
            error.contains("dial eth", ignoreCase = true)
        if (!hasUplinkIface) return false
        return !isWlTunnelBootstrapFailure(error)
    }

    fun probeFailureSkipReason(error: String?): String? =
        when {
            isProbeFailureInconclusive(error, whitelistOnly = true, phase = "post_connect") ->
                "wl_tunnel_bootstrap"
            isProbeFailureInconclusive(error, whitelistOnly = true) ->
                "underlying_proxy_dial"
            else -> null
        }

    fun isProbeFailureInconclusive(
        error: String?,
        whitelistOnly: Boolean,
        phase: String = "",
    ): Boolean {
        if (error.isNullOrBlank()) return false
        if (!whitelistOnly) return false
        if (isWlTunnelBootstrapFailure(error)) {
            return phase == "session_periodic" ||
                phase == "post_connect" ||
                phase.isBlank()
        }
        if (isLikelyUnderlyingProxyDialFailure(error)) return true
        if (isWlHttpProbeInconclusive(error)) {
            return phase == "post_connect" || phase == "session_periodic" || phase.isBlank()
        }
        if (phase != "session_periodic" && phase.isNotBlank()) return false
        val e = error.lowercase()
        return e.contains("no recent network activity")
    }

    private fun isWlHttpProbeInconclusive(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        return e.contains("method not allowed") ||
            e.contains("405") ||
            e.contains("operation not permitted")
    }

    fun logInconclusivePass(phase: String, whitelistOnly: Boolean, reason: String) {
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly inconclusive=$reason",
        )
    }

    fun logTunnelHealthSkipped(phase: String, whitelistOnly: Boolean) {
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly route=skipped " +
                "reason=wl_skip_tunnel_health",
        )
    }

    enum class Route {
        DIRECT_PROFILE,
        TUNNEL_OUTBOUND,
        SKIPPED,
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
