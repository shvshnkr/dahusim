package fr.husi.bg

import fr.husi.database.AssetEntity
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic checks for route-asset auto update: DataStore + DB → planner → due lists.
 */
class RouteAssetAutoUpdateSyntheticTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SagerDatabase.assetDao.reset()
    }

    @Test
    fun `default install has no route asset auto update plan`() = runBlocking {
        assertEquals(0, DataStore.routeAssetsAutoUpdateDelay)
        assertNull(RouteAssetAutoUpdatePlanner.plan())
    }

    @Test
    fun `global managed delay enables planner and marks managed assets due`() = runBlocking {
        val nowSeconds = 5_000L
        DataStore.routeAssetsAutoUpdateDelay = 30
        DataStore.routeAssetsLastUpdated = nowSeconds - 31 * 60

        val plan = RouteAssetAutoUpdatePlanner.plan()
        assertEquals(30, plan?.repeatIntervalMinutes)
        assertEquals(0L, plan?.initialDelaySeconds)
        assertTrue(
            RouteAssetAutoUpdateRunner.isManagedAssetsDue(
                globalAutoUpdateDelayMinutes = DataStore.routeAssetsAutoUpdateDelay,
                globalLastUpdatedSeconds = DataStore.routeAssetsLastUpdated,
                nowSeconds = nowSeconds,
            ),
        )
    }

    @Test
    fun `loadAutoUpdateAssets returns only positive per-asset intervals`() = runBlocking {
        SagerDatabase.assetDao.create(
            AssetEntity(name = "enabled.srs", autoUpdateDelay = 10, lastUpdated = 0L),
        )
        SagerDatabase.assetDao.create(
            AssetEntity(name = "disabled.srs", autoUpdateDelay = 0, lastUpdated = 0L),
        )

        val loaded = RouteAssetAutoUpdatePlanner.loadAutoUpdateAssets()

        assertEquals(listOf("enabled.srs"), loaded.map { it.name })
    }

    @Test
    fun `synthetic due list includes overdue custom asset from database`() = runBlocking {
        SagerDatabase.assetDao.create(
            AssetEntity(name = "due.srs", autoUpdateDelay = 5, lastUpdated = 0L),
        )
        SagerDatabase.assetDao.create(
            AssetEntity(name = "fresh.srs", autoUpdateDelay = 60, lastUpdated = 50 * 60L),
        )

        val due = RouteAssetAutoUpdateRunner.dueAssets(
            assets = RouteAssetAutoUpdatePlanner.loadAutoUpdateAssets(),
            nowSeconds = 10 * 60L,
        )

        assertEquals(listOf("due.srs"), due.map { it.name })
    }

    @Test
    fun `planner picks earliest due between managed global and custom assets`() = runBlocking {
        val nowSeconds = 10_000L
        SagerDatabase.assetDao.create(
            AssetEntity(name = "custom.srs", autoUpdateDelay = 15, lastUpdated = nowSeconds - 12 * 60),
        )

        val plan = RouteAssetAutoUpdatePlanner.plan(
            globalAutoUpdateDelayMinutes = 60,
            globalLastUpdatedSeconds = nowSeconds - 20 * 60,
            assets = RouteAssetAutoUpdatePlanner.loadAutoUpdateAssets(),
            nowSeconds = nowSeconds,
        )

        assertEquals(15, plan?.repeatIntervalMinutes)
        assertEquals(3 * 60L, plan?.initialDelaySeconds)
    }
}
