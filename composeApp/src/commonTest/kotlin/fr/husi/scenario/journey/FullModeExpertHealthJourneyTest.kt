package fr.husi.scenario.journey

import fr.husi.database.DataStore
import fr.husi.simplemode.ExpertConnectRecoverPolicy
import fr.husi.simplemode.SimpleModeHealthRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullModeExpertHealthJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun fullModeExpertRecoverEnablesHealthWatchdog() {
        DataStore.simpleMode = false
        DataStore.expertConnectRecoverEnabled = true
        assertTrue(ExpertConnectRecoverPolicy.allowsFullModeHealthRecover())
    }

    @Test
    fun fullModeExpertRecoverOffSkipsHealthWatchdog() {
        DataStore.simpleMode = false
        DataStore.expertConnectRecoverEnabled = false
        assertFalse(ExpertConnectRecoverPolicy.allowsFullModeHealthRecover())
    }

    @Test
    fun dashboardUrlTestUsesTelegramWhenMessengerProbeOn() {
        DataStore.simpleModeTelegramProbe = true
        assertEquals(
            SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            SimpleModeHealthRoute.dashboardConnectionTestUrl(),
        )
    }

    @Test
    fun dashboardUrlTestUsesCdnWhenMessengerProbeOff() {
        DataStore.simpleModeTelegramProbe = false
        DataStore.connectionTestURL = "http://cp.cloudflare.com/"
        assertEquals(
            SimpleModeHealthRoute.TUNNEL_HEALTH_CLOUDFLARE_HTTPS,
            SimpleModeHealthRoute.dashboardConnectionTestUrl(),
        )
    }
}
