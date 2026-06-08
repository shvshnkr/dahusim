package fr.husi.group

import fr.husi.database.SubscriptionBean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionUserAgentPresetsExtendedTest {

    @Test
    fun detectsPanelHtmlResponse() {
        val html = "<html><body><form>login</form></body></html>"
        assertTrue(SubscriptionUserAgentPresets.isLikelyPanelHtmlResponse(html))
    }

    @Test
    fun uaRetryLadderStartsWithHappForWebPanel() {
        val ladder = SubscriptionUserAgentPresets.uaRetryLadder("https://cdn.example.com/api/v1/client/sub/x")
        assertTrue(SubscriptionFetchProfile.HAPP in ladder)
        assertTrue(ladder.indexOf(SubscriptionFetchProfile.HAPP) < ladder.size)
    }

    @Test
    fun shouldOfferRetryForHtmlBodyEvenWithHappProfile() {
        val sub = SubscriptionBean().apply {
            link = "https://cdn.example.com/sub"
            fetchProfile = SubscriptionFetchProfile.HAPP
        }
        assertTrue(
            SubscriptionUserAgentPresets.shouldOfferRetry(sub, "<html>login</html>"),
        )
    }
}
