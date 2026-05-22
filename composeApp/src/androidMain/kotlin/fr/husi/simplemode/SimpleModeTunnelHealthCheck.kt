package fr.husi.simplemode

import fr.husi.libcore.Libcore

internal object SimpleModeTunnelHealthCheck {

    suspend fun check(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
    ): Boolean {
        if (outboundTag.isBlank()) return false
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
                    return true
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
                true
            } else {
                false
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
