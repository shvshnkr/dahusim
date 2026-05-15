package fr.husi.routing

import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.RuleEntity
import fr.husi.fmt.SingBoxOptions

/**
 * On whitelist-restricted networks, RU geosite/geoip "direct" bypass often hits a dead path;
 * when the tunnel exit is in Russia, route that traffic through the proxy instead.
 */
object WhitelistRuRouting {

    fun shouldRouteRuGeoViaProxy(profile: ProxyEntity): Boolean {
        if (!DataStore.activeWhitelistRestrictedNetwork) return false
        if (DataStore.vpnExitProbeProfileId != profile.id) return false
        return DataStore.vpnExitIsRussia == true
    }
}

fun RuleEntity.isRuGeoDirectBypassRule(): Boolean {
    if (!enabled || dnsOnly) return false
    if (action != SingBoxOptions.ACTION_ROUTE || outbound != RuleEntity.OUTBOUND_DIRECT) {
        return false
    }
    val d = domains.lowercase()
    val ip = ip.lowercase()
    return d.contains("geosite-category-ru") || d.contains("geosite-ru") ||
        ip.contains("geoip-ru")
}

fun List<RuleEntity>.hasRuGeoDirectBypass(): Boolean = any { it.isRuGeoDirectBypassRule() }
