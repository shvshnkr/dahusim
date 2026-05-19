package fr.husi.routing

import fr.husi.database.RuleEntity
import fr.husi.fmt.SingBoxOptions
import fr.husi.fmt.SingBoxOptions.ACTION_HIJACK_DNS
import fr.husi.fmt.SingBoxOptions.ACTION_REJECT
import fr.husi.fmt.SingBoxOptions.ACTION_ROUTE
import fr.husi.fmt.SingBoxOptions.ACTION_SNIFF

private val AI_RULESET_TAGS = listOf(
    "geosite-openai",
    "geosite-anthropic",
    "geosite-google-gemini",
    "geosite-xai",
)

/**
 * Built-in preset rules should remain structurally safe:
 * users may toggle them, but editing/removal/reorder is blocked in UI.
 */
fun RuleEntity.isProtectedBuiltinRule(): Boolean {
    if (dnsOnly) return false
    val d = domains.lowercase()
    val ipLower = ip.lowercase()
    val protocolLower = protocol.map { it.lowercase() }.toSet()
    val networkLower = network.map { it.lowercase() }.toSet()
    return when (action) {
        ACTION_SNIFF -> true
        ACTION_HIJACK_DNS -> protocolLower.contains("dns")
        ACTION_REJECT -> {
            (protocolLower.contains("quic") && networkLower.contains(SingBoxOptions.NetworkUDP.lowercase())) ||
                d.contains("geosite-category-ads-all")
        }

        ACTION_ROUTE -> when (outbound) {
            RuleEntity.OUTBOUND_DIRECT -> {
                // private/LAN direct
                ipLower.split(',', '\n', ';', ' ')
                    .any { it.trim() == "private" } ||
                    // ru/cn/geosite/geoip direct presets
                    d.contains("geosite-category-ru") ||
                    d.contains("geosite-ru") ||
                    d.contains("geosite-cn") ||
                    ipLower.contains("geoip-ru") ||
                    ipLower.contains("geoip-cn")
            }

            RuleEntity.OUTBOUND_PROXY -> {
                // cn play store helper
                d.contains("geosite-google-play") ||
                    // ru-blocked split mode
                    d.contains("geosite-ru-blocked") ||
                    ipLower.contains("geoip-ru-blocked") ||
                    // ai split mode
                    AI_RULESET_TAGS.any { tag -> d.contains(tag) }
            }

            else -> false
        }

        else -> false
    }
}
