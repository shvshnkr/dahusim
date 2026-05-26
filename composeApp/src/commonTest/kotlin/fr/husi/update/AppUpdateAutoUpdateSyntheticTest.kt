package fr.husi.update

import fr.husi.database.DataStore
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateAutoUpdateSyntheticTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun `default install enables planner with 24h interval`() = runBlocking {
        val plan = AppUpdateAutoUpdatePlanner.plan(nowSeconds = 10_000L)
        assertNotNull(plan)
        assertEquals(24 * 60, plan.repeatIntervalMinutes)
        assertEquals(0L, plan.initialDelaySeconds)
        assertTrue(AppUpdateAutoUpdatePlanner.isCheckDue(nowSeconds = 10_000L))
    }

    @Test
    fun `disabled checks remove planner`() = runBlocking {
        DataStore.appUpdateCheckEnabled = false
        assertNull(AppUpdateAutoUpdatePlanner.plan())
    }

    @Test
    fun `recent successful check is not due yet`() = runBlocking {
        val nowSeconds = 100_000L
        DataStore.appUpdateLastCheckAt = nowSeconds - 3600
        assertFalse(AppUpdateAutoUpdatePlanner.isCheckDue(nowSeconds))
        val plan = AppUpdateAutoUpdatePlanner.plan(nowSeconds)
        assertEquals(23 * 3600L, plan?.initialDelaySeconds)
    }

    @Test
    fun `overdue check has zero initial delay`() = runBlocking {
        val nowSeconds = 200_000L
        DataStore.appUpdateLastCheckAt = nowSeconds - 25 * 3600
        assertTrue(AppUpdateAutoUpdatePlanner.isCheckDue(nowSeconds))
        assertEquals(0L, AppUpdateAutoUpdatePlanner.plan(nowSeconds)?.initialDelaySeconds)
    }
}
