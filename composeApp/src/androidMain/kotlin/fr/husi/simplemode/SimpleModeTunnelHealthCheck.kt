package fr.husi.simplemode

import fr.husi.libcore.Libcore

internal object SimpleModeTunnelHealthCheck {

    suspend fun check(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
    ): Boolean = firstSuccessLatencyMs(
        phase = phase,
        whitelistOnly = whitelistOnly,
        outboundTag = outboundTag,
        urls = urls,
        timeoutMs = timeoutMs,
    ) > 0

    data class TunnelProbeOutcome(
        val latencyMs: Int,
        val lastError: String?,
    )

    /** First successful URL latency; 1 on inconclusive WL pass; 0 if failed. */
    suspend fun firstSuccessLatencyMs(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
    ): Int = probeTunnel(phase, whitelistOnly, outboundTag, urls, timeoutMs).latencyMs

    suspend fun probeTunnel(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
    ): TunnelProbeOutcome {
        if (outboundTag.isBlank()) return TunnelProbeOutcome(0, "missing outbound tag")
        val client = Libcore.newClient(null)
        return try {
            var sawRealFailure = false
            var lastError: String? = null
            for (url in urls) {
                val attempt = runCatching {
                    client.urlTest(outboundTag, url, timeoutMs)
                }
                val latency = attempt.getOrNull()
                if (latency != null && latency > 0) {
                    SimpleModeHealthRoute.logProbeAttempt(
                        phase = phase,
                        whitelistOnly = whitelistOnly,
                        route = SimpleModeHealthRoute.Route.TUNNEL_OUTBOUND,
                        outboundTag = outboundTag,
                        url = url,
                        ok = true,
                        delayMs = latency,
                    )
                    return TunnelProbeOutcome(latency, null)
                }
                val errText = attempt.exceptionOrNull()?.message
                lastError = errText
                SimpleModeHealthRoute.wlUrlProbeTreatAsOk(errText, whitelistOnly)?.let { synthetic ->
                    SimpleModeHealthRoute.logInconclusivePass(
                        phase = phase,
                        whitelistOnly = true,
                        reason = "wl_http_reached",
                    )
                    return TunnelProbeOutcome(synthetic, errText)
                }
                if (!SimpleModeHealthRoute.isProbeFailureInconclusive(errText, whitelistOnly, phase)) {
                    sawRealFailure = true
                }
                SimpleModeHealthRoute.logProbeAttempt(
                    phase = phase,
                    whitelistOnly = whitelistOnly,
                    route = SimpleModeHealthRoute.Route.TUNNEL_OUTBOUND,
                    outboundTag = outboundTag,
                    url = url,
                    ok = false,
                    error = errText,
                )
            }
            val allowInconclusive = whitelistOnly && !sawRealFailure
            if (allowInconclusive) {
                SimpleModeHealthRoute.logInconclusivePass(
                    phase = phase,
                    whitelistOnly = true,
                    reason = "underlying_proxy_dial_only",
                )
                TunnelProbeOutcome(1, lastError)
            } else {
                TunnelProbeOutcome(0, lastError)
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
