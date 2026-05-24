package fr.husi.bootstrap

import fr.husi.GroupType
import fr.husi.RouteQuickProfile
import fr.husi.SubscriptionType
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
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import kotlinx.coroutines.flow.first

object DefaultUserBootstrap {
    private const val BUILTIN_SOURCE_PREFIX = "builtin."
    private const val AUTO_UPDATE_MINUTES = 60
    private const val STANDALONE_SE_GROUP = "Quick standalone SE"
    private const val STANDALONE_SE_PROFILE = "SE relay builtin"
    private const val LEGACY_BUILTIN_HELPERS_GROUP = "Built-in (simple mode helpers)"
    private const val STANDALONE_SE_VLESS_URI: String =
        "vless://2001daf3-5c56-4bef-8ea6-8dd0493c5a4c@2.27.23.73:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.deepl.com&fp=chrome&pbk=ZHEMPjSWslk6_qD2JNQzd5enUPz8nY9mYRRuM6NkZmU&sid=1a&packetEncoding=xudp#%F0%9F%87%B8%F0%9F%87%AA%20SE%20%7C%20VLESS%20%7C%20%E2%9A%A1%201362ms"
    /** Legacy Swordware.txt now serves `happ://crypt4/...` which RawUpdater does not parse. */
    private const val brokenSwordwareTxtLink =
        "https://raw.githubusercontent.com/mbelspb-gif/dddddad/refs/heads/main/Swordware.txt"
    private const val swordwareLegacyReserveLink =
        "https://raw.githubusercontent.com/mbelspb-gif/ffsfsfssdf/refs/heads/main/TG-swordware"
    private const val swordwareCanonicalRawLink =
        "https://raw.githubusercontent.com/mbelspb-gif/gdffgd/refs/heads/main/Swordware.net"

    private data class BuiltinSubscriptionSeed(
        val sourceKey: String,
        val link: String,
        val legacyLinks: Set<String> = emptySet(),
    )

    private val defaultSubscriptionSeeds = listOf(
        BuiltinSubscriptionSeed(sourceKey = "mifa-main", link = "https://mifa.world/vless"),
        BuiltinSubscriptionSeed(sourceKey = "mifa-hysteria", link = "https://mifa.world/hysteria"),
        BuiltinSubscriptionSeed(
            sourceKey = "swordware-main",
            link = swordwareCanonicalRawLink,
            legacyLinks = setOf(brokenSwordwareTxtLink, swordwareLegacyReserveLink),
        ),
        BuiltinSubscriptionSeed(
            sourceKey = "black-vless-rus-mobile",
            link = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
        ),
        BuiltinSubscriptionSeed(
            sourceKey = "aetris-vpn",
            link = "https://gist.githubusercontent.com/flaafix/c79a81037d15163360571c7a7331b153/raw/AetrisVPN.txt",
        ),
        BuiltinSubscriptionSeed(
            sourceKey = "tri-228",
            link = "https://raw.githubusercontent.com/nzea243/ikoV31tud_vpn/refs/heads/main/tri_228.txt",
        ),
        BuiltinSubscriptionSeed(
            sourceKey = "white-lattice",
            link = "https://raw.githubusercontent.com/HikaruApps/WhiteLattice/refs/heads/main/subscriptions/config.txt",
        ),
        BuiltinSubscriptionSeed(
            sourceKey = "white-list-vpn-black",
            link = "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/BlackList.txt",
        ),
        BuiltinSubscriptionSeed(sourceKey = "wlrus-blackl", link = "https://wlrus.lol/confs/blackl.txt"),
    )
    private val obsoleteQuickSubscriptionLinks = setOf(
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
    )

    suspend fun bootstrapAll() {
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

    private suspend fun removeLegacyBuiltinHelpersGroup() {
        val groups = SagerDatabase.groupDao.allGroups().first()
        val legacy = groups.find { it.name == LEGACY_BUILTIN_HELPERS_GROUP } ?: return
        GroupManager.deleteGroup(legacy.id)
        Logs.d("DefaultUserBootstrap: removed legacy builtin helpers group id=${legacy.id}")
    }

    private suspend fun bootstrapDefaultSubscriptions() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
        migrateLegacyBuiltinLinks(subscriptions)
        ensureBuiltinManagedMarkers(subscriptions)
        val existingLinks = SagerDatabase.groupDao.subscriptions()
            .mapNotNull { it.subscription?.link }
            .toSet()

        var createdAny = false
        defaultSubscriptionSeeds.forEachIndexed { index, seed ->
            val link = seed.link
            if (link in existingLinks) return@forEachIndexed
            val created = GroupManager.createGroup(
                ProxyGroup(
                    name = "Quick Subscription ${index + 1}",
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
                        sourceId = builtinSourceId(seed.sourceKey)
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
        defaultSubscriptionSeeds.forEach { seed ->
            val group = byLink[seed.link] ?: return@forEach
            val sub = group.subscription ?: return@forEach
            val targetSourceId = builtinSourceId(seed.sourceKey)
            if (sub.sourceId == targetSourceId && sub.managedByRemote) return@forEach
            sub.managedByRemote = true
            sub.sourceId = targetSourceId
            GroupManager.updateGroup(group)
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
            defaultSubscriptionSeeds.forEach { seed ->
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

    private fun builtinSourceId(sourceKey: String): String = "$BUILTIN_SOURCE_PREFIX$sourceKey"
}

internal expect suspend fun bootstrapPerAppDefaults()
