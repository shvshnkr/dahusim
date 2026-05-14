package fr.husi.bg.proto

import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.fmt.ConfigBuildResult
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import org.json.JSONObject

/**
 * Runtime snapshot of built sing-box route for debug (RU bypass / final / per-app).
 */
internal fun emitRouteBuildDebug(profile: ProxyEntity, result: ConfigBuildResult) {
    // #region agent log
    runCatching {
        val root = JSONObject(result.config)
        val route = root.optJSONObject("route")
        val finalOut = route?.optString("final", "") ?: ""
        val rules = route?.optJSONArray("rules")
        val ruleSummaries = StringBuilder()
        val maxRules = 28
        if (rules != null) {
            for (i in 0 until minOf(rules.length(), maxRules)) {
                val r = rules.optJSONObject(i) ?: continue
                val out = r.optString("outbound", "")
                val rs = r.optJSONArray("rule_set")
                val rsStr = if (rs != null && rs.length() > 0) {
                    buildString {
                        for (j in 0 until rs.length()) {
                            if (j > 0) append(',')
                            append(rs.optString(j))
                        }
                    }
                } else ""
                val dom = r.optString("domain_suffix", r.optString("domain", ""))
                ruleSummaries.append('#').append(i).append(" out=").append(out)
                    .append(" rs=").append(rsStr)
                if (dom.isNotEmpty()) ruleSummaries.append(" dom=").append(dom)
                ruleSummaries.append("; ")
            }
            if (rules.length() > maxRules) {
                ruleSummaries.append("…totalRules=").append(rules.length())
            }
        }

        val rsTop = route?.optJSONArray("rule_set")
        val rsTopSummary = StringBuilder()
        if (rsTop != null) {
            for (i in 0 until minOf(rsTop.length(), 12)) {
                val o = rsTop.optJSONObject(i) ?: continue
                rsTopSummary.append(o.optString("tag")).append(':')
                    .append(o.optString("type")).append(';')
            }
            if (rsTop.length() > 12) rsTopSummary.append("…n=").append(rsTop.length())
        }

        var includePkg = "none"
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val ib = inbounds.optJSONObject(i) ?: continue
                if (ib.optString("type") != "tun") continue
                val inc = ib.optJSONArray("include_package")
                includePkg = if (inc == null) "all" else inc.length().toString()
                break
            }
        }

        val dns = root.optJSONObject("dns")
        val dnsRules = dns?.optJSONArray("rules")
        val dnsHead = StringBuilder()
        if (dnsRules != null) {
            for (i in 0 until minOf(dnsRules.length(), 8)) {
                val dr = dnsRules.optJSONObject(i) ?: continue
                val dRs = dr.optJSONArray("rule_set")
                val dRsStr = if (dRs != null && dRs.length() > 0) {
                    (0 until dRs.length()).joinToString(",") { dRs.optString(it) }
                } else ""
                dnsHead.append('#').append(i).append(" srv=").append(dr.optString("server"))
                    .append(" rs=").append(dRsStr).append("; ")
            }
        }

        val hasRuRs = ruleSummaries.contains("geosite-category-ru") ||
            ruleSummaries.contains("geosite-ru")
        val ruDirectIdx = run {
            if (rules == null) return@run -1
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                val rs = r.optJSONArray("rule_set") ?: continue
                for (j in 0 until rs.length()) {
                    val tag = rs.optString(j)
                    if (tag == "geosite-category-ru" || tag == "geosite-ru") {
                        if (r.optString("outbound") == "direct") return@run i
                    }
                }
            }
            -1
        }

        val perAppCatchAllRuleIndex = run {
            if (rules == null) return@run -1
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                val pk = r.optJSONArray("package_name") ?: continue
                if (pk.length() == 0) continue
                if (r.optString("outbound") == result.mainTag) return@run i
            }
            -1
        }

        simpleModeDebugEvent(
            runId = "route-build",
            hypothesisId = "H1-H2",
            location = "RouteBuildDebug.kt:emit",
            message = "route_rules_head",
            data = mapOf(
                "mainTag" to result.mainTag,
                "routeFinal" to finalOut,
                "rulesPreview" to ruleSummaries.toString().take(3800),
                "hasRuRuleSetInPreview" to hasRuRs.toString(),
                "firstRuDirectRuleIndex" to ruDirectIdx.toString(),
                "perAppCatchAllRuleIndex" to perAppCatchAllRuleIndex.toString(),
                "ruleSetTop" to rsTopSummary.toString().take(1200),
            ),
        )
        simpleModeDebugEvent(
            runId = "route-build",
            hypothesisId = "H3-H5",
            location = "RouteBuildDebug.kt:emit",
            message = "tun_dns_per_app",
            data = mapOf(
                "proxyApps" to DataStore.proxyApps.toString(),
                "bypassMode" to DataStore.bypassMode.toString(),
                "tunIncludePackageCount" to includePkg,
                "profileId" to profile.id.toString(),
                "profileType" to profile.type.toString(),
                "dnsRulesHead" to dnsHead.toString().take(2000),
            ),
        )
        simpleModeLog(
            "RouteDbg",
            "final=$finalOut firstRuDirectIdx=$ruDirectIdx hasRuRs=$hasRuRs " +
                "pkgCatchIdx=$perAppCatchAllRuleIndex tunInc=$includePkg proxyApps=${DataStore.proxyApps} " +
                "rulesHead=${ruleSummaries.take(900)}",
        )
    }.onFailure { ex ->
        simpleModeDebugEvent(
            runId = "route-build",
            hypothesisId = "H0",
            location = "RouteBuildDebug.kt:emit",
            message = "parse_failed",
            data = mapOf("error" to (ex.message ?: ex.javaClass.simpleName)),
        )
        simpleModeLog("RouteDbg", "parse_failed err=${ex.message ?: ex.javaClass.simpleName}")
    }
    // #endregion
}
