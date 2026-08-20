package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.database.CatalogOwnership
import fr.husi.database.ConnectPoolRole
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.GroupOrigin
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.ui.GroupItemUiState
import fr.husi.ui.library.LibraryRoleFilter
import fr.husi.ui.library.LibrarySegment
import fr.husi.ui.library.librarySegmentCounts
import fr.husi.ui.library.matchesLibraryQuery
import fr.husi.ui.library.matchesRoleFilter
import fr.husi.ui.library.matchesSegment
import fr.husi.ui.library.subscriptionOutdatedDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Library list is driven by segment + role filters and search over real group rows:
 * segment tabs show live counts, [Все][WL][OPEN] filters subscriptions by
 * subscription.connectPoolRole, search matches displayName/link.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFilteringJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        Dispatchers.setMain(Dispatchers.Unconfined)
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
    }

    override suspend fun postStopKoin() {
        Dispatchers.resetMain()
        super.postStopKoin()
    }

    private suspend fun subscriptionGroup(name: String, link: String, role: Int): ProxyGroup {
        val bean = SubscriptionBean().apply {
            this.link = link
            connectPoolRole = role
            catalogOwnership = CatalogOwnership.USER
            lastUpdated = 5
        }
        return GroupManager.createGroup(
            ProxyGroup(name = name, type = GroupType.SUBSCRIPTION).apply {
                subscription = bean
                origin = GroupOrigin.USER
            },
            notifySubscriptionScheduler = false,
        )
    }

    @Test
    fun roleFilterSearchAndSegmentCountsOverRealGroups() = runBlocking {
        val wlGroup = subscriptionGroup("Wl Feed", "https://wl.example.com/sub", ConnectPoolRole.WL)
        val openGroup = subscriptionGroup("Open Feed", "https://open.example.com/sub", ConnectPoolRole.OPEN)
        subscriptionGroup("Plain Feed", "https://plain.example.com/sub", ConnectPoolRole.ANY)
        val manualGroup = GroupManager.createGroup(
            ProxyGroup(name = "Manual folder", type = GroupType.BASIC).apply {
                origin = GroupOrigin.USER
            },
            notifySubscriptionScheduler = false,
        )
        val builtinGroup = GroupManager.createGroup(
            ProxyGroup(name = "Built-in relay", type = GroupType.BASIC).apply {
                origin = GroupOrigin.BUILTIN
            },
            notifySubscriptionScheduler = false,
        )
        val items = SagerDatabase.groupDao.allGroups().first().map { group ->
            GroupItemUiState(group = group, counts = 0L)
        }
        assertEquals(5, items.size)

        val counts = librarySegmentCounts(items)
        assertEquals(3, counts[LibrarySegment.Subscriptions])
        assertEquals(1, counts[LibrarySegment.Manual])
        assertEquals(1, counts[LibrarySegment.System])

        val subscriptions = items.filter { it.matchesSegment(LibrarySegment.Subscriptions) }
        assertEquals(3, subscriptions.size)
        assertEquals(
            setOf(wlGroup.id),
            subscriptions.filter { it.matchesRoleFilter(LibraryRoleFilter.Wl) }
                .map { it.group.id }
                .toSet(),
        )
        assertEquals(
            setOf(openGroup.id),
            subscriptions.filter { it.matchesRoleFilter(LibraryRoleFilter.Open) }
                .map { it.group.id }
                .toSet(),
        )
        assertEquals(
            setOf(manualGroup.id),
            items.filter { it.matchesSegment(LibrarySegment.Manual) }.map { it.group.id }.toSet(),
        )
        assertEquals(
            setOf(builtinGroup.id),
            items.filter { it.matchesSegment(LibrarySegment.System) }.map { it.group.id }.toSet(),
        )

        assertTrue(items.filter { it.matchesLibraryQuery("open") }
            .all { it.group.id in setOf(openGroup.id) })
        assertTrue(items.filter { it.matchesLibraryQuery("wl.example.com") }
            .all { it.group.id == wlGroup.id })
        assertEquals(items.size, items.count { it.matchesLibraryQuery("  ") })
        assertTrue(items.none { it.matchesLibraryQuery("no-such-feed") })
    }

    @Test
    fun outdatedDerivationFromLastUpdated() {
        val now = 1_000_000L
        assertNull(subscriptionOutdatedDays(0, now))
        assertNull(subscriptionOutdatedDays(-1, now))
        assertEquals(0L, subscriptionOutdatedDays(now.toInt(), now))
        assertEquals(5L, subscriptionOutdatedDays(now.toInt() - 5 * 86_400, now))
    }
}
