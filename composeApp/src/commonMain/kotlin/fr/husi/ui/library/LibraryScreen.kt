package fr.husi.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import com.ernestoyaquello.dragdropswipelazycolumn.DragDropSwipeLazyColumn
import com.ernestoyaquello.dragdropswipelazycolumn.DraggableSwipeableItem
import com.ernestoyaquello.dragdropswipelazycolumn.DraggableSwipeableItemScope
import com.ernestoyaquello.dragdropswipelazycolumn.config.DraggableSwipeableItemColors
import com.ernestoyaquello.dragdropswipelazycolumn.state.DragDropSwipeLazyColumnState
import com.ernestoyaquello.dragdropswipelazycolumn.state.rememberDragDropSwipeLazyColumnState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.husi.GroupType
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.compose.BoxedVerticalScrollbar
import fr.husi.ui.MainTopNavigationIcon
import fr.husi.compose.SagerFab
import fr.husi.compose.SheetActionRow
import fr.husi.compose.SimpleIconButton
import fr.husi.compose.StatsBar
import fr.husi.compose.TextButton
import fr.husi.compose.material3.Button
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.resources.copy_success
import fr.husi.resources.library_segment_manual
import fr.husi.resources.library_segment_subscriptions
import fr.husi.resources.library_segment_system
import fr.husi.resources.share_subscription
import fr.husi.compose.rememberScrollHideState
import fr.husi.compose.setPlainText
import fr.husi.compose.withNavigation
import fr.husi.database.GroupOrigin
import fr.husi.database.isGroupDeletable
import fr.husi.database.isSystemLibraryItem
import fr.husi.database.isUserOwnedLibraryItem
import fr.husi.database.resolvedOrigin
import fr.husi.ktx.blankAsNull
import fr.husi.ktx.formatTime
import fr.husi.ktx.showAndDismissOld
import fr.husi.libcore.Libcore
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.cancel
import fr.husi.resources.confirm
import fr.husi.resources.delete
import fr.husi.resources.edit
import fr.husi.resources.group_create
import fr.husi.resources.library_manage_folders
import fr.husi.resources.group_status_empty
import fr.husi.resources.group_status_empty_subscription
import fr.husi.resources.group_status_proxies
import fr.husi.resources.group_update
import androidx.compose.runtime.mutableIntStateOf
import fr.husi.resources.menu
import fr.husi.resources.menu_library
import fr.husi.resources.more_vert
import fr.husi.resources.ok
import fr.husi.resources.playlist_add
import fr.husi.resources.qr_code
import fr.husi.resources.removed
import fr.husi.resources.share_qr_nfc
import fr.husi.resources.share
import fr.husi.resources.subscription_expire
import fr.husi.resources.subscription_traffic
import fr.husi.resources.subscription_used
import fr.husi.resources.undo
import fr.husi.resources.update
import fr.husi.resources.update_all_subscription
import fr.husi.resources.action_open
import fr.husi.resources.drag_indicator
import fr.husi.resources.library_add_fab
import fr.husi.resources.library_empty_manual
import fr.husi.resources.library_empty_manual_action
import fr.husi.resources.library_empty_subscriptions
import fr.husi.resources.library_empty_subscriptions_action
import fr.husi.resources.library_empty_system
import fr.husi.resources.library_empty_system_action
import fr.husi.resources.library_group_origin_builtin
import fr.husi.resources.library_reorder
import fr.husi.resources.library_reorder_done
import fr.husi.resources.no_proxies_found_in_file
import fr.husi.ktx.onIoDispatcher
import fr.husi.ktx.runOnIoDispatcher
import fr.husi.ui.GroupItemUiState
import fr.husi.ui.configuration.ConfigurationScreenViewModel
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.toImmutableList
import fr.husi.ui.GroupScreenViewModel
import fr.husi.ui.MainViewModel
import fr.husi.ui.MainViewModelAlertDialog
import fr.husi.ui.MainViewModelUiEvent
import fr.husi.ui.NavRoutes
import fr.husi.ui.getStringOrRes
import fr.husi.compose.QRCodeDialog
import io.github.oikvpqya.compose.fastscroller.material3.defaultMaterialScrollbarStyle
import io.github.oikvpqya.compose.fastscroller.rememberScrollbarAdapter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

enum class LibrarySegment {
    Subscriptions,
    Manual,
    System,
}

fun GroupItemUiState.matchesSegment(segment: LibrarySegment): Boolean {
    val group = this.group
    return when (segment) {
        LibrarySegment.Subscriptions ->
            group.type == GroupType.SUBSCRIPTION && group.isUserOwnedLibraryItem()

        LibrarySegment.System -> group.isSystemLibraryItem()

        LibrarySegment.Manual ->
            group.type == GroupType.BASIC && group.isUserOwnedLibraryItem()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    viewModel: GroupScreenViewModel = viewModel { GroupScreenViewModel() },
    onDrawerClick: () -> Unit,
    openGroup: (Long) -> Unit,
    openGroupSettings: (Long) -> Unit,
    openProfileEditor: (NavRoutes.ProfileEditor) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    DisposableEffect(Unit) {
        onDispose { viewModel.commit() }
    }

    var segmentIndex by rememberSaveable { mutableIntStateOf(0) }
    val segment = LibrarySegment.entries[segmentIndex.coerceIn(LibrarySegment.entries.indices)]
    var showUpdateAll by remember { mutableStateOf(false) }
    var qrDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteGroupConfirm by remember { mutableStateOf<Long?>(null) }
    var showAlertDialog by remember { mutableStateOf<MainViewModelUiEvent.AlertDialog?>(null) }
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    var showManageFolders by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    val configImportVm = viewModel(key = "library-file-import") { ConfigurationScreenViewModel() }
    val manualViewModel = viewModel(key = "library-manual") { ManualServersViewModel() }
    val manualUiState by manualViewModel.uiState.collectAsStateWithLifecycle()
    val scannerAction = rememberLibraryScannerAction()
    val importFile = rememberFilePickerLauncher { file ->
        if (file != null) {
            configImportVm.importFile(
                file = file,
                onProxiesFound = { proxies ->
                    runOnIoDispatcher {
                        mainViewModel.importProfile(proxies)
                    }
                },
                onSubscriptionFound = { uri ->
                    mainViewModel.importSubscription(uri)
                },
                onNoProxies = {
                    scope.launch {
                        snackbarState.showSnackbar(
                            message = resolveRepository().getString(Res.string.no_proxies_found_in_file),
                            actionLabel = resolveRepository().getString(Res.string.ok),
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onError = { message ->
                    scope.launch {
                        snackbarState.showSnackbar(
                            message = message,
                            actionLabel = resolveRepository().getString(Res.string.ok),
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filteredGroups = remember(uiState.groups, segment) {
        uiState.groups.filter { it.matchesSegment(segment) }
    }

    LaunchedEffect(uiState.hiddenGroups) {
        if (uiState.hiddenGroups > 0) {
            val result = snackbarState.showAndDismissOld(
                message = resolveRepository().getPluralString(
                    Res.plurals.removed,
                    uiState.hiddenGroups,
                    uiState.hiddenGroups,
                ),
                actionLabel = resolveRepository().getString(Res.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undo()
            }
        }
    }

    LaunchedEffect(manualUiState.hiddenProfiles) {
        if (manualUiState.hiddenProfiles > 0) {
            val result = snackbarState.showAndDismissOld(
                message = resolveRepository().getPluralString(
                    Res.plurals.removed,
                    manualUiState.hiddenProfiles,
                    manualUiState.hiddenProfiles,
                ),
                actionLabel = resolveRepository().getString(Res.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                manualViewModel.undo()
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val windowInsets = WindowInsets.safeDrawing
    val listState = rememberLazyListState()
    val dragDropListState = rememberDragDropSwipeLazyColumnState()
    val scrollHideVisible by rememberScrollHideState(
        if (reorderMode) dragDropListState.lazyListState else listState,
    )
    val serviceStatus by BackendState.status.collectAsStateWithLifecycle()
    val isRefreshing = uiState.groups.any { it.isUpdating }
    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.menu_library)) },
                navigationIcon = {
                    MainTopNavigationIcon(
                        useBack = false,
                        onClick = onDrawerClick,
                        hideOnAndroidBottomNavRoot = true,
                    )
                },
                actions = {
                    if (filteredGroups.isNotEmpty() && segment != LibrarySegment.Manual) {
                        TextButton(
                            stringResource(
                                if (reorderMode) {
                                    Res.string.library_reorder_done
                                } else {
                                    Res.string.library_reorder
                                },
                            ),
                        ) {
                            reorderMode = !reorderMode
                        }
                    }
                    if (segment == LibrarySegment.Subscriptions) {
                        SimpleIconButton(
                            imageVector = vectorResource(Res.drawable.update),
                            contentDescription = stringResource(Res.string.update_all_subscription),
                            onClick = { showUpdateAll = true },
                        )
                    }
                },
                windowInsets = windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(visible = scrollHideVisible && !reorderMode) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddSheet = true },
                        icon = {
                            Icon(
                                imageVector = vectorResource(Res.drawable.playlist_add),
                                contentDescription = null,
                            )
                        },
                        text = { Text(stringResource(Res.string.library_add_fab)) },
                    )
                }
                SagerFab(
                    visible = scrollHideVisible,
                    state = serviceStatus.state,
                    showSnackbar = { message ->
                        scope.launch {
                            snackbarState.showSnackbar(
                                message = getStringOrRes(message),
                                actionLabel = resolveRepository().getString(Res.string.ok),
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (serviceStatus.state == ServiceState.Connected) {
                StatsBar(
                    status = serviceStatus,
                    visible = scrollHideVisible,
                    mainViewModel = mainViewModel,
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = innerPadding.withNavigation()
        val onPullRefresh = {
            if (segment == LibrarySegment.Subscriptions) {
                mainViewModel.updateAllSubscriptionGroups()
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            val listModifier = Modifier.weight(1f).fillMaxHeight()
            if (segment == LibrarySegment.Subscriptions) {
                PullToRefreshBox(
                    modifier = listModifier,
                    isRefreshing = isRefreshing,
                    onRefresh = onPullRefresh,
                    state = pullRefreshState,
                ) {
                    LibraryGroupList(
                        segment = segment,
                        onSegmentSelect = { segmentIndex = it.ordinal },
                        filteredGroups = filteredGroups,
                        reorderMode = reorderMode,
                        listState = listState,
                        dragDropListState = dragDropListState,
                        contentPadding = contentPadding,
                        mainViewModel = mainViewModel,
                        viewModel = viewModel,
                        openGroup = openGroup,
                        openGroupSettings = openGroupSettings,
                        onDeleteRequest = { deleteGroupConfirm = it },
                        showQRDialog = { url, name -> qrDialogData = url to name },
                        onAdd = { showAddSheet = true },
                        onRefresh = onPullRefresh,
                        snackbar = { message ->
                            scope.launch {
                                snackbarState.showSnackbar(
                                    message = message,
                                    actionLabel = resolveRepository().getString(Res.string.ok),
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                    )
                }
            } else if (segment == LibrarySegment.Manual && !reorderMode) {
                ManualServersContent(
                    modifier = listModifier,
                    contentPadding = contentPadding,
                    segment = segment,
                    onSegmentSelect = { segmentIndex = it.ordinal },
                    configViewModel = configImportVm,
                    onAdd = { showAddSheet = true },
                    onManageFolders = { showManageFolders = true },
                    onOpenProfileEditor = openProfileEditor,
                    manualViewModel = manualViewModel,
                )
            } else {
                Box(modifier = listModifier) {
                    LibraryGroupList(
                        segment = segment,
                        onSegmentSelect = { segmentIndex = it.ordinal },
                        filteredGroups = filteredGroups,
                        reorderMode = reorderMode,
                        listState = listState,
                        dragDropListState = dragDropListState,
                        contentPadding = contentPadding,
                        mainViewModel = mainViewModel,
                        viewModel = viewModel,
                        openGroup = openGroup,
                        openGroupSettings = openGroupSettings,
                        onDeleteRequest = { deleteGroupConfirm = it },
                        showQRDialog = { url, name -> qrDialogData = url to name },
                        onAdd = { showAddSheet = true },
                        onRefresh = onPullRefresh,
                        snackbar = { message ->
                            scope.launch {
                                snackbarState.showSnackbar(
                                    message = message,
                                    actionLabel = resolveRepository().getString(Res.string.ok),
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                    )
                }
            }
            val scrollState = if (reorderMode) dragDropListState.lazyListState else listState
            BoxedVerticalScrollbar(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxHeight(),
                adapter = rememberScrollbarAdapter(scrollState = scrollState),
                style = defaultMaterialScrollbarStyle().copy(thickness = 12.dp),
            )
        }
    }

    LibraryAddSheet(
        visible = showAddSheet,
        onDismiss = { showAddSheet = false },
        mainViewModel = mainViewModel,
        onOpenGroupSettings = openGroupSettings,
        onImportFile = { importFile.launch() },
        onScan = scannerAction,
    )

    if (showManageFolders) {
        val manageSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val folderGroups = uiState.groups.filter {
            it.matchesSegment(LibrarySegment.Manual) && !it.group.ungrouped
        }
        ModalBottomSheet(
            onDismissRequest = { showManageFolders = false },
            sheetState = manageSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.library_manage_folders),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                folderGroups.forEach { item ->
                    SheetActionRow(
                        text = item.group.displayName(),
                        onClick = {
                            showManageFolders = false
                            openGroupSettings(item.group.id)
                        },
                    )
                }
                SheetActionRow(
                    text = stringResource(Res.string.group_create),
                    leadingIcon = { Icon(vectorResource(Res.drawable.playlist_add), null) },
                    onClick = {
                        showManageFolders = false
                        openGroupSettings(0L)
                    },
                )
            }
        }
    }

    if (showUpdateAll) {
        AlertDialog(
            onDismissRequest = { showUpdateAll = false },
            confirmButton = {
                TextButton(stringResource(Res.string.ok)) {
                    mainViewModel.updateAllSubscriptionGroups()
                    showUpdateAll = false
                }
            },
            dismissButton = {
                TextButton(stringResource(Res.string.cancel)) { showUpdateAll = false }
            },
            icon = { Icon(vectorResource(Res.drawable.update), null) },
            title = { Text(stringResource(Res.string.confirm)) },
            text = { Text(stringResource(Res.string.update_all_subscription)) },
        )
    }

    qrDialogData?.let { (url, name) ->
        QRCodeDialog(
            url = url,
            name = name,
            onDismiss = { qrDialogData = null },
            showSnackbar = { message ->
                scope.launch {
                    snackbarState.showSnackbar(
                        message = message,
                        actionLabel = resolveRepository().getString(Res.string.ok),
                        duration = SnackbarDuration.Short,
                    )
                }
            },
        )
    }

    deleteGroupConfirm?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteGroupConfirm = null },
            confirmButton = {
                TextButton(stringResource(Res.string.ok)) {
                    viewModel.undoableRemove(id)
                    deleteGroupConfirm = null
                }
            },
            dismissButton = {
                TextButton(stringResource(Res.string.cancel)) { deleteGroupConfirm = null }
            },
            icon = { Icon(vectorResource(Res.drawable.delete), null) },
            title = { Text(stringResource(Res.string.confirm)) },
            text = { Text(stringResource(Res.string.delete)) },
        )
    }

    LaunchedEffect(Unit) {
        mainViewModel.uiEvent.collect { event ->
            when (event) {
                is MainViewModelUiEvent.Snackbar -> scope.launch {
                    snackbarState.showSnackbar(
                        message = getStringOrRes(event.message),
                        actionLabel = resolveRepository().getString(Res.string.ok),
                        duration = SnackbarDuration.Short,
                    )
                }

                is MainViewModelUiEvent.SnackbarWithAction -> scope.launch {
                    val result = snackbarState.showSnackbar(
                        message = getStringOrRes(event.message),
                        actionLabel = getStringOrRes(event.actionLabel),
                        duration = SnackbarDuration.Short,
                    )
                    event.callback(result)
                }

                is MainViewModelUiEvent.AlertDialog -> showAlertDialog = event
            }
        }
    }

    showAlertDialog?.let { dialog ->
        MainViewModelAlertDialog(dialog) { showAlertDialog = null }
    }
}

@Composable
private fun LibraryGroupList(
    segment: LibrarySegment,
    onSegmentSelect: (LibrarySegment) -> Unit,
    filteredGroups: List<GroupItemUiState>,
    reorderMode: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    dragDropListState: DragDropSwipeLazyColumnState,
    contentPadding: PaddingValues,
    mainViewModel: MainViewModel,
    viewModel: GroupScreenViewModel,
    openGroup: (Long) -> Unit,
    openGroupSettings: (Long) -> Unit,
    onDeleteRequest: (Long) -> Unit,
    showQRDialog: (String, String) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    snackbar: suspend (String) -> Unit,
) {
    if (filteredGroups.isEmpty() && !reorderMode) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("segments") {
                LibrarySegmentRow(
                    selected = segment,
                    onSelect = onSegmentSelect,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item("empty") {
                LibraryEmptyState(
                    segment = segment,
                    onAdd = onAdd,
                    onRefresh = onRefresh,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            }
        }
        return
    }

    if (reorderMode) {
        Column(modifier = Modifier.fillMaxSize()) {
            LibrarySegmentRow(
                selected = segment,
                onSelect = onSegmentSelect,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DragDropSwipeLazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = dragDropListState,
                items = filteredGroups.toImmutableList(),
                key = { it.group.id },
                contentType = { 0 },
                userScrollEnabled = true,
                contentPadding = contentPadding,
                onIndicesChangedViaDragAndDrop = { viewModel.submitSegmentReorder(segment, it) },
            ) { _, groupState ->
                DraggableSwipeableItem(
                    modifier = Modifier.animateDraggableSwipeableItem(),
                    colors = DraggableSwipeableItemColors.createRemembered(
                        containerBackgroundColor = Color.Transparent,
                        containerBackgroundColorWhileDragged = Color.Transparent,
                    ),
                ) {
                    LibraryGroupCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        mainViewModel = mainViewModel,
                        state = groupState,
                        showDragHandle = true,
                        dragHandleModifier = Modifier.dragDropModifier(),
                        onOpen = { openGroup(groupState.group.id) },
                        onUpdate = { mainViewModel.updateSubscriptionGroup(groupState.group) },
                        openGroupSettings = openGroupSettings,
                        onDeleteRequest = { onDeleteRequest(groupState.group.id) },
                        showQRDialog = showQRDialog,
                        snackbar = snackbar,
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        item("segments") {
            LibrarySegmentRow(
                selected = segment,
                onSelect = onSegmentSelect,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(filteredGroups, key = { it.group.id }) { groupState ->
            LibraryGroupCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                mainViewModel = mainViewModel,
                state = groupState,
                onOpen = { openGroup(groupState.group.id) },
                onUpdate = { mainViewModel.updateSubscriptionGroup(groupState.group) },
                openGroupSettings = openGroupSettings,
                onDeleteRequest = { onDeleteRequest(groupState.group.id) },
                showQRDialog = showQRDialog,
                snackbar = snackbar,
            )
        }
        item("fab_spacer") {
            Spacer(Modifier.size(88.dp))
        }
    }
}

@Composable
internal fun LibraryEmptyState(
    segment: LibrarySegment,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    titleRes: org.jetbrains.compose.resources.StringResource? = null,
    actionRes: org.jetbrains.compose.resources.StringResource? = null,
) {
    val (title, action) = if (titleRes != null && actionRes != null) {
        titleRes to actionRes
    } else {
        when (segment) {
            LibrarySegment.Subscriptions ->
                Res.string.library_empty_subscriptions to Res.string.library_empty_subscriptions_action

            LibrarySegment.Manual ->
                Res.string.library_empty_manual to Res.string.library_empty_manual_action

            LibrarySegment.System ->
                Res.string.library_empty_system to Res.string.library_empty_system_action
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(action),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (segment != LibrarySegment.System) {
            Button(onClick = onAdd) {
                Text(stringResource(Res.string.library_add_fab))
            }
        }
        if (segment == LibrarySegment.Subscriptions) {
            TextButton(stringResource(Res.string.update_all_subscription), onRefresh)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySegmentRow(
    selected: LibrarySegment,
    onSelect: (LibrarySegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == LibrarySegment.Subscriptions,
            onClick = { onSelect(LibrarySegment.Subscriptions) },
            label = { Text(stringResource(Res.string.library_segment_subscriptions)) },
        )
        FilterChip(
            selected = selected == LibrarySegment.Manual,
            onClick = { onSelect(LibrarySegment.Manual) },
            label = { Text(stringResource(Res.string.library_segment_manual)) },
        )
        FilterChip(
            selected = selected == LibrarySegment.System,
            onClick = { onSelect(LibrarySegment.System) },
            label = { Text(stringResource(Res.string.library_segment_system)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryGroupCard(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    state: GroupItemUiState,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    openGroupSettings: (Long) -> Unit,
    onDeleteRequest: () -> Unit,
    showQRDialog: (url: String, name: String) -> Unit,
    snackbar: suspend (message: String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val group = state.group
    var showOptionsSheet by remember { mutableStateOf(false) }
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (state.isUpdating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDragHandle) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.drag_indicator),
                        contentDescription = stringResource(Res.string.library_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            .then(dragHandleModifier),
                    )
                } else {
                    Spacer(
                        modifier = Modifier
                            .size(10.dp)
                            .padding(end = 6.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = proxyCountLabel(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = groupSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.action_open))
                }
                if (group.type == GroupType.SUBSCRIPTION) {
                    SimpleIconButton(
                        imageVector = vectorResource(Res.drawable.update),
                        contentDescription = stringResource(Res.string.group_update),
                        enabled = !state.isUpdating,
                        onClick = onUpdate,
                    )
                }
                SimpleIconButton(
                    imageVector = vectorResource(Res.drawable.more_vert),
                    contentDescription = stringResource(Res.string.menu),
                    onClick = { showOptionsSheet = true },
                )
            }
        }
    }

    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = optionsSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.subscription?.link?.blankAsNull()?.let { link ->
                    SheetActionRow(
                        text = stringResource(Res.string.share_subscription),
                        leadingIcon = { Icon(vectorResource(Res.drawable.share), null) },
                        onClick = {
                            scope.launch {
                                clipboard.setPlainText(link)
                                snackbar(resolveRepository().getString(Res.string.copy_success))
                            }
                            showOptionsSheet = false
                        },
                    )
                    SheetActionRow(
                        text = stringResource(Res.string.share_qr_nfc),
                        leadingIcon = { Icon(vectorResource(Res.drawable.qr_code), null) },
                        onClick = {
                            showQRDialog(link, group.displayName())
                            showOptionsSheet = false
                        },
                    )
                }
                if (!group.ungrouped && group.isGroupDeletable()) {
                    SheetActionRow(
                        text = stringResource(Res.string.edit),
                        leadingIcon = { Icon(vectorResource(Res.drawable.edit), null) },
                        onClick = {
                            openGroupSettings(group.id)
                            showOptionsSheet = false
                        },
                    )
                }
                if (group.isGroupDeletable()) {
                    SheetActionRow(
                        text = stringResource(Res.string.delete),
                        leadingIcon = { Icon(vectorResource(Res.drawable.delete), null) },
                        textColor = MaterialTheme.colorScheme.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = {
                            showOptionsSheet = false
                            onDeleteRequest()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun proxyCountLabel(state: GroupItemUiState): String {
    val counts = state.counts
    return when (state.group.type) {
        GroupType.BASIC -> if (counts == 0L) {
            stringResource(Res.string.group_status_empty)
        } else {
            pluralStringResource(Res.plurals.group_status_proxies, counts.toInt(), counts)
        }

        GroupType.SUBSCRIPTION -> if (counts == 0L) {
            stringResource(Res.string.group_status_empty_subscription)
        } else {
            pluralStringResource(Res.plurals.group_status_proxies, counts.toInt(), counts)
        }

        else -> error("impossible")
    }
}

@Composable
private fun groupSubtitle(state: GroupItemUiState): String {
    val group = state.group
    val subscription = group.subscription
    if (subscription != null && (subscription.bytesUsed > 0L || subscription.bytesRemaining > 0L)) {
        val traffic = if (subscription.bytesRemaining > 0L) {
            stringResource(
                Res.string.subscription_traffic,
                Libcore.formatBytes(subscription.bytesUsed),
                Libcore.formatBytes(subscription.bytesRemaining),
            )
        } else {
            stringResource(
                Res.string.subscription_used,
                Libcore.formatBytes(subscription.bytesUsed),
            )
        }
        if (subscription.expiryDate > 0) {
            return "$traffic · ${stringResource(
                Res.string.subscription_expire,
                formatTime(subscription.expiryDate * 1000L),
            )}"
        }
        return traffic
    }
    return when {
        group.resolvedOrigin() == GroupOrigin.BUILTIN ->
            stringResource(Res.string.library_group_origin_builtin)

        state.counts == 0L && group.type == GroupType.SUBSCRIPTION ->
            stringResource(Res.string.group_status_empty_subscription)

        state.counts == 0L ->
            stringResource(Res.string.group_status_empty)

        else -> proxyCountLabel(state)
    }
}
