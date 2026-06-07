package fr.husi.simplemode

import fr.husi.database.DirectProfileUrlProbe
import fr.husi.database.ProxyEntity
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.delay

internal object SimpleModePostConnectHealth {

    private const val WL_RETRY_DELAY_MS = 2_000L
    private const val OPEN_RETRY_DELAY_MS = 1_500L

    data class Result(
        val ok: Boolean,
        val latencyMs: Int,
        val lastError: String?,
        val recordUrlVerified: Boolean = false,
    )

    suspend fun verify(
        profile: ProxyEntity,
        whitelistOnly: Boolean,
        healthRoute: SimpleModeHealthRoute.Route,
        outboundTag: String,
        urls: List<String>,
        postConnectTimeoutMs: Int,
        warmupMs: Long,
    ): Result {
        delay(warmupMs)
        val useDirect = healthRoute == SimpleModeHealthRoute.Route.DIRECT_PROFILE
        var lastError: String? = null
        var maxAttempts = SimpleModeHealthRoute.postConnectMaxAttempts(whitelistOnly)
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                val retryDelayMs = if (whitelistOnly) {
                    WL_RETRY_DELAY_MS * attempt
                } else {
                    OPEN_RETRY_DELAY_MS
                }
                delay(retryDelayMs)
                simpleModeLog(
                    "SimpleMode",
                    "H3 post_connect_retry attempt=$attempt/$maxAttempts profileId=${profile.id}",
                )
            }
            if (useDirect) {
                val delayMs = DirectProfileUrlProbe.urlTestDelay(profile)?.toLong()
                val ok = delayMs != null && delayMs > 0L
                SimpleModeHealthRoute.logProbeAttempt(
                    phase = "post_connect",
                    whitelistOnly = whitelistOnly,
                    route = healthRoute,
                    outboundTag = outboundTag,
                    url = urls.firstOrNull().orEmpty(),
                    ok = ok,
                    delayMs = (delayMs ?: 0L).toInt(),
                    error = if (ok) null else "direct url test failed",
                )
                if (ok) {
                    return Result(
                        ok = true,
                        latencyMs = delayMs!!.toInt(),
                        lastError = null,
                        recordUrlVerified = true,
                    )
                }
                lastError = "direct url test failed"
            } else {
                val tunnel = SimpleModeTunnelHealthCheck.probeTunnel(
                    phase = "post_connect",
                    whitelistOnly = whitelistOnly,
                    outboundTag = outboundTag,
                    urls = urls,
                    timeoutMs = postConnectTimeoutMs,
                )
                val outcome = SimpleModeHealthRoute.classifyTunnelProbe(
                    latencyMs = tunnel.latencyMs,
                    wasSyntheticSuccess = tunnel.wasSyntheticSuccess,
                    lastError = tunnel.lastError,
                )
                if (outcome.isProbeOk) {
                    return Result(
                        ok = true,
                        latencyMs = outcome.latencyMs,
                        lastError = null,
                        recordUrlVerified = outcome.recordUrlVerified,
                    )
                }
                lastError = tunnel.lastError ?: "post-connect tunnel url test failed"
            }
            if (SimpleModeHealthRoute.isPostConnectHardFail(lastError)) {
                break
            }
            maxAttempts = SimpleModeHealthRoute.postConnectMaxAttempts(whitelistOnly, lastError)
            if (!SimpleModeHealthRoute.isProbeFailureInconclusive(lastError, whitelistOnly, "post_connect")) {
                break
            }
        }
        return Result(ok = false, latencyMs = 0, lastError = lastError)
    }
}
