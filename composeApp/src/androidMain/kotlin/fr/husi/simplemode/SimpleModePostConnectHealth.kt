package fr.husi.simplemode

import fr.husi.database.DirectProfileUrlProbe
import fr.husi.database.ProxyEntity
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.delay

internal object SimpleModePostConnectHealth {

    private const val WL_MAX_ATTEMPTS = 3
    private const val WL_RETRY_DELAY_MS = 2_000L

    data class Result(
        val ok: Boolean,
        val latencyMs: Int,
        val lastError: String?,
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
        val maxAttempts = if (whitelistOnly) WL_MAX_ATTEMPTS else 1
        var lastError: String? = null
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                delay(WL_RETRY_DELAY_MS * attempt)
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
                    return Result(ok = true, latencyMs = delayMs!!.toInt(), lastError = null)
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
                if (tunnel.latencyMs > 0) {
                    return Result(ok = true, latencyMs = tunnel.latencyMs, lastError = null)
                }
                lastError = tunnel.lastError ?: "post-connect tunnel url test failed"
            }
            if (!SimpleModeHealthRoute.isProbeFailureInconclusive(lastError, whitelistOnly, "post_connect")) {
                break
            }
        }
        return Result(ok = false, latencyMs = 0, lastError = lastError)
    }
}
