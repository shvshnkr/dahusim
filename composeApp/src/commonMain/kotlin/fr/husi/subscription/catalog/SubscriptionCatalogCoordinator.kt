package fr.husi.subscription.catalog

import fr.husi.bg.SubscriptionUpdater
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.group.GroupUpdater
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.flow.firstOrNull

object SubscriptionCatalogCoordinator {

    private val repository = SubscriptionCatalogRepository()

    suspend fun syncIfDue(
        manual: Boolean,
    ): SubscriptionCatalogSyncResult {
        if (!DataStore.subscriptionCatalogEnabled) return SubscriptionCatalogSyncResult.Skipped
        val url = DataStore.subscriptionCatalogUrl.trim()
        if (url.isBlank()) return SubscriptionCatalogSyncResult.Skipped

        val nowMs = System.currentTimeMillis()
        if (!manual) {
            val intervalMs =
                DataStore.subscriptionCatalogCheckIntervalHours.coerceIn(6, 12) * 60L * 60L * 1000L
            val elapsed = nowMs - DataStore.subscriptionCatalogLastCheckAt
            if (elapsed in 0 until intervalMs) {
                return SubscriptionCatalogSyncResult.Skipped
            }
        }

        return runCatching {
            val raw = repository.fetch(url)
            val hash = raw.hashCode().toString()
            val document = SubscriptionCatalogParser.parse(raw)
            val result = if (document.generation <= DataStore.subscriptionCatalogLastAppliedGeneration) {
                SubscriptionCatalogSyncResult.Skipped
            } else {
                SubscriptionCatalogApplier.apply(document, hash)
            }
            if (result is SubscriptionCatalogSyncResult.Success) {
                refreshAffectedGroups(result)
                if (result.created > 0 || result.updated > 0 || result.repairedAutoUpdate > 0) {
                    runCatching { SubscriptionUpdater.reconfigureUpdater() }
                        .onFailure { Logs.w("subscription catalog: reconfigure auto update scheduler", it) }
                }
            }
            DataStore.subscriptionCatalogLastCheckAt = nowMs
            result
        }.getOrElse { e ->
            Logs.w("subscription catalog sync failed", e)
            DataStore.subscriptionCatalogLastCheckAt = nowMs
            SubscriptionCatalogSyncResult.Error(e.readableMessage)
        }
    }

    private suspend fun refreshAffectedGroups(result: SubscriptionCatalogSyncResult.Success) {
        if (result.created == 0 && result.updated == 0) return
        for (groupId in result.affectedGroupIds) {
            val group = SagerDatabase.groupDao.getById(groupId).firstOrNull() ?: continue
            runCatching {
                GroupUpdater.executeUpdate(group, byUser = false)
            }.onFailure {
                Logs.w("catalog post-sync update failed group=${group.displayName()}", it)
            }
            val sub = group.subscription
            simpleModeLog(
                "catalog",
                "catalog_sync group=${group.displayName()} source=${sub?.sourceId.orEmpty()} " +
                    "pool=${sub?.connectPoolRole ?: -1} created=${result.created} updated=${result.updated}",
            )
        }
    }
}
