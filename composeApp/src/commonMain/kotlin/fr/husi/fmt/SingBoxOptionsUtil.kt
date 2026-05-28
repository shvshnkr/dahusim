package fr.husi.fmt

import fr.husi.fmt.SingBoxOptions.DNSRule_Default
import fr.husi.fmt.SingBoxOptions.DomainResolveOptions
import fr.husi.fmt.SingBoxOptions.MyOptions
import fr.husi.fmt.SingBoxOptions.MyRouteOptions
import fr.husi.fmt.SingBoxOptions.NewDNSServerOptions
import fr.husi.fmt.SingBoxOptions.NewDNSServerOptions_LocalDNSServerOptions
import fr.husi.fmt.SingBoxOptions.NewDNSServerOptions_RemoteDNSServerOptions
import fr.husi.fmt.SingBoxOptions.NewDNSServerOptions_RemoteHTTPSDNSServerOptions
import fr.husi.fmt.SingBoxOptions.NewDNSServerOptions_RemoteTLSDNSServerOptions
import fr.husi.fmt.SingBoxOptions.OutboundTLSOptions
import fr.husi.fmt.SingBoxOptions.RuleSet
import fr.husi.fmt.SingBoxOptions.RuleSet_Local
import fr.husi.fmt.SingBoxOptions.RuleSet_Remote
import fr.husi.fmt.SingBoxOptions.Rule_Default
import fr.husi.ktx.JSONMap
import fr.husi.ktx.blankAsNull
import fr.husi.ktx.invariantPathString
import fr.husi.ktx.parseBoolean
import fr.husi.ktx.queryParameterNotBlank
import fr.husi.ktx.toJSONMap
import fr.husi.libcore.Libcore
import java.io.File

/** Fallback when merge/custom leaves tags but bases are missing (must match sing-geosite / sing-geoip layout). */
private const val DEFAULT_RULESET_GEOSITE_BASE =
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
private const val DEFAULT_RULESET_GEOIP_BASE =
    "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
private const val RUNETFREEDOM_RULESET_GEOSITE_BASE =
    "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geosite"
private const val RUNETFREEDOM_RULESET_GEOIP_BASE =
    "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geoip"

private fun ruleSetRemoteBaseForTag(tag: String, ipURL: String?, domainURL: String?): String {
    if (tag.startsWith("geosite-ru-blocked")) return RUNETFREEDOM_RULESET_GEOSITE_BASE
    if (tag.startsWith("geoip-ru-blocked")) return RUNETFREEDOM_RULESET_GEOIP_BASE
    val isIp = tag.startsWith("geoip-")
    val primary = (if (isIp) ipURL else domainURL)?.takeIf { it.isNotBlank() }
    if (primary != null) return primary
    return if (isIp) DEFAULT_RULESET_GEOIP_BASE else DEFAULT_RULESET_GEOSITE_BASE
}

fun DNSRule_Default.makeCommonRule(list: List<RuleItem>) {
    domain = mutableListOf()
    domain_suffix = mutableListOf()
    domain_regex = mutableListOf()
    domain_keyword = mutableListOf()
    rule_set = mutableListOf()

    for (rule in list) {
        when (rule.content) {
            RuleItem.CONTENT_ANY -> {
                ip_accept_any = true
                continue
            }

            RuleItem.CONTENT_PRIVATE -> {
                ip_is_private = true
                continue
            }
        }

        when (rule.type) {
            RuleItem.TYPE_FLAG_RULE_SET -> rule_set!!.add(rule.content)
            RuleItem.TYPE_FLAG_FULL -> domain!!.add(rule.content)
            RuleItem.TYPE_FLAG_DOMAIN_SUFFIX -> domain_suffix!!.add(rule.content)
            RuleItem.TYPE_FLAG_REGEX -> domain_regex!!.add(rule.content)
            else -> domain_keyword!!.add(rule.content)
        }
    }

    rule_set?.removeIf { it.isBlank() }
    domain?.removeIf { it.isBlank() }
    domain_suffix?.removeIf { it.isBlank() }
    domain_regex?.removeIf { it.isBlank() }
    domain_keyword?.removeIf { it.isBlank() }

    if (ip_is_private == false) ip_is_private = null
    if (ip_accept_any == false) ip_accept_any = null
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun DNSRule_Default.makeResponseRule(list: List<RuleItem>) {
    ip_cidr = mutableListOf()
    if (rule_set == null) rule_set = mutableListOf()

    for (rule in list) {
        when (rule.content) {
            RuleItem.CONTENT_ANY -> {
                ip_accept_any = true
                continue
            }

            RuleItem.CONTENT_PRIVATE -> {
                ip_is_private = true
                continue
            }
        }

        when (rule.type) {
            RuleItem.TYPE_FLAG_RULE_SET -> rule_set!!.add(rule.content)
            else -> ip_cidr!!.add(rule.content)
        }
    }

    rule_set?.removeIf { it.isBlank() }
    ip_cidr?.removeIf { it.isBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    if (ip_is_private == false) ip_is_private = null
    if (ip_accept_any == false) ip_accept_any = null
}

fun DNSRule_Default.checkEmpty(): Boolean {
    if (match_response == true) return false
    if (ip_cidr?.isNotEmpty() == true) return false
    if (ip_is_private == true) return false
    if (ip_accept_any == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (response_rcode != null) return false
    if (response_answer?.isNotEmpty() == true) return false
    if (response_ns?.isNotEmpty() == true) return false
    if (response_extra?.isNotEmpty() == true) return false
    if (process_name?.isNotEmpty() == true) return false
    if (process_path?.isNotEmpty() == true) return false
    if (process_path_regex?.isNotEmpty() == true) return false
    if (package_name?.isNotEmpty() == true) return false
    if (package_name_regex?.isNotEmpty() == true) return false
    if (user?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (wifi_ssid?.isNotEmpty() == true) return false
    if (wifi_bssid?.isNotEmpty() == true) return false
    return true
}

fun Rule_Default.makeCommonRule(list: List<RuleItem>, isIP: Boolean) {
    if (isIP) {
        ip_cidr = mutableListOf()
    } else {
        domain = mutableListOf()
        domain_suffix = mutableListOf()
        domain_regex = mutableListOf()
        domain_keyword = mutableListOf()
    }
    if (rule_set == null) rule_set = mutableListOf()

    for (rule in list) {
        if (isIP) {
            when (rule.content) {
                RuleItem.CONTENT_ANY -> continue // just for DNS
                RuleItem.CONTENT_PRIVATE -> {
                    ip_is_private = true
                    continue
                }
            }
            when (rule.type) {
                RuleItem.TYPE_FLAG_RULE_SET -> rule_set!!.add(rule.content)
                else -> ip_cidr!!.add(rule.content)
            }
        } else {
            when (rule.type) {
                RuleItem.TYPE_FLAG_RULE_SET -> rule_set!!.add(rule.content)
                RuleItem.TYPE_FLAG_FULL -> domain!!.add(rule.content)
                RuleItem.TYPE_FLAG_DOMAIN_SUFFIX -> domain_suffix!!.add(rule.content)
                RuleItem.TYPE_FLAG_REGEX -> domain_regex!!.add(rule.content)
                else -> domain_keyword!!.add(rule.content)
            }
        }
    }

    rule_set?.removeIf { it.isBlank() }
    ip_cidr?.removeIf { it.isBlank() }
    domain?.removeIf { it.isBlank() }
    domain_suffix?.removeIf { it.isBlank() }
    domain_regex?.removeIf { it.isBlank() }
    domain_keyword?.removeIf { it.isBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
    if (ip_is_private == false) ip_is_private = null
}

fun Rule_Default.checkEmpty(): Boolean {
    if (ip_cidr?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (ip_is_private == true) return false
    if (source_ip_is_private == true) return false

    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (process_name?.isNotEmpty() == true) return false
    if (process_path?.isNotEmpty() == true) return false
    if (process_path_regex?.isNotEmpty() == true) return false
    if (package_name?.isNotEmpty() == true) return false
    if (package_name_regex?.isNotEmpty() == true) return false
    if (user?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false

    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    if (wifi_ssid?.isNotEmpty() == true) return false
    if (wifi_bssid?.isNotEmpty() == true) return false
    if (clash_mode?.isNotEmpty() == true) return false
    if (network_type?.isNotEmpty() == true) return false
    if (network_is_expensive == true) return false

    if (override_address?.isNotEmpty() == true) return false
    if (override_port != null && override_port!! > 0) return false
    if (tls_fragment == true) return false
    if (tls_record_fragment == true) return false

    if (strategy != null) return false
    if (disable_cache == true) return false
    if (rewrite_ttl != null) return false
    if (client_subnet?.isNotEmpty() == true) return false

    if (timeout?.isNotEmpty() == true) return false
    if (sniffer?.isNotEmpty() == true) return false

    return true
}

fun parseRuleProcessRules(
    packages: Set<String>,
    defaultToPackage: Boolean,
): Pair<LinkedHashSet<String>, List<RuleItem>> {
    val packageNames = LinkedHashSet<String>()
    val processRules = ArrayList<RuleItem>(packages.size)
    for (rawItem in packages) {
        val item = rawItem.trim().blankAsNull() ?: continue

        val parsed = RuleItem.parseRule(item, defaultDNSBehavior = true)
        val content = parsed.content.trim()
        if (content.isBlank()) continue

        when (parsed.type) {
            RuleItem.TYPE_FLAG_NAME,
            RuleItem.TYPE_FLAG_PATH,
            RuleItem.TYPE_FLAG_REGEX,
            RuleItem.TYPE_USER, RuleItem.TYPE_USER_ID,
                -> processRules.add(RuleItem(type = parsed.type, content = content))

            else -> {
                if (defaultToPackage) {
                    packageNames.add(item)
                } else {
                    processRules.add(
                        RuleItem(
                            type = RuleItem.TYPE_FLAG_PATH,
                            content = content,
                        ),
                    )
                }
            }
        }
    }
    return packageNames to processRules
}

fun Rule_Default.makeProcessRule(list: List<RuleItem>) {
    process_name = mutableListOf()
    process_path = mutableListOf()
    process_path_regex = mutableListOf()
    user = mutableListOf()
    user_id = mutableListOf()

    for (rule in list) {
        when (rule.type) {
            RuleItem.TYPE_FLAG_NAME -> process_name!!.add(rule.content)
            RuleItem.TYPE_FLAG_REGEX -> process_path_regex!!.add(rule.content)
            RuleItem.TYPE_USER -> user!!.add(rule.content)
            RuleItem.TYPE_USER_ID -> user_id!!.add(rule.content.toInt())
            else -> process_path!!.add(rule.content)
        }
    }

    process_name?.removeIf { it.isBlank() }
    process_path?.removeIf { it.isBlank() }
    process_path_regex?.removeIf { it.isBlank() }
    user?.removeIf { it.isBlank() }
    user_id?.removeIf { it <= 0 }
    if (process_name?.isEmpty() == true) process_name = null
    if (process_path?.isEmpty() == true) process_path = null
    if (process_path_regex?.isEmpty() == true) process_path_regex = null
    if (user?.isEmpty() == true) user = null
    if (user_id?.isEmpty() == true) user_id = null
}

fun DNSRule_Default.makeProcessRule(list: List<RuleItem>) {
    process_name = mutableListOf()
    process_path = mutableListOf()
    process_path_regex = mutableListOf()
    user = mutableListOf()
    user_id = mutableListOf()

    for (rule in list) {
        when (rule.type) {
            RuleItem.TYPE_FLAG_NAME -> process_name!!.add(rule.content)
            RuleItem.TYPE_FLAG_REGEX -> process_path_regex!!.add(rule.content)
            RuleItem.TYPE_USER -> user!!.add(rule.content)
            RuleItem.TYPE_USER_ID -> user_id!!.add(rule.content.toInt())
            else -> process_path!!.add(rule.content)
        }
    }

    process_name?.removeIf { it.isBlank() }
    process_path?.removeIf { it.isBlank() }
    process_path_regex?.removeIf { it.isBlank() }
    user?.removeIf { it.isBlank() }
    user_id?.removeIf { it <= 0 }
    if (process_name?.isEmpty() == true) process_name = null
    if (process_path?.isEmpty() == true) process_path = null
    if (process_path_regex?.isEmpty() == true) process_path_regex = null
    if (user?.isEmpty() == true) user = null
    if (user_id?.isEmpty() == true) user_id = null
}

/**
 * Builds all rule-set.
 * This will crate route if route is null,
 * and will refreshes route.rule_set.
 * */
fun MyOptions.buildRuleSets(
    ipURL: String?,
    domainURL: String?,
    localPath: String?,
) {
    val names = hashSetOf<String>()
    if (dns != null) collectSet(names, dns!!.rules)
    if (route != null) collectSet(names, route!!.rules)

    if (names.isEmpty()) return

    if (route == null) route = MyRouteOptions()
    if (route!!.rule_set == null) route!!.rule_set = mutableListOf()
    for (set in route!!.rule_set!!) names.add(set.tag!!)
    val list = ArrayList<RuleSet>(names.size)

    val localDir = localPath?.let(::File)
    for (name in names.sorted()) {
        val localRuleSetFile = localDir?.resolve("$name.srs")
        if (localRuleSetFile?.isFile == true) {
            list.add(
                RuleSet_Local().apply {
                    tag = name
                    type = SingBoxOptions.RULE_SET_TYPE_LOCAL
                    format = SingBoxOptions.RULE_SET_FORMAT_BINARY
                    path = localRuleSetFile.invariantPathString()
                },
            )
        } else {
            val fileName = mapRuleSetFileName(name)
            val base = ruleSetRemoteBaseForTag(name, ipURL, domainURL)
            list.add(
                RuleSet_Remote().apply {
                    tag = name
                    type = SingBoxOptions.RULE_SET_TYPE_REMOTE
                    format = SingBoxOptions.RULE_SET_FORMAT_BINARY
                    url = "$base/$fileName.srs"
                },
            )
        }
    }

    route!!.rule_set = list
}

private fun mapRuleSetFileName(tag: String): String = when (tag) {
    // sing-geosite no longer publishes geosite-ru.srs; current file is geosite-category-ru.srs.
    "geosite-ru" -> "geosite-category-ru"
    else -> tag
}

/**
 * Subscription/clash `customConfigJson` is merged after [buildRuleSets]; [mergeJson] replaces
 * whole `route.rule_set` lists, so broken remote URLs (e.g. rule-set-unstable) win.
 * Rebuild `route.rule_set` from tags referenced in the merged dns/route rules.
 */
fun JSONMap.refreshRuleSetsAfterCustomMerge(
    forTest: Boolean,
    ipURL: String?,
    domainURL: String?,
    localPath: String?,
) {
    if (forTest) return
    if (localPath == null && ipURL.isNullOrBlank() && domainURL.isNullOrBlank()) return

    val names = hashSetOf<String>()
    (this["dns"] as? Map<*, *>)?.get("rules")?.let { collectSet(names, it as? List<*>) }
    val routeAny = this["route"]
    val routeObj = routeAny as? Map<*, *>
    routeObj?.get("rules")?.let { collectSet(names, it as? List<*>) }
    (routeObj?.get("rule_set") as? List<*>)?.forEach { entry ->
        val tag = (entry as? Map<*, *>)?.get("tag") as? String
        if (!tag.isNullOrBlank()) names.add(tag)
    }
    if (names.isEmpty()) return

    val routeMutable: MutableMap<String, Any?> = when (routeAny) {
        null -> mutableMapOf<String, Any?>().also { this["route"] = it }
        is MutableMap<*, *> -> @Suppress("UNCHECKED_CAST") routeAny as MutableMap<String, Any?>
        is Map<*, *> -> toJSONMap(routeAny).also { this["route"] = it }
        else -> return
    }
    if (routeMutable["rules"] == null) {
        routeMutable["rules"] = mutableListOf<Any?>()
    }

    val localDir = localPath?.let(::File)
    val list = ArrayList<Map<String, Any?>>(names.size)
    for (name in names.sorted()) {
        val localRuleSetFile = localDir?.resolve("$name.srs")
        if (localRuleSetFile?.isFile == true) {
            list.add(
                linkedMapOf(
                    "tag" to name,
                    "type" to SingBoxOptions.RULE_SET_TYPE_LOCAL,
                    "format" to SingBoxOptions.RULE_SET_FORMAT_BINARY,
                    "path" to localRuleSetFile.invariantPathString(),
                ),
            )
        } else {
            val fileName = mapRuleSetFileName(name)
            val base = ruleSetRemoteBaseForTag(name, ipURL, domainURL)
            list.add(
                linkedMapOf(
                    "tag" to name,
                    "type" to SingBoxOptions.RULE_SET_TYPE_REMOTE,
                    "format" to SingBoxOptions.RULE_SET_FORMAT_BINARY,
                    "url" to "$base/$fileName.srs",
                ),
            )
        }
    }
    routeMutable["rule_set"] = list
}

/**
 * Collects all rule-set in rules.
 * @param rules item should be DNSRule or Rule.
 */
@Suppress("UNCHECKED_CAST")
internal fun collectSet(set: MutableSet<String>, rules: List<*>?) {
    if (rules.isNullOrEmpty()) return

    for (rawRule in rules) {
        val rule = rawRule as? Map<*, *> ?: continue
        val nestedRules = rule["rules"] as? List<*>
        val type = (rule["type"] as? String)?.lowercase()
        if (type == "logical" || (type == null && !nestedRules.isNullOrEmpty())) {
            collectSet(set, nestedRules)
            continue
        }

        val ruleSet = rule["rule_set"] as? List<*>
        if (ruleSet.isNullOrEmpty()) continue

        for (name in ruleSet) {
            val tag = (name as? String)?.takeIf { it.isNotBlank() } ?: continue
            set.add(tag)
        }
    }
}

fun isEndpoint(type: String): Boolean = when (type) {
    SingBoxOptions.TYPE_WIREGUARD -> true
    else -> false
}

/**
 * Turn link to new DNS options.
 */
fun buildDNSServer(
    link: String,
    out: String?,
    tag: String,
    domainResolver: DomainResolveOptions,
): NewDNSServerOptions {
    if (link == "local") return NewDNSServerOptions_LocalDNSServerOptions().also {
        it.type = SingBoxOptions.DNS_TYPE_LOCAL
        it.tag = tag
    }

    val url = if (!link.contains("://")) {
        Libcore.newURL(SingBoxOptions.DNS_TYPE_UDP).apply {
            fullHost = link
        }
    } else {
        Libcore.parseURL(link)
    }

    return when (val scheme = url.scheme) {
        SingBoxOptions.DNS_TYPE_TLS -> NewDNSServerOptions_RemoteTLSDNSServerOptions().apply {
            type = scheme
            server = url.host
            server_port = url.ports.toIntOrNull()
            domain_resolver = domainResolver
            tls = OutboundTLSOptions().apply {
                enabled = true
            }
            detour = out
            if (url.parseBoolean("pipeline")) pipeline = true
            max_queries = url.queryParameterNotBlank("maxqueries")?.toIntOrNull()
        }

        SingBoxOptions.DNS_TYPE_QUIC -> NewDNSServerOptions_RemoteTLSDNSServerOptions().apply {
            type = scheme
            server = url.host
            server_port = url.ports.toIntOrNull()
            domain_resolver = domainResolver
            tls = OutboundTLSOptions().apply {
                enabled = true
            }
            detour = out
        }

        "http3", SingBoxOptions.DNS_TYPE_HTTPS, SingBoxOptions.DNS_TYPE_H3 -> NewDNSServerOptions_RemoteHTTPSDNSServerOptions().apply {
            type = if (scheme == "http3") {
                SingBoxOptions.DNS_TYPE_H3
            } else {
                scheme
            }
            server = url.host
            server_port = url.ports.toIntOrNull()
            domain_resolver = domainResolver
            tls = OutboundTLSOptions().apply {
                enabled = true
            }
            path = url.path
            detour = out
        }

        SingBoxOptions.DNS_TYPE_TCP -> SingBoxOptions.NewDNSServerOptions_RemoteTCPDNSServerOptions()
            .apply {
                type = SingBoxOptions.DNS_TYPE_TCP
                server = url.host
                server_port = url.ports.toIntOrNull()
                domain_resolver = domainResolver
                detour = out
                if (url.parseBoolean("reuse")) reuse = true
                if (url.parseBoolean("pipeline")) pipeline = true
                max_queries = url.queryParameterNotBlank("maxqueries")?.toIntOrNull()
            }

        // "", SingBoxOptions.DNS_TYPE_UDP ->
        else -> NewDNSServerOptions_RemoteDNSServerOptions().apply {
            type = scheme.ifBlank {
                SingBoxOptions.DNS_TYPE_UDP
            }
            server = url.host
            server_port = url.ports.toIntOrNull()
            domain_resolver = domainResolver
            detour = out
        }

    }.also {
        it.tag = tag
    }
}
