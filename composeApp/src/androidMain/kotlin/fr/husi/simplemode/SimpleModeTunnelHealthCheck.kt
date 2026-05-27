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
        val lastProbeUrl: String? = null,
        val hadConclusiveFailure: Boolean = false,
        val wasSyntheticSuccess: Boolean = false,
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
        val primary = SimpleModeHealthRoute.probeUrlPlan(
            phase,
            whitelistOnly,
            SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        val usePrimary = urls.isEmpty() || urls == primary
        val primaryUrls = if (usePrimary) primary else urls
        val outcome = probeUrlWave(
            phase = phase,
            whitelistOnly = whitelistOnly,
            outboundTag = outboundTag,
            urls = primaryUrls,
            timeoutMs = timeoutMs,
            tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        if (outcome.latencyMs > 0) return outcome
        if (outcome.hadConclusiveFailure) return outcome
        val escalate = SimpleModeHealthRoute.shouldEscalateToConfirm(
            SimpleModeHealthRoute.ProbeEscalationContext(
                phase = phase,
                whitelistOnly = whitelistOnly,
                lastProbeError = outcome.lastError,
                primaryProbeFailed = true,
            ),
        )
        if (!escalate) return outcome
        val confirmUrls = SimpleModeHealthRoute.probeUrlPlan(
            phase,
            whitelistOnly,
            SimpleModeHealthRoute.ProbeTier.CONFIRM,
        ).filter { it !in primaryUrls }
        if (confirmUrls.isEmpty()) return outcome
        return probeUrlWave(
            phase = phase,
            whitelistOnly = whitelistOnly,
            outboundTag = outboundTag,
            urls = confirmUrls,
            timeoutMs = timeoutMs,
            tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
        )
    }

    private suspend fun probeUrlWave(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        urls: List<String>,
        timeoutMs: Int,
        tier: SimpleModeHealthRoute.ProbeTier,
    ): TunnelProbeOutcome {
        if (outboundTag.isBlank()) return TunnelProbeOutcome(0, "missing outbound tag")
        val client = Libcore.newClient(null)
        return try {
            var sawRealFailure = false
            var lastError: String? = null
            var lastProbeUrl: String? = null
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
                        tier = tier,
                    )
                    return TunnelProbeOutcome(
                        latency,
                        null,
                        lastProbeUrl = url,
                        hadConclusiveFailure = false,
                        wasSyntheticSuccess = false,
                    )
                }
                val errText = attempt.exceptionOrNull()?.message
                lastError = errText
                lastProbeUrl = url
                SimpleModeHealthRoute.wlUrlProbeTreatAsOk(
                    error = errText,
                    whitelistOnly = whitelistOnly,
                    probeUrl = url,
                )?.let { synthetic ->
                    val reason = if (SimpleModeHealthRoute.isHttpRateLimitOrTransientResponse(errText)) {
                        "http_rate_limit"
                    } else {
                        "wl_http_reached"
                    }
                    SimpleModeHealthRoute.logInconclusivePass(
                        phase = phase,
                        whitelistOnly = whitelistOnly,
                        reason = reason,
                    )
                    return TunnelProbeOutcome(
                        synthetic,
                        errText,
                        lastProbeUrl = url,
                        hadConclusiveFailure = false,
                        wasSyntheticSuccess = true,
                    )
                }
                if (!SimpleModeHealthRoute.isProbeFailureInconclusive(
                        errText,
                        whitelistOnly,
                        phase,
                        probeUrl = url,
                    )
                ) {
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
                    tier = tier,
                )
            }
            val allowInconclusive = whitelistOnly && !sawRealFailure
            if (allowInconclusive) {
                SimpleModeHealthRoute.logInconclusivePass(
                    phase = phase,
                    whitelistOnly = true,
                    reason = "underlying_proxy_dial_only",
                )
                TunnelProbeOutcome(
                    1,
                    lastError,
                    lastProbeUrl = lastProbeUrl,
                    hadConclusiveFailure = false,
                    wasSyntheticSuccess = true,
                )
            } else {
                TunnelProbeOutcome(0, lastError, lastProbeUrl = lastProbeUrl, hadConclusiveFailure = sawRealFailure)
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
