package fr.husi.subscription.catalog

import fr.husi.GroupType
import fr.husi.bg.SubscriptionUpdater
import fr.husi.database.CatalogOwnership
import fr.husi.database.applyOriginFromSubscription
import fr.husi.database.ConnectPoolRole
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.ktx.Logs
import fr.husi.ktx.applyDefaultValues
import java.net.URL

object SubscriptionCatalogApplier {

    private const val MAX_BULK_DELETE_ABS = 10
    private const val MAX_BULK_DELETE_PERCENT = 0.30
    private const val PENDING_REMOVE_GRACE_SECONDS = 24L * 60L * 60L
    internal const val MANAGED_AUTO_UPDATE_DELAY_MINUTES = 720

    suspend fun apply(
        document: SubscriptionCatalogDocument,
        rawHash: String,
    ): SubscriptionCatalogSyncResult {
        val upserts = document.entries.filterIsInstance<SubscriptionCatalogEntry.Upsert>()
            .associateBy { it.sourceId }
        val explicitRemoves = document.entries
            .filterIsInstance<SubscriptionCatalogEntry.Remove>()
            .mapTo(LinkedHashSet()) { it.sourceId }

        var subscriptions = SagerDatabase.groupDao.subscriptions()
        migrateCatalogOwnership(subscriptions)
        val repairedAutoUpdate = repairManagedAutoUpdateFlags()
        if (repairedAutoUpdate > 0) {
            subscriptions = SagerDatabase.groupDao.subscriptions()
        }

        val githubManaged = subscriptions.filter {
            val sub = it.subscription
            sub != null && sub.catalogOwnership == CatalogOwnership.GH_MANAGED
        }
        val githubManagedBySourceId = githubManaged.associateBy {
            it.subscription!!.sourceId.removePrefix(SubscriptionCatalogDefaults.GITHUB_SOURCE_PREFIX)
        }
        val builtinManagedBySourceId = subscriptions
            .filter {
                val sub = it.subscription
                sub != null && sub.isBuiltinManagedSourceId()
            }
            .associateBy {
                it.subscription!!.sourceId.removePrefix(SubscriptionCatalogDefaults.BUILTIN_SOURCE_PREFIX)
            }

        if (upserts.isEmpty() && githubManaged.isNotEmpty() && !document.allowEmpty) {
            return SubscriptionCatalogSyncResult.Blocked(
                "catalog has no UPSERT records while remote-managed subscriptions exist",
            )
        }

        val missingSourceIds = githubManagedBySourceId.keys.filter { sourceId ->
            sourceId !in upserts || sourceId in explicitRemoves
        }
        if (githubManaged.isNotEmpty() && missingSourceIds.size == githubManaged.size) {
            return SubscriptionCatalogSyncResult.Blocked(
                "catalog would remove all remote-managed subscriptions",
            )
        }
        if (isOverDestructiveThreshold(githubManaged.size, missingSourceIds.size)) {
            return SubscriptionCatalogSyncResult.Blocked(
                "catalog diff is too destructive: ${missingSourceIds.size}/${githubManaged.size}",
            )
        }

        var created = 0
        var updated = 0
        var removed = 0
        var stagedRemoval = 0
        val affectedGroupIds = LinkedHashSet<Long>()
        val nowSeconds = System.currentTimeMillis() / 1000L

        for (record in upserts.values) {
            val sourceId = githubSourceId(record.sourceId)
            val existing = githubManagedBySourceId[record.sourceId]
                ?: builtinManagedBySourceId[record.sourceId]
            if (existing == null) {
                val userWithSameLink = subscriptions.any { group ->
                    val sub = group.subscription ?: return@any false
                    sub.catalogOwnership == CatalogOwnership.USER &&
                        normalizeLink(sub.link) == normalizeLink(record.link)
                }
                if (userWithSameLink) {
                    runCatching {
                        Logs.d(
                            "H16 catalog_upsert_disjoint source=${record.sourceId} " +
                                "link already owned by USER; creating gh.*",
                        )
                    }
                }
                val createdGroup = GroupManager.createGroup(
                    ProxyGroup(
                        name = record.name,
                        type = GroupType.SUBSCRIPTION,
                    ).apply {
                        subscription = SubscriptionBean().apply {
                            type = record.subscriptionType
                            link = record.link
                            applyManagedAutoUpdatePolicy()
                            deduplication = true
                            this.sourceId = sourceId
                            managedByRemote = true
                            catalogOwnership = CatalogOwnership.GH_MANAGED
                            connectPoolRole = record.poolRole
                            pendingRemoveAt = 0L
                            remoteGenerationSeen = document.generation
                            fetchProfile = record.fetchProfile
                            customUserAgent = record.customUserAgent
                        }.applyDefaultValues()
                        applyOriginFromSubscription()
                    },
                    notifySubscriptionScheduler = false,
                )
                affectedGroupIds += createdGroup.id
                created++
            } else {
                val group = existing.copy()
                val sub = (group.subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                    type = record.subscriptionType
                    link = record.link
                    this.sourceId = sourceId
                    managedByRemote = true
                    catalogOwnership = CatalogOwnership.GH_MANAGED
                    connectPoolRole = record.poolRole
                    pendingRemoveAt = 0L
                    remoteGenerationSeen = document.generation
                    fetchProfile = record.fetchProfile
                    customUserAgent = record.customUserAgent
                    applyManagedAutoUpdatePolicy()
                }
                group.name = record.name
                group.subscription = sub
                group.applyOriginFromSubscription()
                GroupManager.updateGroup(group)
                affectedGroupIds += group.id
                updated++
            }
        }

        for (sourceId in missingSourceIds) {
            val group = githubManagedBySourceId[sourceId] ?: continue
            val sub = group.subscription ?: continue
            if (sub.pendingRemoveAt <= 0L) {
                sub.pendingRemoveAt = nowSeconds
                GroupManager.updateGroup(group)
                stagedRemoval++
                continue
            }
            val absentForGenerations = document.generation >= sub.remoteGenerationSeen + 2
            val pendingTooLong = nowSeconds - sub.pendingRemoveAt >= PENDING_REMOVE_GRACE_SECONDS
            if (absentForGenerations || pendingTooLong) {
                GroupManager.deleteGroup(group.id)
                removed++
            } else {
                stagedRemoval++
            }
        }

        DataStore.subscriptionCatalogLastAppliedGeneration = document.generation
        DataStore.subscriptionCatalogLastAppliedHash = rawHash

        if (created > 0 || updated > 0 || repairedAutoUpdate > 0) {
            runCatching { SubscriptionUpdater.reconfigureUpdater() }
                .onFailure { Logs.w("subscription catalog: reconfigure auto update scheduler", it) }
        }

        return SubscriptionCatalogSyncResult.Success(
            created = created,
            updated = updated,
            removed = removed,
            stagedRemoval = stagedRemoval,
            affectedGroupIds = affectedGroupIds.toList(),
            repairedAutoUpdate = repairedAutoUpdate,
        )
    }

    suspend fun repairManagedAutoUpdateFlags(): Int {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
        var repaired = 0
        for (group in subscriptions) {
            val sub = group.subscription ?: continue
            if (!sub.needsManagedAutoUpdateRepair()) continue
            sub.applyManagedAutoUpdatePolicy()
            SagerDatabase.groupDao.updateGroup(group)
            repaired++
        }
        return repaired
    }

    private suspend fun migrateCatalogOwnership(subscriptions: List<ProxyGroup>) {
        for (group in subscriptions) {
            val sub = group.subscription ?: continue
            if (sub.catalogOwnership != CatalogOwnership.USER) continue
            val sourceKey = sub.sourceId
                .removePrefix(SubscriptionCatalogDefaults.GITHUB_SOURCE_PREFIX)
                .removePrefix(SubscriptionCatalogDefaults.BUILTIN_SOURCE_PREFIX)
            val newOwnership = when {
                sub.sourceId == SubscriptionCatalogDefaults.reservedBuiltinSourceId() ->
                    CatalogOwnership.PROTECTED_RESERVED
                sub.isGithubManagedSourceId() -> CatalogOwnership.GH_MANAGED
                else -> CatalogOwnership.USER
            }
            var changed = sub.catalogOwnership != newOwnership
            sub.catalogOwnership = newOwnership
            if (sub.catalogOwnership == CatalogOwnership.GH_MANAGED &&
                sub.connectPoolRole == ConnectPoolRole.ANY
            ) {
                SubscriptionCatalogDefaults.STARTER_SEEDS
                    .find { it.sourceKey == sourceKey }
                    ?.let {
                        sub.connectPoolRole = it.poolRole
                        changed = true
                    }
            }
            if (changed) {
                group.applyOriginFromSubscription()
                GroupManager.updateGroup(group)
            }
        }
    }

    private fun isOverDestructiveThreshold(
        managedCount: Int,
        removalCount: Int,
    ): Boolean {
        if (managedCount <= 0 || removalCount <= 0) return false
        if (removalCount > MAX_BULK_DELETE_ABS) return true
        if (managedCount < 5) return false
        return removalCount.toDouble() / managedCount.toDouble() > MAX_BULK_DELETE_PERCENT
    }

    private fun githubSourceId(sourceId: String): String =
        "${SubscriptionCatalogDefaults.GITHUB_SOURCE_PREFIX}$sourceId"

    private fun normalizeLink(link: String): String {
        val trimmed = link.trim()
        return runCatching {
            val parsed = URL(trimmed)
            "${parsed.protocol.lowercase()}://${parsed.host.lowercase()}${parsed.file}"
        }.getOrElse {
            trimmed
        }
    }

    private fun SubscriptionBean.isGithubManagedSourceId(): Boolean {
        return managedByRemote && sourceId.startsWith(SubscriptionCatalogDefaults.GITHUB_SOURCE_PREFIX)
    }

    private fun SubscriptionBean.isBuiltinManagedSourceId(): Boolean {
        return managedByRemote && sourceId.startsWith(SubscriptionCatalogDefaults.BUILTIN_SOURCE_PREFIX)
    }

    private fun SubscriptionBean.applyManagedAutoUpdatePolicy() {
        autoUpdate = true
        autoUpdateDelay = MANAGED_AUTO_UPDATE_DELAY_MINUTES
    }

    private fun SubscriptionBean.needsManagedAutoUpdateRepair(): Boolean {
        return catalogOwnership == CatalogOwnership.GH_MANAGED && !autoUpdate
    }
}
