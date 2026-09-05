package fr.husi.database

import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * The full-sweep flag is what widens the ADAPT prepare timeout (BS-S2). It must never leak
 * across prepares: any new prepare starts with a clean flag, so a later compact sweep is not
 * stuck with the 180s (or stale 30s) budget of a previous forced full sweep.
 */
class AutoServerSelectorFullSweepFlagTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SagerDatabase.proxyDao.reset()
    }

    @Test
    fun staleFlagFromInterruptedSweepIsClearedByNextPrepare() = runTest {
        AutoServerSelector.fullSweepInProgress = true
        AutoServerSelector.prepareForConnect()
        assertFalse(
            AutoServerSelector.fullSweepInProgress,
            "every prepare must start with a clean full-sweep flag",
        )
    }
}
