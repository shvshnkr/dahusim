package fr.husi.subscription.catalog

data class SubscriptionCatalogDocument(
    val generation: Long,
    val allowEmpty: Boolean,
    val entries: List<SubscriptionCatalogEntry>,
)

sealed interface SubscriptionCatalogEntry {
    val sourceId: String

    data class Upsert(
        override val sourceId: String,
        val name: String,
        val link: String,
        val subscriptionType: Int,
        val fetchProfile: Int,
        val customUserAgent: String,
    ) : SubscriptionCatalogEntry

    data class Remove(
        override val sourceId: String,
    ) : SubscriptionCatalogEntry
}

sealed interface SubscriptionCatalogSyncResult {
    data object Skipped : SubscriptionCatalogSyncResult
    data class Success(
        val created: Int,
        val updated: Int,
        val removed: Int,
        val stagedRemoval: Int,
    ) : SubscriptionCatalogSyncResult
    data class Blocked(
        val reason: String,
    ) : SubscriptionCatalogSyncResult
    data class Error(
        val reason: String,
    ) : SubscriptionCatalogSyncResult
}
