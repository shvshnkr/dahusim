package fr.husi.bootstrap

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.bg.SubscriptionUpdater
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
import fr.husi.repository.resolveAndroidRepository
import kotlinx.coroutines.flow.first

object DefaultUserBootstrap {
    private const val AUTO_UPDATE_MINUTES = 60
    private const val STANDALONE_SE_GROUP = "Quick standalone SE"
    private const val STANDALONE_SE_PROFILE = "SE relay builtin"
    /** Swordware.txt now serves `happ://crypt4/...` which RawUpdater does not parse; use plain vless list reserve. */
    private const val brokenSwordwareTxtLink =
        "https://raw.githubusercontent.com/mbelspb-gif/dddddad/refs/heads/main/Swordware.txt"
    private const val swordwareVlessReserveLink =
        "https://raw.githubusercontent.com/mbelspb-gif/ffsfsfssdf/refs/heads/main/TG-swordware"
    private val defaultSubscriptionLinks = listOf(
        "https://mifa.world/vless",
        "https://mifa.world/hysteria",
        swordwareVlessReserveLink,
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
        "https://gist.githubusercontent.com/flaafix/c79a81037d15163360571c7a7331b153/raw/AetrisVPN.txt",
        "https://raw.githubusercontent.com/nzea243/ikoV31tud_vpn/refs/heads/main/tri_228.txt",
        "https://raw.githubusercontent.com/HikaruApps/WhiteLattice/refs/heads/main/subscriptions/config.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/BlackList.txt",
        "https://wlrus.lol/confs/blackl.txt",
    )
    private val obsoleteQuickSubscriptionLinks = setOf(
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
    )
    private val targetPackages = linkedSetOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.google.android.youtube",
    )
    private val optionalRevancedPackages = listOf(
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
    )

    suspend fun bootstrapAll() {
        bootstrapDefaultSubscriptions()
        ensureStandaloneSeVlessProfile()
        bootstrapPerAppDefaults()
        ProfileManager.ensureBootstrapRoutingDefaults()
        WhitelistBuiltinBootstrap.ensureGroupAndProfiles()
    }

    private suspend fun bootstrapDefaultSubscriptions() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
        migrateBrokenSwordwareSubscription(subscriptions)
        val existingLinks = SagerDatabase.groupDao.subscriptions()
            .mapNotNull { it.subscription?.link }
            .toSet()

        var createdAny = false
        defaultSubscriptionLinks.forEachIndexed { index, link ->
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
                    }
                },
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
        SubscriptionUpdater.reconfigureUpdater()
        Logs.d(
            "Default subscriptions bootstrap completed, createdAny=$createdAny, removedLegacy=${obsoleteIds.size}",
        )
    }

    private suspend fun ensureStandaloneSeVlessProfile() {
        val bean = parseProxies(WhitelistBuiltinVlessShareLines.standaloneSeVlessUri)
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

    private suspend fun migrateBrokenSwordwareSubscription(subscriptions: List<ProxyGroup>) {
        for (group in subscriptions) {
            val sub = group.subscription ?: continue
            if (sub.link != brokenSwordwareTxtLink) continue
            sub.link = swordwareVlessReserveLink
            GroupManager.updateGroup(group)
            GroupUpdater.executeUpdate(group, byUser = false)
            Logs.d("DefaultUserBootstrap: migrated group id=${group.id} from broken Swordware.txt to vless reserve")
        }
    }

    private suspend fun bootstrapPerAppDefaults() {
        if (DataStore.defaultPerAppBootstrapped) {
            ensureTelegramVariantsIncluded()
            return
        }

        val packageManager = resolveAndroidRepository().packageManager
        val packages = targetPackages.toMutableSet()
        optionalRevancedPackages
            .firstOrNull { packageName ->
                runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
            }
            ?.let { packages.add(it) }

        DataStore.proxyApps = true
        DataStore.bypassMode = false
        DataStore.packages = packages
        DataStore.defaultPerAppBootstrapped = true
    }

    private fun ensureTelegramVariantsIncluded() {
        val current = DataStore.packages
        if (current.isEmpty()) return
        val hasTelegram = current.any {
            it == "org.telegram.messenger" || it == "org.telegram.messenger.web"
        }
        if (!hasTelegram) return
        val merged = current.toMutableSet()
        merged.add("org.telegram.messenger")
        merged.add("org.telegram.messenger.web")
        if (merged.size != current.size) {
            DataStore.packages = merged
        }
    }
}
