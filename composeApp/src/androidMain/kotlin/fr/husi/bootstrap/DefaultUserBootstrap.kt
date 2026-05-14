package fr.husi.bootstrap

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.bg.SubscriptionUpdater
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.group.GroupUpdater
import fr.husi.ktx.Logs
import fr.husi.repository.resolveAndroidRepository

object DefaultUserBootstrap {
    private const val AUTO_UPDATE_MINUTES = 60
    /** Swordware.txt now serves `happ://crypt4/...` which RawUpdater does not parse; use plain vless list reserve. */
    private const val brokenSwordwareTxtLink =
        "https://raw.githubusercontent.com/mbelspb-gif/dddddad/refs/heads/main/Swordware.txt"
    private const val swordwareVlessReserveLink =
        "https://raw.githubusercontent.com/mbelspb-gif/ffsfsfssdf/refs/heads/main/TG-swordware"
    private val defaultSubscriptionLinks = listOf(
        "https://mifa.world/vless",
        swordwareVlessReserveLink,
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
