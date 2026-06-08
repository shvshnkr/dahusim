package fr.husi.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.husi.GroupType
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.isUserOwnedLibraryItem
import fr.husi.ktx.onIoDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object ManualFolderFilter {
    val All: Long? = null
    const val UngroupedOnly: Long = -2L
}

@Immutable
data class ManualServerItem(
    val profile: ProxyEntity,
    val group: ProxyGroup,
)

@Immutable
data class ManualFolderChip(
    val filterId: Long?,
    val label: String,
)

@Immutable
data class ManualServersUiState(
    val items: List<ManualServerItem> = emptyList(),
    val chips: List<ManualFolderChip> = emptyList(),
    val selectedFilter: Long? = ManualFolderFilter.All,
    val searchQuery: String = "",
)

@Stable
class ManualServersViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ManualServersUiState())
    val uiState = _uiState.asStateFlow()

    private var allItems: List<ManualServerItem> = emptyList()
    private var folderGroups: List<ProxyGroup> = emptyList()

    init {
        viewModelScope.launch {
            SagerDatabase.groupDao.allGroups().collectLatest { groups ->
                val userGroups = groups.filter { it.type == GroupType.BASIC && it.isUserOwnedLibraryItem() }
                folderGroups = userGroups
                val userGroupIds = userGroups.map { it.id }.toSet()
                val proxies = onIoDispatcher { SagerDatabase.proxyDao.getAll() }
                allItems = proxies
                    .filter { it.groupId in userGroupIds }
                    .mapNotNull { profile ->
                        val group = userGroups.find { it.id == profile.groupId } ?: return@mapNotNull null
                        ManualServerItem(profile, group)
                    }
                    .sortedWith(
                        compareBy<ManualServerItem> { it.group.order }
                            .thenBy { it.profile.userOrder }
                            .thenBy { it.profile.displayName() },
                    )
                rebuildVisible()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query.trim()) }
        rebuildVisible()
    }

    fun setFolderFilter(filterId: Long?) {
        _uiState.update { it.copy(selectedFilter = filterId) }
        rebuildVisible()
    }

    private fun rebuildVisible() {
        val state = _uiState.value
        val filtered = allItems.filter { item ->
            when (state.selectedFilter) {
                ManualFolderFilter.All -> true
                ManualFolderFilter.UngroupedOnly -> item.group.ungrouped
                else -> item.group.id == state.selectedFilter
            }
        }.filter { item ->
            val q = state.searchQuery
            if (q.isBlank()) true
            else item.profile.displayName().contains(q, ignoreCase = true) ||
                item.group.name.orEmpty().contains(q, ignoreCase = true)
        }
        val chips = buildList {
            add(ManualFolderChip(ManualFolderFilter.All, "All"))
            folderGroups.find { it.ungrouped }?.let {
                add(ManualFolderChip(ManualFolderFilter.UngroupedOnly, it.displayName()))
            }
            folderGroups.filter { !it.ungrouped }.forEach { group ->
                add(ManualFolderChip(group.id, group.displayName()))
            }
        }
        _uiState.update {
            it.copy(items = filtered, chips = chips)
        }
    }

    fun userFolderGroups(): List<ProxyGroup> =
        folderGroups.filter { !it.ungrouped }
}
