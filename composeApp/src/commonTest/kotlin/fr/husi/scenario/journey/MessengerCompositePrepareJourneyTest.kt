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

    @Test
    fun messengerWaveThatOnlyPassedSyntheticallyIsNotRecordedAsUrlVerified() {
        // All URLs failed with inconclusive proxy-dial timeouts -> synthetic latency 1.
        val wave = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 1,
            webError = "dial ccmni1 (15): dial tcp 151.101.56.6:443: i/o timeout",
            dcRequiredLatencyMs = 1,
            dcRequiredError = "dial ccmni1 (15): dial tcp 151.101.56.6:443: i/o timeout",
            dcSecondaryLatencyMs = 1,
            webSynthetic = true,
            dcRequiredSynthetic = true,
        )
        assertTrue(wave.ok)
        assertTrue(wave.wasSynthetic)
        // Post-connect must NOT bless a dead proxy as url-verified.
        assertFalse(
            SimpleModeHealthRoute.postConnectRecordUrlVerified(
                tunnelLatencyMs = wave.latencyMs,
                wasSyntheticSuccess = wave.wasSynthetic,
            ),
        )
    }

    @Test
    fun realMessengerWaveIsRecordedAsUrlVerified() {
        val wave = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 815,
            webError = null,
            dcRequiredLatencyMs = 344,
            dcRequiredError = null,
            dcSecondaryLatencyMs = 977,
        )
        assertTrue(wave.ok)
        assertFalse(wave.wasSynthetic)
        assertTrue(
            SimpleModeHealthRoute.postConnectRecordUrlVerified(
                tunnelLatencyMs = wave.latencyMs,
                wasSyntheticSuccess = wave.wasSynthetic,
            ),
        )
    }
}
