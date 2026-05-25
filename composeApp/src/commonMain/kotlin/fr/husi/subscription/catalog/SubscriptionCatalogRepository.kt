package fr.husi.subscription.catalog

import fr.husi.group.SubscriptionHttpFetch
import fr.husi.ktx.USER_AGENT

class SubscriptionCatalogRepository {

    suspend fun fetch(url: String): String {
        val response = SubscriptionHttpFetch.fetchText(
            SubscriptionHttpFetch.Request(
                canonicalLink = url,
                userAgent = USER_AGENT,
                purpose = SubscriptionHttpFetch.FetchPurpose.Catalog,
                logContext = "catalog",
            ),
        )
        return response.body
    }
}
