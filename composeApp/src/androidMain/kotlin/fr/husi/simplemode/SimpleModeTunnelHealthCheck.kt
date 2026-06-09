package fr.husi.simplemode

import fr.husi.libcore.Libcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object SimpleModeTunnelHealthCheck {

    /** Kotlin-side cap when native urlTest ignores timeoutMs (Doze / stuck sing-box). */
    private fun probeWatchdogBudgetMs(timeoutMs: Int): Long =
        (timeoutMs + 3_000).toLong()

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
        val dcSecondaryOk: Boolean = false,
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
        if (SimpleModeMessengerProbe.compositeRequired(whitelistOnly) &&
            primaryUrls.singleOrNull() == SimpleModeMessengerProbe.WEB_URL
        ) {
            return probeMessengerWave(
                phase = phase,
                whitelistOnly = whitelistOnly,
                outboundTag = outboundTag,
                timeoutMs = timeoutMs,
            )
        }
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

    private suspend fun probeMessengerWave(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        timeoutMs: Int,
    ): TunnelProbeOutcome {
        if (outboundTag.isBlank()) return TunnelProbeOutcome(0, "missing outbound tag")
        val web = probeSingleUrl(
            phase = phase,
            whitelistOnly = whitelistOnly,
            outboundTag = outboundTag,
            url = SimpleModeMessengerProbe.WEB_URL,
            timeoutMs = timeoutMs,
            tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        if (web.latencyMs <= 0) {
            return TunnelProbeOutcome(
                latencyMs = 0,
                lastError = web.lastError,
                lastProbeUrl = SimpleModeMessengerProbe.WEB_URL,
                hadConclusiveFailure = web.hadConclusiveFailure,
            )
        }
        val dcRequired = probeSingleUrl(
            phase = phase,
            whitelistOnly = whitelistOnly,
            outboundTag = outboundTag,
            url = SimpleModeMessengerProbe.DC_REQUIRED_URL,
            timeoutMs = timeoutMs,
            tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        val dcSecondary = if (dcRequired.latencyMs > 0) {
            probeSingleUrl(
                phase = phase,
                whitelistOnly = whitelistOnly,
                outboundTag = outboundTag,
                url = SimpleModeMessengerProbe.DC_SECONDARY_URL,
                timeoutMs = timeoutMs,
                tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
            )
        } else {
            TunnelProbeOutcome(0, dcRequired.lastError, SimpleModeMessengerProbe.DC_REQUIRED_URL, true)
        }
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = web.latencyMs,
            webError = web.lastError,
            dcRequiredLatencyMs = dcRequired.latencyMs,
            dcRequiredError = dcRequired.lastError,
            dcSecondaryLatencyMs = dcSecondary.latencyMs,
        )
        SimpleModeMessengerProbe.logTunnelWave(phase, whitelistOnly, outboundTag, evaluation)
        return if (evaluation.ok) {
            TunnelProbeOutcome(
                latencyMs = evaluation.latencyMs,
                lastError = null,
                lastProbeUrl = evaluation.lastProbeUrl,
                hadConclusiveFailure = false,
                dcSecondaryOk = evaluation.dcSecondaryOk,
            )
        } else {
            TunnelProbeOutcome(
                latencyMs = 0,
                lastError = evaluation.lastError,
                lastProbeUrl = evaluation.lastProbeUrl,
                hadConclusiveFailure = true,
            )
        }
    }

    private data class SingleUrlProbe(
        val latencyMs: Int,
        val lastError: String?,
        val hadConclusiveFailure: Boolean,
        val wasSyntheticSuccess: Boolean,
    )

    private suspend fun probeSingleUrl(
        phase: String,
        whitelistOnly: Boolean,
        outboundTag: String,
        url: String,
        timeoutMs: Int,
        tier: SimpleModeHealthRoute.ProbeTier,
    ): SingleUrlProbe {
        val wave = probeUrlWave(phase, whitelistOnly, outboundTag, listOf(url), timeoutMs, tier)
        return SingleUrlProbe(
            latencyMs = wave.latencyMs,
            lastError = wave.lastError,
            hadConclusiveFailure = wave.hadConclusiveFailure,
            wasSyntheticSuccess = wave.wasSyntheticSuccess,
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
                val budgetMs = probeWatchdogBudgetMs(timeoutMs)
                val attempt = runCatching {
                    withTimeoutOrNull(budgetMs) {
                        withContext(Dispatchers.IO) {
                            client.urlTest(outboundTag, url, timeoutMs)
                        }
                    }
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
                val errText = when {
                    attempt.isFailure -> attempt.exceptionOrNull()?.message
                    latency == null -> "probe_watchdog_timeout"
                    else -> null
                }
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
