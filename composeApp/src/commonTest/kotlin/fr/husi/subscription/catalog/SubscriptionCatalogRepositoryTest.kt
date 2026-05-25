package fr.husi.subscription.catalog

import fr.husi.group.SubscriptionHttpFetch
import fr.husi.group.WhitelistSubscriptionFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionCatalogRepositoryTest {

    @Test
    fun `catalog fetch request uses Catalog purpose and wl mirror policy`() {
        val url =
            "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-catalog.txt"
        val request = SubscriptionHttpFetch.Request(
            canonicalLink = url,
            userAgent = "husi/catalog-test",
            purpose = SubscriptionHttpFetch.FetchPurpose.Catalog,
            logContext = "catalog",
            whitelistRestricted = true,
            vpnConnected = false,
        )
        assertEquals(SubscriptionHttpFetch.FetchPurpose.Catalog, request.purpose)
        assertEquals("catalog", request.logContext)
        val fetchLink = WhitelistSubscriptionFetch.resolveFetchLink(
            link = request.canonicalLink,
            whitelistRestricted = request.whitelistRestricted!!,
            vpnConnected = request.vpnConnected!!,
        )
        assertTrue(fetchLink.contains("translate.yandex"))
    }
}
