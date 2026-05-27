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

    const val TUNNEL_HEALTH_INSTAGRAM = "https://www.instagram.com"

    const val TUNNEL_HEALTH_FACEBOOK = "https://www.facebook.com"

    /** WL URL probe reached HTTP (e.g. 405) — tunnel/profile path is up. */
    const val WL_URL_PROBE_SYNTHETIC_MS = 200

    /** Uplink-only on WL (reachable without VPN). Never use for tunnel urlTest. */
    const val WL_YA_HTTPS = "https://ya.ru/"

    const val WL_DZEN_HTTP = "http://dzen.ru/"

    const val PREPARE_TIE_BREAK_MS_WL = 80
    const val PREPARE_TIE_BREAK_MS_OPEN = 50

    enum class ProbeTier {
        PRIMARY,
        CONFIRM,
    }

    data class ProbeEscalationContext(
        val phase: String = "",
        val urlOk: Int = 0,
        val tcpAlive: Int = 0,
        val topDelays: List<Pair<Long, Int>> = emptyList(),
        val whitelistOnly: Boolean = false,
        val lastProbeError: String? = null,
        val primaryProbeFailed: Boolean = false,
    )

    fun primaryBsProbeUrls(): List<String> = listOf(TUNNEL_HEALTH_TELEGRAM)

    fun confirmBsProbeUrls(): List<String> = listOf(
        TUNNEL_HEALTH_TELEGRAM,
        TUNNEL_HEALTH_INSTAGRAM,
        TUNNEL_HEALTH_FACEBOOK,
    )

    fun tunnelBsProbeUrls(): List<String> = primaryBsProbeUrls()

    fun messengerProbeRequired(whitelistOnly: Boolean): Boolean =
        whitelistOnly || DataStore.simpleModeTelegramProbe

    fun probeUrlPlan(
        phase: String,
        whitelistOnly: Boolean,
        tier: ProbeTier = ProbeTier.PRIMARY,
    ): List<String> = if (whitelistOnly) {
        when (tier) {
            ProbeTier.PRIMARY -> primaryBsProbeUrls()
            ProbeTier.CONFIRM -> confirmBsProbeUrls()
        }
    } else {
        when (tier) {
            ProbeTier.PRIMARY -> openPrimaryProbeUrls()
            ProbeTier.CONFIRM -> openConfirmProbeUrls()
        }
    }

    fun healthCheckUrls(whitelistOnly: Boolean): List<String> =
        probeUrlPlan(phase = "session", whitelistOnly = whitelistOnly, tier = ProbeTier.PRIMARY)

    fun postConnectProbeUrls(whitelistOnly: Boolean): List<String> =
        probeUrlPlan(phase = "post_connect", whitelistOnly = whitelistOnly, tier = ProbeTier.PRIMARY)

    fun prepareProbeUrls(whitelistOnly: Boolean): List<String> =
        probeUrlPlan(phase = "prepare", whitelistOnly = whitelistOnly, tier = ProbeTier.PRIMARY)

    fun shouldEscalateToConfirm(ctx: ProbeEscalationContext): Boolean {
        if (!ctx.whitelistOnly && ctx.phase != "prepare") return false
        if (ctx.phase == "prepare") {
            if (ctx.urlOk == 0 && ctx.tcpAlive > 0) return true
            if (ctx.topDelays.size >= 2) {
                val threshold = if (ctx.whitelistOnly) {
                    PREPARE_TIE_BREAK_MS_WL
                } else {
                    PREPARE_TIE_BREAK_MS_OPEN
                }
                val sorted = ctx.topDelays.sortedBy { it.second }
                val gap = sorted[1].second - sorted[0].second
                if (gap <= threshold) return true
            }
            return false
        }
        if (ctx.phase == "lkg_fast_path") return true
        if (ctx.primaryProbeFailed &&
            isProbeFailureInconclusive(ctx.lastProbeError, ctx.whitelistOnly, ctx.phase)
        ) {
            return true
        }
        return false
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

    internal fun isHttpRateLimitOrTransientResponse(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        if (e.contains("rate limit") || e.contains("too many requests")) return true
        return e.contains("429") || e.contains("502") || e.contains("503") || e.contains("504")
    }

    fun isProbeFailureInconclusive(
        error: String?,
        whitelistOnly: Boolean,
        phase: String = "",
        probeUrl: String? = null,
    ): Boolean {
        if (error.isNullOrBlank()) return false
        // Messenger probe is a "must succeed" signal in simple-mode. When it times out, we
        // must treat it as a real degradation and allow server re-selection.
        // Otherwise the client can appear "connected" while Telegram traffic never comes.
        if (probeUrl == TUNNEL_HEALTH_TELEGRAM) return false
        if (isHttpRateLimitOrTransientResponse(error)) {
            return phase == "post_connect" || phase == "session_periodic" || phase.isBlank()
        }
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

    internal fun isWlHttpProbeInconclusive(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        return e.contains("method not allowed") ||
            e.contains("405") ||
            e.contains("operation not permitted")
    }

    fun wlUrlProbeTreatAsOk(error: String?, whitelistOnly: Boolean): Int? {
        if (error.isNullOrBlank()) return null
        if (isHttpRateLimitOrTransientResponse(error)) return WL_URL_PROBE_SYNTHETIC_MS
        if (!whitelistOnly) return null
        return if (isWlHttpProbeInconclusive(error)) WL_URL_PROBE_SYNTHETIC_MS else null
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
        tier: ProbeTier = ProbeTier.PRIMARY,
    ) {
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly tier=${tier.name} " +
                "route=${route.name.lowercase()} " +
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
        tier: ProbeTier = ProbeTier.PRIMARY,
    ) {
        val result = if (ok) "ok delayMs=$delayMs" else "fail error=${error.orEmpty()}"
        simpleModeLog(
            "SimpleMode",
            "H37 health_route phase=$phase wlOnly=$whitelistOnly tier=${tier.name} " +
                "route=${route.name.lowercase()} " +
                "outboundTag=${outboundTag.ifBlank { "-" }} url=${urlHost(url)} $result",
        )
    }

    private fun openPrimaryProbeUrls(): List<String> =
        if (messengerProbeRequired(whitelistOnly = false)) {
            listOf(TUNNEL_HEALTH_TELEGRAM)
        } else {
            listOf(TUNNEL_HEALTH_GSTATIC, normalizeTunnelHealthUrl(DataStore.connectionTestURL))
                .distinct()
                .filter { it.isNotBlank() }
        }

    private fun openConfirmProbeUrls(): List<String> =
        if (messengerProbeRequired(whitelistOnly = false)) {
            listOf(
                TUNNEL_HEALTH_TELEGRAM,
                TUNNEL_HEALTH_GSTATIC,
                normalizeTunnelHealthUrl(DataStore.connectionTestURL),
                TUNNEL_HEALTH_CLOUDFLARE_HTTPS,
            ).distinct().filter { it.isNotBlank() }
        } else {
            (openPrimaryProbeUrls() + TUNNEL_HEALTH_CLOUDFLARE_HTTPS).distinct()
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
