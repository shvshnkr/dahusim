package fr.husi.bg

/**
 * Full-UI network handoff decisions when simple-mode ownership is released but VPN stays up.
 * WL reachability reload vs stale-tunnel reload after long carrier restore.
 */
internal object WhitelistNetworkHandoffPolicy {

    const val STALE_HANDOFF_LOSS_MS = 5_000L

    fun isStaleHandoffTunnelReload(
        handoffReason: String?,
        elapsedFromLossMs: Long,
    ): Boolean =
        handoffReason == UnderlyingNetworkHandoffPolicy.REASON_CARRIER_RESTORE &&
            elapsedFromLossMs >= STALE_HANDOFF_LOSS_MS

    fun shouldRequestReloadOnReachabilityFlip(staleHandoff: Boolean): Boolean = !staleHandoff

    fun shouldSuppressExitRuRoutingReload(
        reason: String,
        nowMs: Long,
        suppressUntilMs: Long,
    ): Boolean = reason == "exit_country_ru_routing" && nowMs < suppressUntilMs
}
