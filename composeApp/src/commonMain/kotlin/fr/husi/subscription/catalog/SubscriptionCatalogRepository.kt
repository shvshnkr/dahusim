package fr.husi.subscription.catalog

import fr.husi.database.DataStore
import fr.husi.group.WhitelistSubscriptionFetch
import fr.husi.ktx.USER_AGENT
import fr.husi.libcore.Libcore

class SubscriptionCatalogRepository {
    suspend fun fetch(url: String): String {
        val fetchLink = WhitelistSubscriptionFetch.resolveFetchLink(
            url,
            whitelistRestricted = DataStore.activeWhitelistRestrictedNetwork,
            vpnConnected = DataStore.serviceState.connected,
        )
        val client = Libcore.newHttpClient().apply { keepAlive() }
        val response = client.newRequest().apply {
            setURL(fetchLink)
            setUserAgent(USER_AGENT)
        }.execute()
        return WhitelistSubscriptionFetch.extractSubscriptionBody(response.contentString)
    }
}
