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

    /** First successful URL latency; 1 on inconclusive WL pass; 0 if failed. */
    suspend fun firstSuccessLatencyMs(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
    ): Int {
        if (outboundTag.isBlank()) return 0
        val client = Libcore.newClient(null)
        return try {
            var sawRealFailure = false
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
                    return latency
                }
                val errText = attempt.exceptionOrNull()?.message
                if (!SimpleModeHealthRoute.isLikelyUnderlyingProxyDialFailure(errText)) {
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
            if (whitelistOnly && !sawRealFailure) {
                SimpleModeHealthRoute.logInconclusivePass(
                    phase = phase,
                    whitelistOnly = true,
                    reason = "underlying_proxy_dial_only",
                )
                1
            } else {
                0
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
