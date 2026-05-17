package fr.husi.subscription.catalog

import fr.husi.database.DataStore
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage

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
            DataStore.subscriptionCatalogLastCheckAt = nowMs
            result
        }.getOrElse { e ->
            Logs.w("subscription catalog sync failed", e)
            DataStore.subscriptionCatalogLastCheckAt = nowMs
            SubscriptionCatalogSyncResult.Error(e.readableMessage)
        }
    }
}
