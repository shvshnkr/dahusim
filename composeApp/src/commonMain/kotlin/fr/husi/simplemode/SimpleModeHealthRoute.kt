package fr.husi.simplemode

import fr.husi.bg.UnderlyingCarrierState
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

    fun dashboardConnectionTestUrl(): String =
        if (messengerProbeRequired(whitelistOnly = false)) {
            TUNNEL_HEALTH_TELEGRAM
        } else {
            normalizeTunnelHealthUrl(DataStore.connectionTestURL)
        }


    /** Manual dashboard / group urlTest: web first; DC required when composite messenger probe is on. */
    fun dashboardProbeUrls(whitelistOnly: Boolean = false): List<String> =
        if (SimpleModeMessengerProbe.compositeRequired(whitelistOnly)) {
            listOf(
                SimpleModeMessengerProbe.WEB_URL,
                SimpleModeMessengerProbe.DC_REQUIRED_URL,
            )
        } else if (messengerProbeRequired(whitelistOnly)) {
            listOf(TUNNEL_HEALTH_TELEGRAM)
        } else {
            listOf(dashboardConnectionTestUrl())
        }

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
        if (ctx.phase == SimpleModeTunnelSoftRecoveryPolicy.SOFT_REPROBE_PHASE) return false
        if (!ctx.whitelistOnly && ctx.phase != "prepare" && ctx.phase != "post_connect") return false
        if (!ctx.whitelistOnly && ctx.phase == "post_connect" && ctx.primaryProbeFailed) {
            return true
        }
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

    private const val OPEN_POST_CONNECT_WARMUP_MS = 400L
    private const val OPEN_POST_CONNECT_RECENT_FULL_PROBE_WARMUP_MS = 1_000L
    private const val RECENT_FULL_PROBE_FOR_POST_CONNECT_MS = 3L * 60 * 1000

    fun postConnectWarmupMs(
        whitelistOnly: Boolean,
        recentFullProbe: Boolean = isRecentFullProbeForPostConnect(),
    ): Long = when {
        whitelistOnly -> 2_500L
        recentFullProbe -> OPEN_POST_CONNECT_RECENT_FULL_PROBE_WARMUP_MS
        else -> OPEN_POST_CONNECT_WARMUP_MS
    }

    fun postConnectMaxAttempts(whitelistOnly: Boolean, lastError: String? = null): Int =
        when {
            isPostConnectHardFail(lastError) -> 1
            whitelistOnly -> 3
            else -> 2
        }

    fun isPostConnectHardFail(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        return e.contains("connection refused") ||
            e.contains("x509") ||
            e.contains("not a valid tls")
    }

    fun isRecentFullProbeForPostConnect(): Boolean {
        val lastProbeAt = DataStore.autoSelectLastFullProbeAt
        return lastProbeAt > 0L &&
            System.currentTimeMillis() - lastProbeAt < RECENT_FULL_PROBE_FOR_POST_CONNECT_MS
    }

    internal fun hasWlUplinkDialIface(error: String): Boolean =
        error.contains("dial rmnet", ignoreCase = true) ||
            error.contains("dial wlan", ignoreCase = true) ||
            error.contains("dial eth", ignoreCase = true) ||
            error.contains("dial ccmni", ignoreCase = true)

    private fun isNetworkHandoffProbeFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        return error.contains("network changed", ignoreCase = true)
    }

    fun isCarrierOutageProbeFailure(error: String?): Boolean {
        if (!UnderlyingCarrierState.awaitingRestore) return false
        if (error.isNullOrBlank()) return true
        val e = error.lowercase()
        return e.contains("no available network interface") ||
            e.contains("resource temporarily unavailable") ||
            isNetworkHandoffProbeFailure(error)
    }

    fun isWlTunnelBootstrapFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        if (!hasWlUplinkDialIface(error)) return false
        return isTunnelBootstrapTransportFailure(error)
    }

    /** OPEN post-connect: TUN cold start without requiring uplink dial iface in the error text. */
    internal fun isOpenPostConnectTunnelBootstrapFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        return isTunnelBootstrapTransportFailure(error)
    }

    fun isSoftRecoveryEligible(
        error: String?,
        whitelistOnly: Boolean,
        probeUrl: String? = null,
    ): Boolean {
        if (error.isNullOrBlank()) return false
        if (isProxyAuthenticationFailure(error)) return false
        if (error.lowercase().contains("connection refused")) return false
        if (isWlHttpProbeInconclusive(error)) return false
        if (isHttpRateLimitOrTransientResponse(error)) return false
        if (error == SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR) return true
        if (error.contains("probe_watchdog_timeout", ignoreCase = true)) return true
        if (isNetworkHandoffProbeFailure(error)) return true
        if (isTunnelBootstrapTransportFailure(error)) return true
        if (whitelistOnly) {
            if (isWlTunnelBootstrapFailure(error)) return true
            if (isLikelyUnderlyingProxyDialFailure(error)) return true
        }
        if (isMessengerDnsOrDialFailure(error, probeUrl)) return false
        return false
    }

    internal fun isTunnelBootstrapTransportFailure(error: String): Boolean {
        val e = error.lowercase()
        return e.contains("i/o timeout") ||
            e.contains("connection timed out") ||
            e.contains("context deadline exceeded") ||
            e.contains("no recent network activity") ||
            e.contains("operation was canceled")
    }

    fun isLikelyUnderlyingProxyDialFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        if (!hasWlUplinkDialIface(error)) return false
        return !isWlTunnelBootstrapFailure(error)
    }

    fun probeFailureSkipReason(error: String?, whitelistOnly: Boolean): String? =
        when {
            error == SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR ->
                if (whitelistOnly) "wl_tunnel_bootstrap" else "session_health_probe_stall"
            isProbeFailureInconclusive(error, whitelistOnly = whitelistOnly, phase = "post_connect") ->
                if (whitelistOnly) "wl_tunnel_bootstrap" else null
            isProbeFailureInconclusive(error, whitelistOnly = whitelistOnly) ->
                if (whitelistOnly) "underlying_proxy_dial" else null
            else -> null
        }

    fun recoverProbePhase(context: SessionRecoverContext): String = when (context) {
        SessionRecoverContext.PostConnectBootstrap,
        SessionRecoverContext.PostConnectExhausted,
        -> "post_connect"
        SessionRecoverContext.SessionHealth,
        SessionRecoverContext.StallWatchdog,
        -> "session_periodic"
    }

    fun allowsInconclusiveSoftRecover(
        context: SessionRecoverContext,
        error: String?,
        whitelistOnly: Boolean,
        probeUrl: String?,
    ): Boolean {
        if (context == SessionRecoverContext.SessionHealth ||
            context == SessionRecoverContext.StallWatchdog
        ) {
            if (isCarrierOutageProbeFailure(error)) return true
        }
        if (context != SessionRecoverContext.PostConnectBootstrap) return false
        return isProbeFailureInconclusive(
            error = error,
            whitelistOnly = whitelistOnly,
            phase = recoverProbePhase(context),
            probeUrl = probeUrl,
        )
    }

    private fun isProxyAuthenticationFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        return e.contains("authentication failed") ||
            e.contains("auth failed")
    }

    internal fun isHttpRateLimitOrTransientResponse(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        if (isProxyAuthenticationFailure(e)) return false
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
        if (error == SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR) {
            return phase == "post_connect" || phase.isBlank()
        }
        if (isNetworkHandoffProbeFailure(error)) {
            return phase == "post_connect" || phase == "session_periodic" || phase.isBlank()
        }
        if (isMessengerDnsOrDialFailure(error, probeUrl)) return false
        if (whitelistOnly && isWlTunnelBootstrapFailure(error)) {
            return phase == "session_periodic" ||
                phase == "post_connect" ||
                phase.isBlank()
        }
        if (!whitelistOnly &&
            phase == "post_connect" &&
            probeUrl == TUNNEL_HEALTH_TELEGRAM &&
            isOpenPostConnectTunnelBootstrapFailure(error)
        ) {
            return true
        }
        // Messenger probe is a "must succeed" signal when the tunnel path is stable. Uplink
        // bootstrap / handoff errors are handled above.
        if (SimpleModeMessengerProbe.isMessengerProbeUrl(probeUrl.orEmpty())) return false
        if (isHttpRateLimitOrTransientResponse(error)) {
            return phase == "post_connect" || phase == "session_periodic" || phase.isBlank()
        }
        if (!whitelistOnly) return false
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

    private fun isMessengerDnsOrDialFailure(error: String?, probeUrl: String?): Boolean {
        if (!SimpleModeMessengerProbe.isMessengerProbeUrl(probeUrl.orEmpty()) || error.isNullOrBlank()) {
            return false
        }
        val e = error.lowercase()
        return e.contains("lookup ") ||
            e.contains("connection refused") ||
            e.contains("software caused connection abort") ||
            e.contains("dial tun") ||
            e.contains("dial rmnet") ||
            e.contains("dial wlan")
    }

    sealed class TunnelHealthOutcome {
        abstract val latencyMs: Int

        data class RealSuccess(override val latencyMs: Int) : TunnelHealthOutcome()

        data class InconclusiveSynthetic(
            override val latencyMs: Int,
            val lastError: String? = null,
        ) : TunnelHealthOutcome()

        data class HardFail(val lastError: String? = null) : TunnelHealthOutcome() {
            override val latencyMs: Int = 0
        }

        val isProbeOk: Boolean get() = latencyMs > 0

        val recordUrlVerified: Boolean get() = this is RealSuccess
    }

    fun classifyTunnelProbe(
        latencyMs: Int,
        wasSyntheticSuccess: Boolean,
        lastError: String? = null,
    ): TunnelHealthOutcome = when {
        latencyMs > 0 && !wasSyntheticSuccess -> TunnelHealthOutcome.RealSuccess(latencyMs)
        latencyMs > 0 -> TunnelHealthOutcome.InconclusiveSynthetic(latencyMs, lastError)
        else -> TunnelHealthOutcome.HardFail(lastError)
    }

    fun postConnectRecordUrlVerified(tunnelLatencyMs: Int, wasSyntheticSuccess: Boolean): Boolean =
        classifyTunnelProbe(tunnelLatencyMs, wasSyntheticSuccess).recordUrlVerified

    fun wlUrlProbeTreatAsOk(error: String?, whitelistOnly: Boolean, probeUrl: String? = null): Int? {
        if (error.isNullOrBlank()) return null
        if (SimpleModeMessengerProbe.isMessengerProbeUrl(probeUrl.orEmpty())) return null
        if (isProxyAuthenticationFailure(error)) return null
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
