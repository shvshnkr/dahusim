package fr.husi.bg

/** When true, [fr.husi.group.SubscriptionHttpFetch] skips SOCKS via VPN for the current fetch. */
internal object SubscriptionUpdateFetchOverrides {
    var bypassVpn: Boolean = false
}
