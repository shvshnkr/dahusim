package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.GroupOrigin
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.test.HusiKoinMainDispatcherTest
import fr.husi.ui.library.ManualServersViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryManualFlatListJourneyTest : HusiKoinMainDispatcherTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
    }

    @Test
    fun manualTabListsUserOwnedProfilesOnly() = runTest(dispatcher.scheduler) {
        val userGroup = GroupManager.createGroup(
            ProxyGroup(name = "Journey folder", type = GroupType.BASIC).apply {
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

        val userProxy = proxy(groupId = userGroup.id)
        userProxy.id = SagerDatabase.proxyDao.addProxy(userProxy)
        val builtinProxy = proxy(groupId = builtinGroup.id)
        builtinProxy.id = SagerDatabase.proxyDao.addProxy(builtinProxy)

        val viewModel = ManualServersViewModel()
        viewModel.setUngroupedLabel("No folder")
        backgroundScope.launch {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.rows.isNotEmpty() }
        assertEquals(1, state.rows.size)
        assertEquals(userProxy.id, state.rows.single().profile.id)
        assertEquals(userGroup.id, state.rows.single().groupId)
        assertTrue(state.chips.any { it.groupId == userGroup.id })
        assertTrue(state.chips.none { it.groupId == builtinGroup.id })
    }

    private fun proxy(groupId: Long): ProxyEntity =
        ProxyEntity(groupId = groupId, type = ProxyEntity.TYPE_TROJAN).apply {
            trojanBean = TrojanBean()
        }
}
