package fr.husi.routing

import fr.husi.database.DataStore
import fr.husi.database.RuleEntity
import fr.husi.fmt.SingBoxOptions

/**
 * On whitelist-restricted networks, provider whitelists drop RU IP ranges that are not in the
 * list, so the geoip-ru "direct" bypass dead-ends for RU-hosted media (e.g. YouTube GGC nodes)
 * while domains (geosite-category-ru / geosite-ru) keep working direct. Geoip-rules therefore
 * go through the tunnel on WL; domain rules stay direct.
 *
 * The gate is the network only: the tunnel exit country (H27/VpnExitProbe) is informational
 * (log/UI), not a routing gate — the first connect no longer races the late exit probe.
 */
object WhitelistRuRouting {

    fun shouldRouteRuGeoViaProxy(): Boolean = DataStore.activeWhitelistRestrictedNetwork
}

fun RuleEntity.isRuGeoDomainDirectBypassRule(): Boolean {
    if (!enabled || dnsOnly) return false
    if (action != SingBoxOptions.ACTION_ROUTE || outbound != RuleEntity.OUTBOUND_DIRECT) {
        return false
    }
    val d = domains.lowercase()
    return d.contains("geosite-category-ru") || d.contains("geosite-ru")
}

fun RuleEntity.isRuGeoIpDirectBypassRule(): Boolean {
    if (!enabled || dnsOnly) return false
    if (action != SingBoxOptions.ACTION_ROUTE || outbound != RuleEntity.OUTBOUND_DIRECT) {
        return false
    }
    val ip = ip.lowercase()
    return ip.contains("geoip-ru")
}

fun List<RuleEntity>.hasRuGeoDirectBypass(): Boolean =
    any { it.isRuGeoDomainDirectBypassRule() || it.isRuGeoIpDirectBypassRule() }
