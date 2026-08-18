package fr.husi.scenario.journey

import fr.husi.database.PrepareForConnectResult
import fr.husi.simplemode.SimpleModeConnectCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BS servers flap on a minute scale (field 2026-08-18: 340 dead at 02:55, alive at 03:17).
 * After an AllProbesDead sweep the connect flow must keep watching and auto-connect the moment
 * any candidate verifies — the user taps Connect once, not every few minutes.
 */
class WlServerRevivalWatchJourneyTest : FeatureJourneyTest() {

    @Test
    fun bsDeadSweepAutoConnectsWhenServerRevives() = runTest {
        var calls = 0
        val outcome = SimpleModeConnectCoordinator.awaitWlServerRevival(
            initial = PrepareForConnectResult.AllProbesDead,
            refreshBudgetMs = 1000L,
            whitelistOnly = true,
            watchMs = 400L,
            pollIntervalMs = 50L,
            prepare = {
                calls++
                if (calls >= 2) PrepareForConnectResult.Success(340L) else PrepareForConnectResult.AllProbesDead
            },
        )

        assertEquals(PrepareForConnectResult.Success(340L), outcome)
        assertTrue(calls >= 2)
    }
}
