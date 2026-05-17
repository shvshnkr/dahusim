package fr.husi.subscription.catalog

import fr.husi.ktx.USER_AGENT
import fr.husi.libcore.Libcore

class SubscriptionCatalogRepository {
    suspend fun fetch(url: String): String {
        val client = Libcore.newHttpClient().apply { keepAlive() }
        val response = client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute()
        return response.contentString
    }
}
