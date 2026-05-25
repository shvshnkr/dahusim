package fr.husi.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.husi.GroupOrder
import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.database.CatalogOwnership
import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.database.isCatalogDeletable
import fr.husi.group.SubscriptionFetchProfile
import fr.husi.group.SubscriptionSourceKind
import fr.husi.group.SubscriptionUserAgentPresets
import fr.husi.ktx.applyDefaultValues
import fr.husi.ktx.blankAsNull
import fr.husi.ktx.runOnIoDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
internal data class GroupSettingsUiState(
    val name: String = "",
    val type: Int = GroupType.BASIC,
    val order: Int = GroupOrder.ORIGIN,
    val frontProxy: Long = -1,
    val landingProxy: Long = -1,

    val subscriptionType: Int = SubscriptionType.RAW,
    val subscriptionToken: String = "",
    val subscriptionLink: String = "",
    val subscriptionSourceKind: Int = SubscriptionSourceKind.WEB,
    val subscriptionSourceId: String = "",
    val subscriptionManagedByRemote: Boolean = false,
    val subscriptionFetchProfile: Int = SubscriptionFetchProfile.DEFAULT,
    val subscriptionUaVersionPinned: Boolean = false,
    val subscriptionUaVersionOverride: String = "",
    val subscriptionForceResolve: Boolean = false,
    val subscriptionDeduplication: Boolean = false,
    val subscriptionFilterNotRegex: String = "",
    val subscriptionUpdateWhenConnectedOnly: Boolean = false,
    val subscriptionUserAgent: String = "",
    val subscriptionAutoUpdate: Boolean = false,
    val subscriptionUpdateDelay: Int = 1440,
)

@Stable
internal class GroupSettingsViewModel(
    groupId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupSettingsUiState())
    val uiState = _uiState.asStateFlow()

    private var editingID: Long = 0L
    val isNew get() = editingID == 0L

    private val initialState = MutableStateFlow<GroupSettingsUiState?>(null)
    val isDirty = combine(uiState, initialState) { currentState, initialState ->
        initialState?.let {
            it != currentState
        } ?: false
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    init {
        initialize(groupId)
    }

    fun initialize(id: Long) = viewModelScope.launch {
        editingID = id
        initialState.value = null
        val group = if (isNew) {
            ProxyGroup()
        } else {
            SagerDatabase.groupDao.getById(id).first()!!
        }
        _uiState.update { state ->
            val subscription = group.subscription ?: SubscriptionBean().applyDefaultValues()
            state.copy(
                name = group.name ?: "",
                type = group.type,
                order = group.order,
                frontProxy = group.frontProxy,
                landingProxy = group.landingProxy,

                subscriptionType = subscription.type,
                subscriptionToken = subscription.token,
                subscriptionLink = subscription.link,
                subscriptionSourceKind = SubscriptionSourceKind.inferFromLink(subscription.link),
                subscriptionSourceId = subscription.sourceId,
                subscriptionManagedByRemote = subscription.managedByRemote,
                subscriptionFetchProfile = subscription.fetchProfile,
                subscriptionUaVersionPinned = subscription.userAgentVersionOverride.isNotBlank(),
                subscriptionUaVersionOverride = subscription.userAgentVersionOverride,
                subscriptionForceResolve = subscription.forceResolve,
                subscriptionDeduplication = subscription.deduplication,
                subscriptionFilterNotRegex = subscription.filterNotRegex,
                subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly,
                subscriptionUserAgent = subscription.customUserAgent,
                subscriptionAutoUpdate = subscription.autoUpdate,
                subscriptionUpdateDelay = subscription.autoUpdateDelay,
            ).also {
                initialState.value = it
            }
        }
    }

    fun delete() = runOnIoDispatcher {
        val entity = SagerDatabase.groupDao.getById(editingID).firstOrNull() ?: return@runOnIoDispatcher
        if (!entity.isCatalogDeletable()) return@runOnIoDispatcher
        GroupManager.deleteGroup(editingID)
    }

    fun save() = runOnIoDispatcher {
        if (isNew) {
            GroupManager.createGroup(ProxyGroup().apply { loadFromUiState(uiState.value) })
            return@runOnIoDispatcher
        }
        if (!isDirty.value) return@runOnIoDispatcher
        val entity =
            SagerDatabase.groupDao.getById(editingID).firstOrNull() ?: return@runOnIoDispatcher
        val state = _uiState.value
        val keepUserInfo = entity.type == GroupType.SUBSCRIPTION
                && initialState.value?.type == GroupType.SUBSCRIPTION
                && entity.subscription?.link == state.subscriptionLink
        if (!keepUserInfo) entity.subscription?.apply {
            bytesUsed = -1L
            bytesRemaining = -1L
            expiryDate = -1L
        }
        entity.loadFromUiState(state)
        GroupManager.updateGroup(entity)
    }

    private fun ProxyGroup.loadFromUiState(state: GroupSettingsUiState) {
        name = state.name.blankAsNull() ?: "My Group"
        type = state.type
        order = state.order
        frontProxy = state.frontProxy
        landingProxy = state.landingProxy

        if (type == GroupType.SUBSCRIPTION) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                if (isNew) {
                    managedByRemote = false
                    sourceId = ""
                    catalogOwnership = CatalogOwnership.USER
                }
                type = state.subscriptionType
                token = state.subscriptionToken
                link = state.subscriptionLink
                fetchProfile = state.subscriptionFetchProfile
                userAgentVersionOverride = if (state.subscriptionUaVersionPinned) {
                    state.subscriptionUaVersionOverride
                } else {
                    ""
                }
                forceResolve = state.subscriptionForceResolve
                deduplication = state.subscriptionDeduplication
                filterNotRegex = state.subscriptionFilterNotRegex
                updateWhenConnectedOnly = state.subscriptionUpdateWhenConnectedOnly
                customUserAgent = state.subscriptionUserAgent
                autoUpdate = state.subscriptionAutoUpdate
                autoUpdateDelay = state.subscriptionUpdateDelay
            }
        }
    }

    fun setName(name: String) = viewModelScope.launch {
        _uiState.update {
            it.copy(name = name)
        }
    }

    fun setType(type: Int) = viewModelScope.launch {
        _uiState.update {
            it.copy(type = type)
        }
    }

    fun setOrder(order: Int) = viewModelScope.launch {
        _uiState.update {
            it.copy(order = order)
        }
    }

    fun setFrontProxy(frontProxy: Long) = viewModelScope.launch {
        _uiState.update {
            it.copy(frontProxy = frontProxy)
        }
    }

    fun setLandingProxy(landingProxy: Long) = viewModelScope.launch {
        _uiState.update {
            it.copy(landingProxy = landingProxy)
        }
    }

    fun setSubscriptionType(subscriptionType: Int) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionType = subscriptionType)
        }
    }

    fun setSubscriptionToken(subscriptionToken: String) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionToken = subscriptionToken)
        }
    }

    fun setSubscriptionLink(subscriptionLink: String) = viewModelScope.launch {
        _uiState.update { state ->
            val inferredProfile = if (isNew && state.subscriptionFetchProfile == SubscriptionFetchProfile.DEFAULT) {
                SubscriptionUserAgentPresets.inferFetchProfileForNewLink(subscriptionLink)
            } else {
                state.subscriptionFetchProfile
            }
            state.copy(
                subscriptionLink = subscriptionLink,
                subscriptionSourceKind = SubscriptionSourceKind.inferFromLink(subscriptionLink),
                subscriptionFetchProfile = inferredProfile,
            )
        }
    }

    fun setSubscriptionFetchProfile(subscriptionFetchProfile: Int) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionFetchProfile = subscriptionFetchProfile)
        }
    }

    fun setSubscriptionUaVersionPinned(subscriptionUaVersionPinned: Boolean) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionUaVersionPinned = subscriptionUaVersionPinned)
        }
    }

    fun setSubscriptionUaVersionOverride(subscriptionUaVersionOverride: String) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionUaVersionOverride = subscriptionUaVersionOverride)
        }
    }

    fun setSubscriptionForceResolve(subscriptionForceResolve: Boolean) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionForceResolve = subscriptionForceResolve)
        }
    }

    fun setSubscriptionDeduplication(subscriptionDeduplication: Boolean) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionDeduplication = subscriptionDeduplication)
        }
    }

    fun setSubscriptionFilterNotRegex(subscriptionFilterNotRegex: String) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionFilterNotRegex = subscriptionFilterNotRegex)
        }
    }

    fun setSubscriptionUpdateWhenConnectedOnly(subscriptionUpdateWhenConnectedOnly: Boolean) =
        viewModelScope.launch {
            _uiState.update {
                it.copy(subscriptionUpdateWhenConnectedOnly = subscriptionUpdateWhenConnectedOnly)
            }
        }

    fun setSubscriptionUserAgent(subscriptionUserAgent: String) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionUserAgent = subscriptionUserAgent)
        }
    }

    fun setSubscriptionAutoUpdate(subscriptionAutoUpdate: Boolean) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionAutoUpdate = subscriptionAutoUpdate)
        }
    }

    fun setSubscriptionUpdateDelay(subscriptionUpdateDelay: Int) = viewModelScope.launch {
        _uiState.update {
            it.copy(subscriptionUpdateDelay = subscriptionUpdateDelay)
        }
    }

}
