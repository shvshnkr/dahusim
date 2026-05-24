package fr.husi.group

import fr.husi.database.DataStore
import fr.husi.database.SubscriptionBean
import fr.husi.test.HusiKoinTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionUserAgentPresetsTest : HusiKoinTest() {

    @BeforeTest
    fun resetTemplates() {
        SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.HAPP)
        SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.V2RAYNG)
        SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.V2RAYTUN)
        SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.INCY)
    }

    @Test
    fun `resolve happ from global template`() {
        DataStore.subscriptionUaVersionHapp = "3.19.1"
        val ua = SubscriptionUserAgentPresets.resolveUserAgent(
            SubscriptionBean().apply { fetchProfile = SubscriptionFetchProfile.HAPP },
        )
        assertEquals("happ/3.19.1", ua)
    }

    @Test
    fun `per subscription version override`() {
        val ua = SubscriptionUserAgentPresets.resolveUserAgent(
            SubscriptionBean().apply {
                fetchProfile = SubscriptionFetchProfile.HAPP
                userAgentVersionOverride = "2.9.0"
            },
        )
        assertEquals("happ/2.9.0", ua)
    }

    @Test
    fun `infer web to happ github to default`() {
        assertEquals(
            SubscriptionFetchProfile.HAPP,
            SubscriptionUserAgentPresets.inferFetchProfileForNewLink("https://mifa.world/vless"),
        )
        assertEquals(
            SubscriptionFetchProfile.DEFAULT,
            SubscriptionUserAgentPresets.inferFetchProfileForNewLink(
                "https://raw.githubusercontent.com/foo/bar/main/sub.txt",
            ),
        )
    }

    @Test
    fun `reset template restores factory default`() {
        DataStore.subscriptionUaVersionV2rayNg = "9.9.9"
        SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.V2RAYNG)
        assertEquals(
            SubscriptionUserAgentPresets.FactoryVersions.V2RAYNG,
            DataStore.subscriptionUaVersionV2rayNg,
        )
    }

    @Test
    fun `detect likely user agent rejection`() {
        assertTrue(SubscriptionUserAgentPresets.isLikelyUserAgentRejection("invalid client"))
        assertTrue(SubscriptionUserAgentPresets.isLikelyUserAgentRejection("HTTP 403"))
    }
}
