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
        SagerDatabase.proxyDao.deleteProxies(profileIDs)
        if (profileIDs.contains(DataStore.selectedProxy)) {
            DataStore.selectedProxy = 0L
        }
        if (SagerDatabase.proxyDao.countByGroup(groupId).first() > 1) {
            GroupManager.rearrange(groupId)
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
     * Wipe all rules and seed the default set for the Russian Federation. Used by the
     * explicit "Apply RU preset" action in the Route screen.
     */
    suspend fun applyRussianPreset() {
        SagerDatabase.rulesDao.reset()
        DataStore.rulesFirstCreate = true
        seedDefaultRules(listOf("ru" to "Россия"))
    }

    /**
     * Wipe all rules and seed the default China preset. Kept for parity with the RU action so
     * users who switch locales can still easily restore the historic defaults.
     */
    suspend fun applyChinaPreset() {
        SagerDatabase.rulesDao.reset()
        DataStore.rulesFirstCreate = true
        seedDefaultRules(listOf("cn" to "中国"))
    }

    private suspend fun seedDefaultRules(countries: List<Pair<String, String>>) {
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
                name = repository.getString(Res.string.route_opt_block_quic),
                action = ACTION_REJECT,
                protocol = setOf("quic"),
                network = setOf(NetworkUDP),
            ),
        )
        createRule(
            RuleEntity(
                name = repository.getString(Res.string.route_opt_block_ads),
                action = ACTION_REJECT,
                domains = "set+dns:geosite-category-ads-all",
            ),
        )
        for ((country, displayCountry) in countries) {
            if (country == "cn") createRule(
                RuleEntity(
                    name = repository.getString(Res.string.route_play_store, displayCountry),
                    action = ACTION_ROUTE,
                    domains = "set+dns:geosite-google-play",
                    outbound = RuleEntity.OUTBOUND_PROXY,
                ),
                false,
            )
            createRule(
                RuleEntity(
                    name = repository.getString(Res.string.route_bypass_domain, displayCountry),
                    action = ACTION_ROUTE,
                    domains = "set+dns:geosite-$country",
                    outbound = RuleEntity.OUTBOUND_DIRECT,
                ),
                false,
            )
            createRule(
                RuleEntity(
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
