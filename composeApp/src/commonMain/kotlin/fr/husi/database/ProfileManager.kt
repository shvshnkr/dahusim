package fr.husi.database

import androidx.sqlite.SQLiteException
import fr.husi.fmt.AbstractBean
import fr.husi.fmt.RuleItem
import fr.husi.fmt.SingBoxOptions.ACTION_HIJACK_DNS
import fr.husi.fmt.SingBoxOptions.ACTION_REJECT
import fr.husi.fmt.SingBoxOptions.ACTION_ROUTE
import fr.husi.fmt.SingBoxOptions.ACTION_SNIFF
import fr.husi.fmt.SingBoxOptions.NetworkICMP
import fr.husi.fmt.SingBoxOptions.NetworkUDP
import fr.husi.ktx.applyDefaultValues
import fr.husi.repository.resolveRepository
import fr.husi.RuleProvider
import fr.husi.RouteQuickProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.runBlocking
import fr.husi.resources.*

object ProfileManager {

    private val defaultGroupLock = Any()
    private val repository get() = resolveRepository()
    private const val RULESET_GEOSITE_RU_BLOCKED = "geosite-ru-blocked"
    private const val RULESET_GEOSITE_RU_BLOCKED_ALL = "geosite-ru-blocked-all"
    private const val RULESET_GEOIP_RU_BLOCKED = "geoip-ru-blocked"
    private const val RULESET_GEOIP_RU_BLOCKED_COMMUNITY = "geoip-ru-blocked-community"
    private val AI_RULESET_TAGS = listOf(
        "geosite-openai",
        "geosite-anthropic",
        "geosite-google-gemini",
        "geosite-xai",
    )

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateProxy(profile)
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        SagerDatabase.proxyDao.updateProxy(profiles)
    }

    suspend fun updateTraffic(profile: ProxyEntity, tx: Long?, rx: Long?) {
        SagerDatabase.proxyDao.updateTraffic(profile.id, tx, rx)
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (!canDeleteProfiles(groupId, listOf(profileId))) return
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        if (SagerDatabase.proxyDao.countByGroup(groupId).first() > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    suspend fun deleteProfiles(groupId: Long, profileIDs: List<Long>) {
        if (profileIDs.isEmpty()) return
        if (!canDeleteProfiles(groupId, profileIDs)) return
        SagerDatabase.proxyDao.deleteProxies(profileIDs)
        if (profileIDs.contains(DataStore.selectedProxy)) {
            DataStore.selectedProxy = 0L
        }
        if (SagerDatabase.proxyDao.countByGroup(groupId).first() > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    private suspend fun canDeleteProfiles(groupId: Long, profileIDs: List<Long>): Boolean {
        val group = SagerDatabase.groupDao.getById(groupId).first() ?: return true
        if (!group.isGroupDeletable()) return false
        if (group.resolvedOrigin() != GroupOrigin.BUILTIN) return true
        val profiles = SagerDatabase.proxyDao.getByGroup(groupId).first()
        return profileIDs.none { id ->
            profiles.find { it.id == id }?.originSourceId?.isNotBlank() == true
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            runBlocking { SagerDatabase.proxyDao.getById(profileId) }
        } catch (e: SQLiteException) {
            throw IOException(e)
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            runBlocking { SagerDatabase.proxyDao.getEntities(profileIds) }
        } catch (e: SQLiteException) {
            throw IOException(e)
        }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
    }

    suspend fun deleteRulesByIds(ruleIds: List<Long>) {
        SagerDatabase.rulesDao.deleteByIds(ruleIds)
    }

    /**
     * Get all rules as a Flow with automatic initialization.
     *
     * This is a wrapper around [SagerDatabase.rulesDao.allRules] that ensures default rules
     * are created on first app launch. When the Flow is first collected, it checks if the
     * rule list is empty and creates the following rules.
     *
     * Always use this method instead of calling the DAO directly to ensure proper initialization.
     */
    fun getRules(): Flow<List<RuleEntity>> {
        return SagerDatabase.rulesDao.allRules().onStart {
            val currentRules = SagerDatabase.rulesDao.allRules().first()
            if (currentRules.isEmpty() && !DataStore.rulesFirstCreate) {
                DataStore.rulesFirstCreate = true
                seedDefaultRules(defaultBypassCountries())
            }
        }
    }

    /**
     * Bypass-country list used by the first-run rule seeder. Russian-speaking locales get a
     * dedicated RU profile; English users keep the historic CN+IR default; everyone else gets CN.
     */
    private fun defaultBypassCountries(): List<Pair<String, String>> {
        return when (Locale.getDefault().country) {
            "RU", "BY", "KZ" -> listOf("ru" to "Россия")
            Locale.US.country -> listOf("cn" to "中国", "ir" to "Iran")
            else -> listOf("cn" to "中国")
        }
    }

    /**
     * Apply RU routing: full seed when there are no rules; otherwise merge geosite-ru / geoip-ru
     * direct bypass without wiping user rules (no separate NSPK list — covered by RU geosite).
     */
    suspend fun applyRussianPreset() {
        DataStore.rulesFirstCreate = true
        val rules = SagerDatabase.rulesDao.allRules().first()
        if (rules.isEmpty()) {
            seedDefaultRules(listOf("ru" to "Россия"))
        } else {
            mergeRussianPresetRules()
        }
    }

    /**
     * One-tap "Russian mode": RU routing preset, Chocolate4U provider, Cloudflare DoH if remote
     * DNS is still the default, and auto-generated SOCKS5 credentials. Per-app bypass is handled
     * by [fr.husi.utils.enableRussianPerAppBypass].
     */
    suspend fun enableRussianMode() {
        applyRussianPreset()
        DataStore.rulesProvider = RuleProvider.CHOCOLATE4U
        if (DataStore.remoteDns == "tcp://dns.google" || DataStore.remoteDns.isBlank()) {
            DataStore.remoteDns = "https://1.1.1.1/dns-query"
        }
        DataStore.ensureInboundCredentials()
    }

    /**
     * Apply CN routing: full seed when empty; otherwise merge Play Store + CN bypass rules only.
     */
    suspend fun applyChinaPreset() {
        DataStore.rulesFirstCreate = true
        val rules = SagerDatabase.rulesDao.allRules().first()
        if (rules.isEmpty()) {
            seedDefaultRules(listOf("cn" to "中国"))
        } else {
            mergeChinaPresetRules()
        }
    }

    suspend fun applyRouteQuickProfile(profile: Int) {
        when (profile) {
            RouteQuickProfile.RU_DIRECT_ONLY -> applyRuDirectOnlyProfile()
            RouteQuickProfile.RU_DIRECT_WITH_BLOCKED_AND_AI_PROXY -> applyRuDirectWithBlockedAndAiProxyProfile()
            else -> Unit
        }
    }

    private suspend fun applyRuDirectOnlyProfile() {
        applyRussianPreset()
        toggleRulesByPredicate(enabled = false, predicate = ::isBlockedOrAiProxyRule)
    }

    private suspend fun applyRuDirectWithBlockedAndAiProxyProfile() {
        applyRussianPreset()
        ensureRuBlockedProxyRuleMerged()
        ensureAiProxyRuleMerged()
        ensureBlockedAndAiRulesBeforeRuDirect()
        toggleRulesByPredicate(enabled = true, predicate = ::isBlockedOrAiProxyRule)
    }

    private suspend fun mergeRussianPresetRules() {
        var rules = SagerDatabase.rulesDao.allRules().first()
        val display = "Россия"
        val country = "ru"
        val geositeRuleTag = "geosite-category-ru"
        fun hasGeositeBypass(c: String) = rules.any {
            it.action == ACTION_ROUTE && it.outbound == RuleEntity.OUTBOUND_DIRECT &&
                (
                    it.domains.contains("geosite-$c") ||
                        it.domains.contains(geositeRuleTag)
                    )
        }
        fun hasGeoipBypass(c: String) = rules.any {
            it.action == ACTION_ROUTE && it.outbound == RuleEntity.OUTBOUND_DIRECT &&
                it.ip.contains("geoip-$c")
        }
        if (!hasGeositeBypass(country)) {
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_bypass_domain, display),
                    action = ACTION_ROUTE,
                    domains = "set+dns:$geositeRuleTag",
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            rules = SagerDatabase.rulesDao.allRules().first()
        }
        if (!hasGeoipBypass(country)) {
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_bypass_ip, display),
                    action = ACTION_ROUTE,
                    ip = "set-dns:geoip-$country",
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            rules = SagerDatabase.rulesDao.allRules().first()
        }
        ensureRuGeoBypassRulesEnabled()
        ensurePrivateLanBypassRuleMerged()
    }

    private suspend fun mergeChinaPresetRules() {
        var rules = SagerDatabase.rulesDao.allRules().first()
        val display = "中国"
        val country = "cn"
        val playName = repository.getString(Res.string.route_play_store, display)
        val hasPlayStore = rules.any {
            it.action == ACTION_ROUTE && it.outbound == RuleEntity.OUTBOUND_PROXY &&
                it.domains.contains("geosite-google-play")
        }
        if (!hasPlayStore) {
            createRule(
                RuleEntity(
                    enabled = true,
                    name = playName,
                    action = ACTION_ROUTE,
                    domains = "set+dns:geosite-google-play",
                    outbound = RuleEntity.OUTBOUND_PROXY,
                ),
                false,
            )
            rules = SagerDatabase.rulesDao.allRules().first()
        }
        fun hasGeositeBypass(c: String) = rules.any {
            it.action == ACTION_ROUTE && it.outbound == RuleEntity.OUTBOUND_DIRECT &&
                it.domains.contains("geosite-$c")
        }
        fun hasGeoipBypass(c: String) = rules.any {
            it.action == ACTION_ROUTE && it.outbound == RuleEntity.OUTBOUND_DIRECT &&
                it.ip.contains("geoip-$c")
        }
        if (!hasGeositeBypass(country)) {
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_bypass_domain, display),
                    action = ACTION_ROUTE,
                    domains = "set+dns:geosite-$country",
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            rules = SagerDatabase.rulesDao.allRules().first()
        }
        if (!hasGeoipBypass(country)) {
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_bypass_ip, display),
                    action = ACTION_ROUTE,
                    ip = "set-dns:geoip-$country",
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            rules = SagerDatabase.rulesDao.allRules().first()
        }
        ensurePrivateLanBypassRuleMerged()
    }

    /**
     * For installs that already have rules: ensure LAN `private` → direct exists and is on.
     * [DataStore.bypassLan] only adds VpnService-side excluded routes; sing-box still needs this
     * rule when traffic hits TUN (ROM/stack differences).
     */
    suspend fun ensureBootstrapRoutingDefaults() {
        if (SagerDatabase.rulesDao.allRules().first().isEmpty()) return
        when (Locale.getDefault().country) {
            "RU", "BY", "KZ" -> mergeRussianPresetRules()
            else -> ensurePrivateLanBypassRuleMerged()
        }
    }

    /** Turns RU geosite/geoip → direct rules back on if they exist but were disabled. */
    private suspend fun ensureRuGeoBypassRulesEnabled() {
        val rules = SagerDatabase.rulesDao.allRules().first()
        val geositeRuleTag = "geosite-category-ru"
        val country = "ru"
        for (rule in rules) {
            if (rule.dnsOnly || rule.action != ACTION_ROUTE || rule.outbound != RuleEntity.OUTBOUND_DIRECT) {
                continue
            }
            val d = rule.domains.lowercase()
            val ip = rule.ip.lowercase()
            val isRuGeo = d.contains(geositeRuleTag) || d.contains("geosite-$country")
            val isRuIp = ip.contains("geoip-$country")
            if ((isRuGeo || isRuIp) && !rule.enabled) {
                rule.enabled = true
                updateRule(rule)
            }
        }
    }

    private suspend fun ensurePrivateLanBypassRuleMerged() {
        var rules = SagerDatabase.rulesDao.allRules().first()
        fun isPrivateLanRule(rule: RuleEntity): Boolean {
            if (rule.dnsOnly || rule.action != ACTION_ROUTE || rule.outbound != RuleEntity.OUTBOUND_DIRECT) {
                return false
            }
            val ip = rule.ip.trim().lowercase()
            if (ip.isBlank()) return false
            if (ip.contains("geoip-")) return false
            return ip == RuleItem.CONTENT_PRIVATE.lowercase() ||
                ip.split(',', '\n', ';', ' ').any { it.trim() == RuleItem.CONTENT_PRIVATE.lowercase() }
        }
        if (!rules.any(::isPrivateLanRule)) {
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_opt_bypass_lan),
                    action = ACTION_ROUTE,
                    ip = RuleItem.CONTENT_PRIVATE,
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            rules = SagerDatabase.rulesDao.allRules().first()
        }
        rules = SagerDatabase.rulesDao.allRules().first()
        for (rule in rules) {
            if (isPrivateLanRule(rule) && !rule.enabled) {
                rule.enabled = true
                updateRule(rule)
            }
        }
    }

    private suspend fun ensureRuBlockedProxyRuleMerged() {
        val rules = SagerDatabase.rulesDao.allRules().first()
        if (rules.any(::isRuBlockedProxyRule)) return
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.route_proxy_ru_blocked),
                action = ACTION_ROUTE,
                domains = buildString {
                    append("set+dns:")
                    append(RULESET_GEOSITE_RU_BLOCKED_ALL)
                    append('\n')
                    append("set+dns:")
                    append(RULESET_GEOSITE_RU_BLOCKED)
                },
                ip = buildString {
                    append("set-dns:")
                    append(RULESET_GEOIP_RU_BLOCKED)
                    append('\n')
                    append("set-dns:")
                    append(RULESET_GEOIP_RU_BLOCKED_COMMUNITY)
                },
                outbound = RuleEntity.OUTBOUND_PROXY,
            ),
            false,
        )
    }

    private suspend fun ensureAiProxyRuleMerged() {
        val rules = SagerDatabase.rulesDao.allRules().first()
        if (rules.any(::isAiProxyRule)) return
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.route_proxy_ai_services),
                action = ACTION_ROUTE,
                domains = AI_RULESET_TAGS.joinToString(separator = "\n") { "set+dns:$it" },
                outbound = RuleEntity.OUTBOUND_PROXY,
            ),
            false,
        )
    }

    private suspend fun toggleRulesByPredicate(
        enabled: Boolean,
        predicate: (RuleEntity) -> Boolean,
    ) {
        val rules = SagerDatabase.rulesDao.allRules().first()
        for (rule in rules) {
            if (!predicate(rule) || rule.enabled == enabled) continue
            updateRule(rule.copy(enabled = enabled))
        }
    }

    /**
     * Conflict guard for the 4 core split-routing rules:
     * - geosite-ru / geoip-ru -> direct
     * - ru-blocked / ai services -> proxy
     * Proxy exceptions must be evaluated before RU direct bypass.
     */
    suspend fun stabilizeBlockedAndAiRulesBeforeRuDirect() {
        ensureBlockedAndAiRulesBeforeRuDirect()
    }

    private suspend fun ensureBlockedAndAiRulesBeforeRuDirect() {
        val rules = SagerDatabase.rulesDao.allRules().first().sortedBy { it.userOrder }
        val conflictRules = rules.filter { isBlockedOrAiProxyRule(it) }
        if (conflictRules.isEmpty()) return
        val insertIndex = rules.indexOfFirst(::isRuGeoDirectRule).takeIf { it >= 0 } ?: return
        val conflictIds = conflictRules.map { it.id }.toHashSet()
        val reordered = buildList(rules.size) {
            addAll(rules.take(insertIndex).filterNot { conflictIds.contains(it.id) })
            addAll(conflictRules)
            addAll(rules.drop(insertIndex).filterNot { conflictIds.contains(it.id) })
        }
        val toUpdate = reordered.mapIndexedNotNull { index, rule ->
            val newOrder = index.toLong()
            if (rule.userOrder == newOrder) {
                null
            } else {
                rule.copy(userOrder = newOrder)
            }
        }
        if (toUpdate.isNotEmpty()) {
            SagerDatabase.rulesDao.updateRules(toUpdate)
        }
    }

    private fun isRuGeoDirectRule(rule: RuleEntity): Boolean {
        if (rule.dnsOnly || rule.action != ACTION_ROUTE || rule.outbound != RuleEntity.OUTBOUND_DIRECT) {
            return false
        }
        val domains = rule.domains.lowercase()
        val ip = rule.ip.lowercase()
        return domains.contains("geosite-category-ru") || domains.contains("geosite-ru") || ip.contains("geoip-ru")
    }

    private fun isRuBlockedProxyRule(rule: RuleEntity): Boolean {
        if (rule.dnsOnly || rule.action != ACTION_ROUTE || rule.outbound != RuleEntity.OUTBOUND_PROXY) {
            return false
        }
        val domains = rule.domains.lowercase()
        val ip = rule.ip.lowercase()
        return domains.contains(RULESET_GEOSITE_RU_BLOCKED) ||
            domains.contains(RULESET_GEOSITE_RU_BLOCKED_ALL) ||
            ip.contains(RULESET_GEOIP_RU_BLOCKED) ||
            ip.contains(RULESET_GEOIP_RU_BLOCKED_COMMUNITY)
    }

    private fun isAiProxyRule(rule: RuleEntity): Boolean {
        if (rule.dnsOnly || rule.action != ACTION_ROUTE || rule.outbound != RuleEntity.OUTBOUND_PROXY) {
            return false
        }
        val domains = rule.domains.lowercase()
        return AI_RULESET_TAGS.any { tag -> domains.contains(tag) }
    }

    private fun isBlockedOrAiProxyRule(rule: RuleEntity): Boolean {
        return isRuBlockedProxyRule(rule) || isAiProxyRule(rule)
    }

    private suspend fun seedDefaultRules(
        countries: List<Pair<String, String>>,
    ) {
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.sniff),
                action = ACTION_SNIFF,
            ),
        )
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.hijack_dns),
                protocol = setOf("dns"),
                action = ACTION_HIJACK_DNS,
            ),
        )
        createRule(
            RuleEntity(
                enabled = true,
                action = ACTION_ROUTE,
                name = repository.getString(Res.string.bypass_icmp),
                network = setOf(NetworkICMP),
                outbound = RuleEntity.OUTBOUND_DIRECT,
            ),
        )
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.route_opt_block_quic),
                action = ACTION_REJECT,
                protocol = setOf("quic"),
                network = setOf(NetworkUDP),
            ),
        )
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.route_opt_block_ads),
                action = ACTION_REJECT,
                domains = "set+dns:geosite-category-ads-all",
            ),
        )
        for ((country, displayCountry) in countries) {
            if (country == "cn") createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_play_store, displayCountry),
                    action = ACTION_ROUTE,
                    domains = "set+dns:geosite-google-play",
                    outbound = RuleEntity.OUTBOUND_PROXY,
                ),
                false,
            )
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_bypass_domain, displayCountry),
                    action = ACTION_ROUTE,
                    domains = if (country == "ru") {
                        "set+dns:geosite-category-ru"
                    } else {
                        "set+dns:geosite-$country"
                    },
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            createRule(
                RuleEntity(
                    enabled = true,
                    name = repository.getString(Res.string.route_bypass_ip, displayCountry),
                    action = ACTION_ROUTE,
                    ip = "set-dns:geoip-$country",
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
        }
        createRule(
            RuleEntity(
                enabled = true,
                name = repository.getString(Res.string.route_opt_bypass_lan),
                action = ACTION_ROUTE,
                ip = RuleItem.CONTENT_PRIVATE,
                outbound = RuleEntity.OUTBOUND_DIRECT,
            ),
            false,
        )
    }

    fun enabledRules(): Flow<List<RuleEntity>> {
        return getRules().map {
            it.filter { it.enabled }
        }
    }

    /**
     * Get all groups as a Flow with automatic initialization.
     *
     * This is a wrapper around [SagerDatabase.groupDao.allGroups] that ensures at least one
     * group exists. When the Flow is first collected, it checks if the group list is empty
     * and creates a default ungrouped group if needed.
     *
     * Always use this method instead of calling the DAO directly to ensure proper initialization.
     */
    fun getGroups(): Flow<List<ProxyGroup>> {
        return SagerDatabase.groupDao.allGroups().onStart {
            ensureDefaultGroupId()
        }
    }

    fun ensureDefaultGroupId(): Long = synchronized(defaultGroupLock) {
        runBlocking {
            SagerDatabase.groupDao.firstGroupId()
                ?: SagerDatabase.groupDao.ungroupedId()
                ?: SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        }
    }

}
