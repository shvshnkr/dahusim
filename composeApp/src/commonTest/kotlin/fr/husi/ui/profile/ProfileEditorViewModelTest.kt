package fr.husi.ui.profile

import fr.husi.database.DataStore
import fr.husi.test.HusiKoinMainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorViewModelTest : HusiKoinMainDispatcherTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
    }

    @Test
    fun `initialize with same args should reset dirty state`() = runTest(dispatcher.scheduler) {
        val viewModel = ConfigSettingsViewModel()
        backgroundScope.launch {
            viewModel.isDirty.collect {}
        }
        advanceUntilIdle()

        viewModel.initialize(editingId = -1L, isSubscription = false)
        advanceUntilIdle()
        assertFalse(viewModel.isDirty.value)

        viewModel.setName("dirty")
        advanceUntilIdle()
        assertTrue(viewModel.isDirty.value)

        viewModel.initialize(editingId = -1L, isSubscription = false)
        advanceUntilIdle()
        assertFalse(viewModel.isDirty.value)
        assertEquals("", viewModel.uiState.value.name)
    }
}
