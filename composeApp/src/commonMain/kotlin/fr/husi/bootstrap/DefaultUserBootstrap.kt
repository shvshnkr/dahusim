package fr.husi.bootstrap

import fr.husi.GroupType
import fr.husi.RouteQuickProfile
import fr.husi.SubscriptionType
import fr.husi.database.CatalogOwnership
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.fmt.v2ray.VLESSBean
import fr.husi.group.GroupUpdater
import fr.husi.ktx.Logs
import fr.husi.ktx.applyDefaultValues
import fr.husi.ktx.parseProxies
import fr.husi.simplemode.probeSimpleModeNetwork
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import fr.husi.subscription.catalog.SubscriptionCatalogDefaults
import kotlinx.coroutines.flow.first

object DefaultUserBootstrap {
    private const val AUTO_UPDATE_MINUTES = 60
    private const val STANDALONE_SE_GROUP = "Quick standalone SE"
    private const val STANDALONE_SE_PROFILE = "SE relay builtin"
    private const val LEGACY_BUILTIN_HELPERS_GROUP = "Built-in (simple mode helpers)"
    private const val STANDALONE_SE_VLESS_URI: String =
        "vless://2001daf3-5c56-4bef-8ea6-8dd0493c5a4c@2.27.23.73:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.deepl.com&fp=chrome&pbk=ZHEMPjSWslk6_qD2JNQzd5enUPz8nY9mYRRuM6NkZmU&sid=1a&packetEncoding=xudp#%F0%9F%87%B8%F0%9F%87%AA%20SE%20%7C%20VLESS%20%7C%20%E2%9A%A1%201362ms"
    private val obsoleteQuickSubscriptionLinks = setOf(
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
    )

    suspend fun bootstrapAll() {
        applyBootstrapNetworkProbe()
        ensureReservedBuiltinSlot()
        bootstrapDefaultSubscriptions()
        runCatching {
            SubscriptionCatalogCoordinator.syncIfDue(manual = false)
        }.onFailure {
            Logs.w("DefaultUserBootstrap: subscription catalog sync", it)
        }
        ensureStandaloneSeVlessProfile()
        bootstrapPerAppDefaults()
        ProfileManager.ensureBootstrapRoutingDefaults()
        if (DataStore.routeQuickProfile != RouteQuickProfile.MANUAL) {
            ProfileManager.applyRouteQuickProfile(DataStore.routeQuickProfile)
        }
        removeLegacyBuiltinHelpersGroup()
    }

    private suspend fun applyBootstrapNetworkProbe() {
        val net = probeSimpleModeNetwork()
        DataStore.activeWhitelistRestrictedNetwork = net.whitelistOnly
        if (net.whitelistOnly) {
            DataStore.simpleModeUseWhitelistBuiltinPoolOnly = true
        }
        Logs.d(
            "DefaultUserBootstrap: network probe whitelistOnly=${net.whitelistOnly} " +
                "hasInternet=${net.hasAnyInternet}",
        )
    }

    private suspend fun removeLegacyBuiltinHelpersGroup() {
        val groups = SagerDatabase.groupDao.allGroups().first()
        val legacy = groups.find { it.name == LEGACY_BUILTIN_HELPERS_GROUP } ?: return
        GroupManager.deleteGroup(legacy.id)
        Logs.d("DefaultUserBootstrap: removed legacy builtin helpers group id=${legacy.id}")
    }

    private suspend fun ensureReservedBuiltinSlot() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
        val reservedId = SubscriptionCatalogDefaults.reservedBuiltinSourceId()
        val existing = subscriptions.find { it.subscription?.sourceId == reservedId }
        if (existing != null) {
            val sub = existing.subscription ?: return
            if (sub.catalogOwnership != CatalogOwnership.PROTECTED_RESERVED) {
                sub.catalogOwnership = CatalogOwnership.PROTECTED_RESERVED
                sub.managedByRemote = true
                GroupManager.updateGroup(existing)
            }
            return
        }
        GroupManager.createGroup(
            ProxyGroup(
                name = SubscriptionCatalogDefaults.RESERVED_BUILTIN_GROUP_NAME,
                type = GroupType.SUBSCRIPTION,
            ).apply {
                subscription = SubscriptionBean().apply {
                    type = SubscriptionType.RAW
                    link = ""
                    autoUpdate = false
                    managedByRemote = true
                    sourceId = reservedId
                    catalogOwnership = CatalogOwnership.PROTECTED_RESERVED
                }.applyDefaultValues()
            },
            notifySubscriptionScheduler = false,
        )
        Logs.d("DefaultUserBootstrap: ensured reserved builtin slot")
    }

    private suspend fun bootstrapDefaultSubscriptions() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
        migrateLegacyBuiltinLinks(subscriptions)
        ensureBuiltinManagedMarkers(subscriptions)
        val existingLinks = SagerDatabase.groupDao.subscriptions()
            .mapNotNull { it.subscription?.link }
            .toSet()

        var createdAny = false
        SubscriptionCatalogDefaults.STARTER_SEEDS.forEachIndexed { index, seed ->
            val link = seed.link
            if (link in existingLinks) return@forEachIndexed
            val created = GroupManager.createGroup(
                ProxyGroup(
                    name = seed.name.ifBlank { "Quick Subscription ${index + 1}" },
                    type = GroupType.SUBSCRIPTION,
                ).apply {
                    subscription = SubscriptionBean().apply {
                        this.type = SubscriptionType.RAW
                        this.link = link
                        autoUpdate = true
                        autoUpdateDelay = AUTO_UPDATE_MINUTES
                        deduplication = true
                        updateWhenConnectedOnly = false
                        managedByRemote = true
                        sourceId = SubscriptionCatalogDefaults.builtinSourceId(seed.sourceKey)
                        connectPoolRole = seed.poolRole
                    }
                },
                notifySubscriptionScheduler = false,
            )
            GroupUpdater.executeUpdate(created, byUser = false)
            createdAny = true
        }

        val obsoleteIds = subscriptions
            .filter { group ->
                val link = group.subscription?.link ?: return@filter false
                link in obsoleteQuickSubscriptionLinks &&
                    (group.name?.startsWith("Quick Subscription") == true)
            }
            .map { it.id }
        if (obsoleteIds.isNotEmpty()) {
            GroupManager.deleteGroup(obsoleteIds)
        }

        DataStore.defaultSubscriptionsBootstrapped = true
        Logs.d(
            "Default subscriptions bootstrap completed, createdAny=$createdAny, removedLegacy=${obsoleteIds.size}",
        )
    }

    private suspend fun ensureBuiltinManagedMarkers(subscriptions: List<ProxyGroup>) {
        val byLink = subscriptions.associateBy { it.subscription?.link.orEmpty() }
        SubscriptionCatalogDefaults.STARTER_SEEDS.forEach { seed ->
            val group = byLink[seed.link] ?: return@forEach
            val sub = group.subscription ?: return@forEach
            val targetSourceId = SubscriptionCatalogDefaults.builtinSourceId(seed.sourceKey)
            var changed = false
            if (sub.sourceId != targetSourceId) {
                sub.sourceId = targetSourceId
                changed = true
            }
            if (!sub.managedByRemote) {
                sub.managedByRemote = true
                changed = true
            }
            if (sub.connectPoolRole != seed.poolRole) {
                sub.connectPoolRole = seed.poolRole
                changed = true
            }
            if (changed) GroupManager.updateGroup(group)
        }
    }

    private suspend fun ensureStandaloneSeVlessProfile() {
        val bean = parseProxies(STANDALONE_SE_VLESS_URI)
            .mapNotNull { it as? VLESSBean }
            .firstOrNull()
            ?: return
        bean.name = STANDALONE_SE_PROFILE
        bean.applyDefaultValues()
        val allGroups = SagerDatabase.groupDao.allGroups().first()
        var seGroup = allGroups.find { it.name == STANDALONE_SE_GROUP }
        if (seGroup == null) {
            seGroup = GroupManager.createGroup(
                ProxyGroup(name = STANDALONE_SE_GROUP, type = GroupType.BASIC),
            )
        }
        val gid = seGroup.id
        val existing = SagerDatabase.proxyDao.getByGroup(gid).first()
        val entity = existing.find {
            it.type == ProxyEntity.TYPE_VLESS && it.vlessBean?.name == STANDALONE_SE_PROFILE
        }
        if (entity == null) {
            ProfileManager.createProfile(gid, bean)
        } else {
            entity.putBean(bean)
            ProfileManager.updateProfile(entity)
        }
    }

    private suspend fun migrateLegacyBuiltinLinks(subscriptions: List<ProxyGroup>) {
        val legacyLinkToCanonical = buildMap<String, String> {
            SubscriptionCatalogDefaults.STARTER_SEEDS.forEach { seed ->
                seed.legacyLinks.forEach { legacy ->
                    put(legacy, seed.link)
                }
            }
        }
        for (group in subscriptions) {
            val sub = group.subscription ?: continue
            val canonical = legacyLinkToCanonical[sub.link] ?: continue
            if (canonical == sub.link) continue
            sub.link = canonical
            GroupManager.updateGroup(group)
            GroupUpdater.executeUpdate(group, byUser = false)
            Logs.d("DefaultUserBootstrap: migrated group id=${group.id} to canonical link")
        }
    }
}

internal expect suspend fun bootstrapPerAppDefaults()
