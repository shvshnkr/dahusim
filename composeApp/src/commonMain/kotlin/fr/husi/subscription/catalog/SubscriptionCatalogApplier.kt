package fr.husi.subscription.catalog

import fr.husi.GroupType
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.ktx.applyDefaultValues
import java.net.URL

object SubscriptionCatalogApplier {

    private const val GITHUB_SOURCE_PREFIX = "gh."
    private const val BUILTIN_SOURCE_PREFIX = "builtin."
    private const val MAX_BULK_DELETE_ABS = 10
    private const val MAX_BULK_DELETE_PERCENT = 0.30
    private const val PENDING_REMOVE_GRACE_SECONDS = 24L * 60L * 60L

    suspend fun apply(
        document: SubscriptionCatalogDocument,
        rawHash: String,
    ): SubscriptionCatalogSyncResult {
        val upserts = document.entries.filterIsInstance<SubscriptionCatalogEntry.Upsert>()
            .associateBy { it.sourceId }
        val explicitRemoves = document.entries
            .filterIsInstance<SubscriptionCatalogEntry.Remove>()
            .mapTo(LinkedHashSet()) { it.sourceId }

        val subscriptions = SagerDatabase.groupDao.subscriptions()
        val githubManaged = subscriptions.filter {
            val sub = it.subscription
            sub != null && sub.isGithubManagedSourceId()
        }
        val githubManagedBySourceId = githubManaged.associateBy {
            it.subscription!!.sourceId.removePrefix(GITHUB_SOURCE_PREFIX)
        }
        val allByNormalizedLink = subscriptions
            .filter { it.subscription?.link?.isNotBlank() == true }
            .associateBy { normalizeLink(it.subscription!!.link) }

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
        val nowSeconds = System.currentTimeMillis() / 1000L

        for (record in upserts.values) {
            val sourceId = githubSourceId(record.sourceId)
            val existing = githubManagedBySourceId[record.sourceId]
                ?: allByNormalizedLink[normalizeLink(record.link)]
            if (existing == null) {
                GroupManager.createGroup(
                    ProxyGroup(
                        name = record.name,
                        type = GroupType.SUBSCRIPTION,
                    ).apply {
                        subscription = SubscriptionBean().apply {
                            type = record.subscriptionType
                            link = record.link
                            autoUpdate = true
                            autoUpdateDelay = 720
                            deduplication = true
                            this.sourceId = sourceId
                            managedByRemote = true
                            pendingRemoveAt = 0L
                            remoteGenerationSeen = document.generation
                            fetchProfile = record.fetchProfile
                            customUserAgent = record.customUserAgent
                        }.applyDefaultValues()
                    },
                )
                created++
            } else {
                val group = existing.copy()
                val sub = (group.subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                    type = record.subscriptionType
                    link = record.link
                    if (isBuiltinManagedSourceId()) {
                        // Built-in managed groups are never re-owned by GitHub catalog.
                        managedByRemote = true
                    } else if (isGithubManagedSourceId()) {
                        this.sourceId = sourceId
                        managedByRemote = true
                    }
                    pendingRemoveAt = 0L
                    remoteGenerationSeen = document.generation
                    fetchProfile = record.fetchProfile
                    customUserAgent = record.customUserAgent
                }
                group.name = record.name
                group.subscription = sub
                GroupManager.updateGroup(group)
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

        return SubscriptionCatalogSyncResult.Success(
            created = created,
            updated = updated,
            removed = removed,
            stagedRemoval = stagedRemoval,
        )
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

    private fun githubSourceId(sourceId: String): String = "$GITHUB_SOURCE_PREFIX$sourceId"

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
        return managedByRemote && sourceId.startsWith(GITHUB_SOURCE_PREFIX)
    }

    private fun SubscriptionBean.isBuiltinManagedSourceId(): Boolean {
        return managedByRemote && sourceId.startsWith(BUILTIN_SOURCE_PREFIX)
    }
}
