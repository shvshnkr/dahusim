package fr.husi.scenario.journey

import fr.husi.database.DataStore
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.SimpleModeMessengerProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessengerCompositePrepareJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun wlAlwaysUsesCompositeMessengerProbe() {
        assertTrue(SimpleModeMessengerProbe.compositeRequired(whitelistOnly = true))
    }

    @Test
    fun openUsesCompositeWhenTelegramProbeEnabled() {
        DataStore.simpleModeTelegramProbe = true
        assertTrue(SimpleModeMessengerProbe.compositeRequired(whitelistOnly = false))
    }

    @Test
    fun openSkipsCompositeWhenTelegramProbeDisabled() {
        DataStore.simpleModeTelegramProbe = false
        assertFalse(SimpleModeMessengerProbe.compositeRequired(whitelistOnly = false))
    }

    @Test
    fun dashboardProbeUrlsIncludeDcRequiredWhenCompositeOn() {
        DataStore.simpleModeTelegramProbe = true
        assertEquals(
            listOf(
                SimpleModeMessengerProbe.WEB_URL,
                SimpleModeMessengerProbe.DC_REQUIRED_URL,
            ),
            SimpleModeHealthRoute.dashboardProbeUrls(whitelistOnly = false),
        )
    }

    @Test
    fun domainOnlySocksProfileWouldFailCompositeGate() {
        val partial = SimpleModeMessengerProbe.PrepareResult(
            webDelayMs = 365,
            dcRequiredDelayMs = null,
            dcSecondaryDelayMs = null,
        )
        assertFalse(partial.ready)
    }
}
