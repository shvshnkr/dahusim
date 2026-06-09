package fr.husi.simplemode

import fr.husi.utils.simpleModeLog

/**
 * Composite messenger readiness: web.telegram.org (domain/L7) plus Telegram DC IP egress.
 * Catches domain-only SOCKS relays and partial VPS egress (e.g. Timeweb without WARP).
 */
internal object SimpleModeMessengerProbe {

    val WEB_URL: String get() = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM

    const val DC_REQUIRED_URL = "http://91.105.192.100/"

    const val DC_SECONDARY_URL = "http://149.154.167.51/"

    fun compositeRequired(whitelistOnly: Boolean): Boolean =
        SimpleModeHealthRoute.messengerProbeRequired(whitelistOnly)

    fun messengerReady(webOk: Boolean, dcRequiredOk: Boolean): Boolean = webOk && dcRequiredOk

    fun messengerProbeUrls(): List<String> = listOf(WEB_URL, DC_REQUIRED_URL, DC_SECONDARY_URL)

    fun isMessengerProbeUrl(url: String): Boolean =
        url == WEB_URL || url == DC_REQUIRED_URL || url == DC_SECONDARY_URL

    data class PrepareResult(
        val webDelayMs: Int,
        val dcRequiredDelayMs: Int?,
        val dcSecondaryDelayMs: Int?,
    ) {
        val ready: Boolean
            get() = messengerReady(
                webOk = webDelayMs > 0,
                dcRequiredOk = (dcRequiredDelayMs ?: 0) > 0,
            )

        val compositeDelayMs: Int
            get() = if (!ready) 0 else maxOf(webDelayMs, dcRequiredDelayMs ?: 0)

        val secondaryOk: Boolean get() = (dcSecondaryDelayMs ?: 0) > 0
    }

    data class TunnelWaveEvaluation(
        val ok: Boolean,
        val latencyMs: Int,
        val lastError: String?,
        val lastProbeUrl: String?,
        val dcSecondaryOk: Boolean = false,
    )

    fun evaluateTunnelWave(
        webLatencyMs: Int,
        webError: String?,
        dcRequiredLatencyMs: Int,
        dcRequiredError: String?,
        dcSecondaryLatencyMs: Int = 0,
    ): TunnelWaveEvaluation {
        if (webLatencyMs <= 0) {
            return TunnelWaveEvaluation(
                ok = false,
                latencyMs = 0,
                lastError = webError ?: "messenger_web_failed",
                lastProbeUrl = WEB_URL,
            )
        }
        if (dcRequiredLatencyMs <= 0) {
            return TunnelWaveEvaluation(
                ok = false,
                latencyMs = 0,
                lastError = dcRequiredError ?: "messenger_dc_required_failed",
                lastProbeUrl = DC_REQUIRED_URL,
            )
        }
        return TunnelWaveEvaluation(
            ok = true,
            latencyMs = maxOf(webLatencyMs, dcRequiredLatencyMs),
            lastError = null,
            lastProbeUrl = DC_REQUIRED_URL,
            dcSecondaryOk = dcSecondaryLatencyMs > 0,
        )
    }

    fun logPrepareProbe(
        profileId: Long,
        webOk: Boolean,
        dcRequiredOk: Boolean,
        dcSecondaryOk: Boolean?,
    ) {
        val secondary = dcSecondaryOk?.let { if (it) "ok" else "fail" } ?: "skip"
        simpleModeLog(
            "SimpleMode",
            "H37 prepare_messenger_probe profile=$profileId web=${if (webOk) "ok" else "fail"} " +
                "dc_91_105=${if (dcRequiredOk) "ok" else "fail"} dc_149_154=$secondary",
        )
    }

    fun logTunnelWave(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        evaluation: TunnelWaveEvaluation,
    ) {
        simpleModeLog(
            "SimpleMode",
            "H37 messenger_wave phase=$phase wlOnly=$whitelistOnly " +
                "outboundTag=${outboundTag.ifBlank { "-" }} ok=${evaluation.ok} " +
                "delayMs=${evaluation.latencyMs} dc_149_154=${if (evaluation.dcSecondaryOk) "ok" else "fail"}",
        )
    }
}
