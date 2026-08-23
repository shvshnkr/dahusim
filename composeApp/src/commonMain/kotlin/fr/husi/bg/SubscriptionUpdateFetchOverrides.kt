package fr.husi.bg

/** When true, [fr.husi.group.SubscriptionHttpFetch] skips SOCKS via VPN for the current fetch. */
internal object SubscriptionUpdateFetchOverrides {
    var bypassVpn: Boolean = false

    /** Per-fetch HTTP timeout (ms) for the current update; null → default (15s). */
    var fetchTimeoutMs: Int? = null
}
