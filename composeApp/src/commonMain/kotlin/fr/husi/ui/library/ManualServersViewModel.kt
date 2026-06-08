package fr.husi.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.husi.Key
import fr.husi.database.DataStore
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.ktx.Logs
import fr.husi.ktx.onIoDispatcher
import fr.husi.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class ManualServersUiState(
    val rows: List<ManualServersPolicy.ProfileRow> = emptyList(),
    val chips: List<ManualServersPolicy.GroupChip> = emptyList(),
    val selectedChipGroupId: Long? = null,
    val selectedProfileId: Long = 0L,
    val ungroupedLabel: String = "",
    val hiddenProfiles: Int = 0,
)

@Stable
@OptIn(ExperimentalCoroutinesApi::class)
class ManualServersViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ManualServersUiState())
    val uiState = _uiState.asStateFlow()

    private val selectedChipGroupId = MutableStateFlow<Long?>(null)
    private val ungroupedLabel = MutableStateFlow("")
    private val hiddenProfileAccess = Mutex()
    private val hiddenProfiles = LinkedHashMap<Long, Long>()
    private var deleteTimer: Job? = null
    private var lastBuiltState = ManualServersUiState()

    fun setUngroupedLabel(label: String) {
        ungroupedLabel.value = label
    }

    fun selectChip(groupId: Long?) {
        selectedChipGroupId.value = groupId
    }

    fun undoableRemove(profileId: Long, groupId: Long) = viewModelScope.launch {
        hiddenProfileAccess.withLock {
            hiddenProfiles[profileId] = groupId
            _uiState.update { state ->
                state.copy(
                    rows = state.rows.filter { it.profile.id != profileId },
                    hiddenProfiles = hiddenProfiles.size,
                )
            }
        }
        startDeleteTimer()
    }

    fun undo() = viewModelScope.launch {
        deleteTimer?.cancel()
        deleteTimer = null
        hiddenProfileAccess.withLock {
            hiddenProfiles.clear()
        }
        _uiState.value = lastBuiltState.copy(hiddenProfiles = 0)
    }

    fun commit() = runOnDefaultDispatcher {
        deleteTimer?.cancel()
        deleteTimer = null
        val pending = hiddenProfileAccess.withLock {
            val pending = hiddenProfiles.toMap()
            hiddenProfiles.clear()
            pending
        }
        if (pending.isEmpty()) return@runOnDefaultDispatcher
        Logs.d("ManualServers: deleting ${pending.size} profile(s)")
        onIoDispatcher {
            pending.entries.groupBy({ it.value }, { it.key }).forEach { (groupId, profileIds) ->
                ProfileManager.deleteProfiles(groupId, profileIds)
            }
        }
    }

    private fun startDeleteTimer() {
        deleteTimer?.cancel()
        deleteTimer = viewModelScope.launch {
            delay(5000)
            commit()
        }
    }

    init {
        viewModelScope.launch {
            combine(
                SagerDatabase.groupDao.allGroups(),
                selectedChipGroupId,
                DataStore.configurationStore.longFlow(Key.PROFILE_ID, 0L),
                ungroupedLabel,
            ) { groups, chipId, selectedProfileId, ungrouped ->
                ManualServersBuildInput(groups, chipId, selectedProfileId, ungrouped)
            }.flatMapLatest { input ->
                val manualGroups = ManualServersPolicy.manualGroups(input.groups)
                if (manualGroups.isEmpty()) {
                    flowOf(
                        ManualServersUiState(
                            selectedChipGroupId = input.chipId,
                            selectedProfileId = input.selectedProfileId,
                            ungroupedLabel = input.ungroupedLabel,
                        ),
                    )
                } else {
                    combine(
                        manualGroups.map { group ->
                            SagerDatabase.proxyDao.getByGroup(group.id)
                                .map { profiles -> group.id to profiles }
                        },
                    ) { pairs ->
                        val profilesByGroup = pairs.toList().toMap()
                        val allRows = ManualServersPolicy.buildRows(
                            input.groups,
                            profilesByGroup,
                            input.ungroupedLabel,
                        )
                        ManualServersUiState(
                            rows = ManualServersPolicy.filterRows(allRows, input.chipId),
                            chips = ManualServersPolicy.buildChips(
                                input.groups,
                                profilesByGroup,
                                input.ungroupedLabel,
                            ),
                            selectedChipGroupId = input.chipId,
                            selectedProfileId = input.selectedProfileId,
                            ungroupedLabel = input.ungroupedLabel,
                        )
                    }
                }
            }.collect { state ->
                lastBuiltState = state
                val hiddenIds = hiddenProfileAccess.withLock { hiddenProfiles.keys.toSet() }
                _uiState.value = state.copy(
                    rows = state.rows.filter { it.profile.id !in hiddenIds },
                    hiddenProfiles = hiddenIds.size,
                )
            }
        }
    }

    private data class ManualServersBuildInput(
        val groups: List<ProxyGroup>,
        val chipId: Long?,
        val selectedProfileId: Long,
        val ungroupedLabel: String,
    )
}
