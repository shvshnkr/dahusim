@file:OptIn(KoinExperimentalAPI::class)

package fr.husi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import fr.husi.AlertType
import fr.husi.bg.Alert
import fr.husi.bg.BackendState
import fr.husi.bg.Executable
import fr.husi.bg.ServiceState
import fr.husi.compose.BackHandler
import fr.husi.compose.ScrollableDialog
import fr.husi.ui.MainViewModelAlertDialog
import fr.husi.compose.TextButton
import fr.husi.compose.material3.DrawerItem
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.IconButton
import fr.husi.compose.material3.NavigationDrawer
import fr.husi.compose.material3.Text
import fr.husi.compose.material3.rememberDrawerStateHolder
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.fmt.PluginEntry
import fr.husi.ktx.exitApplication
import fr.husi.ktx.restartApplication
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.ktx.showToast
import fr.husi.permission.AppPermission
import fr.husi.permission.LocalPermissionPlatform
import fr.husi.platform.PlatformInfo
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.action_download
import fr.husi.resources.bug_report
import fr.husi.resources.cancel
import fr.husi.resources.close
import fr.husi.resources.construction
import fr.husi.resources.data_usage
import fr.husi.resources.description
import fr.husi.resources.directions
import fr.husi.resources.document
import fr.husi.resources.error
import fr.husi.resources.fast_rewind
import fr.husi.resources.have_a_nice_day
import fr.husi.resources.home
import fr.husi.resources.info
import fr.husi.resources.location_permission_description
import fr.husi.resources.location_permission_title
import fr.husi.resources.menu_about
import fr.husi.resources.menu_app_update
import fr.husi.resources.menu_library
import fr.husi.resources.menu_more
import fr.husi.resources.menu_dashboard
import fr.husi.resources.menu_log
import fr.husi.resources.menu_route
import fr.husi.resources.menu_tools
import fr.husi.resources.missing_plugin
import fr.husi.resources.nfc
import fr.husi.resources.no_thanks
import fr.husi.resources.ok
import fr.husi.resources.permission_denied
import fr.husi.resources.plugin
import fr.husi.resources.plugin_unknown
import fr.husi.resources.query_package_denied
import fr.husi.resources.question_mark
import fr.husi.resources.developer_mode
import fr.husi.resources.settings
import fr.husi.resources.simple_mode_switch
import fr.husi.resources.simple_mode_all_servers_dead_message
import fr.husi.resources.simple_mode_all_servers_dead_title
import fr.husi.resources.simple_mode_exit_app_action
import fr.husi.resources.simple_mode_wait_for_google_action
import fr.husi.resources.transform
import fr.husi.resources.update
import fr.husi.resources.view_list
import fr.husi.resources.more_vert
import fr.husi.resources.warning_amber
import fr.husi.results.LocalResultEventBus
import fr.husi.results.ResultEventBus
import fr.husi.ui.configuration.ProfileSelectSheet
import fr.husi.utils.simpleModeDebugEvent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.currentKoinScope
import org.koin.compose.navigation3.EntryProvider
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.compose.scope.KoinScope
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.Scope
import kotlin.random.Random

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    moveToBackground: () -> Unit,
    initialProcessText: String? = null,
) {
    val scopeId = remember {
        "main-screen:${Random.nextLong()}"
    }
    KoinScope<MainScreenScope>(scopeID = scopeId) {
        val mainScreenScope = currentKoinScope()
        val viewModel = koinViewModel<MainViewModel>()
        val entryProvider = koinEntryProvider<NavKey>(scope = mainScreenScope)
        MainScreenContent(
            modifier = modifier,
            viewModel = viewModel,
            moveToBackground = moveToBackground,
            initialProcessText = initialProcessText,
            koinScope = mainScreenScope,
            entryProvider = entryProvider,
        )
    }
}

@Composable
private fun MainScreenContent(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    moveToBackground: () -> Unit,
    initialProcessText: String?,
    koinScope: Scope,
    entryProvider: EntryProvider<NavKey>,
) {
    val permission = LocalPermissionPlatform.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val savedStateConfiguration = remember { NavRoutes.savedStateConfiguration }
    val startRoute = remember { NavRoutes.Simple }
    val backStack = rememberNavBackStack(savedStateConfiguration, startRoute)
    val resultBus = remember { ResultEventBus() }
    val drawerStateHolder = rememberDrawerStateHolder()
    val navigator = remember(koinScope, backStack) {
        koinScope.get<Navigator> {
            parametersOf(backStack, startRoute)
        }
    }
    var navColdStartDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (navColdStartDone) return@LaunchedEffect
        navColdStartDone = true
        // #region agent log
        simpleModeDebugEvent(
            runId = "cold-start",
            hypothesisId = "H1",
            location = "MainScreen.kt:navColdStart",
            message = "backStack before normalize",
            data = mapOf(
                "simpleMode" to DataStore.simpleMode.toString(),
                "size" to backStack.size.toString(),
                "top" to (backStack.lastOrNull()?.toString() ?: "null"),
            ),
        )
        // #endregion
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
        if (backStack.lastOrNull() != NavRoutes.Simple) {
            backStack.clear()
            backStack.add(NavRoutes.Simple)
        }
        DataStore.simpleMode = true
        // #region agent log
        simpleModeDebugEvent(
            runId = "cold-start",
            hypothesisId = "H2",
            location = "MainScreen.kt:navColdStart",
            message = "backStack after normalize",
            data = mapOf(
                "size" to backStack.size.toString(),
                "top" to (backStack.lastOrNull()?.toString() ?: "null"),
            ),
        )
        // #endregion
    }
    val selectedDrawerRoute = navigator.selectedDrawerRoute
    val selectedBottomNavTab = navigator.selectedBottomNavTab
    val currentRoute = backStack.lastOrNull() as? NavRoutes
    val showBottomNav = PlatformInfo.isAndroid &&
        currentRoute != NavRoutes.Simple &&
        currentRoute != null &&
        (currentRoute.isMainBottomNavRoot() || selectedBottomNavTab != null)
    LaunchedEffect(currentRoute, backStack.size) {
        if (currentRoute == NavRoutes.Simple && backStack.size > 1) {
            navigator.navigateToSimpleMode()
        }
    }
    val isAtStartDestination = navigator.isAtStartDestination
    val serviceStatus by BackendState.status.collectAsStateWithLifecycle()
    val profilePickerController = remember(koinScope) {
        koinScope.get<ProfilePickerController>()
    }

    fun closeDrawer() {
        if (drawerStateHolder.canCollapse) {
            scope.launch { drawerStateHolder.close() }
        }
    }

    /**
     * Check query packages permission for rogue vendors.
     * If we don't query for `com.android.permission.GET_INSTALLED_APPS` permission,
     * only when we query all packages in foreground will pop the permission window for query permission.
     * @see <a href="https://www.taf.org.cn/upload/AssociationStandard/TTAF%20108-2022%20%E7%A7%BB%E5%8A%A8%E7%BB%88%E7%AB%AF%E5%BA%94%E7%94%A8%E8%BD%AF%E4%BB%B6%E5%88%97%E8%A1%A8%E6%9D%83%E9%99%90%E5%AE%9E%E6%96%BD%E6%8C%87%E5%8D%97.pdf">移动终端应用软件列表权限实施指南</a>
     */
    var showQueryPackageDeniedDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (permission.canRequestPermission(AppPermission.QueryInstalledApps) &&
            !permission.hasPermission(AppPermission.QueryInstalledApps)
        ) {
            permission.requestPermission(AppPermission.QueryInstalledApps) { granted ->
                if (granted) runOnDefaultDispatcher {
                    resolveRepository().stopService()
                    delay(500)
                    SagerDatabase.instance.close()
                    Executable.killAll(true)
                    restartApplication()
                } else {
                    showQueryPackageDeniedDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val hasPostNotification =
            permission.hasPermission(AppPermission.PostNotifications)
        if (!hasPostNotification) {
            permission.requestPermission(AppPermission.PostNotifications)
        }
    }

    BackHandler(enabled = true) {
        when {
            drawerStateHolder.canCollapse && drawerStateHolder.isOpen -> scope.launch { drawerStateHolder.close() }

            !isAtStartDestination -> {
                val popped = navigator.popBackStack()
                if (!popped) {
                    navigator.navigateToDrawerRoute(startRoute)
                }
            }

            else -> moveToBackground()
        }
    }

    LaunchedEffect(serviceStatus.state) {
        if (serviceStatus.state != ServiceState.Connected) {
            viewModel.resetUrlTestStatus()
        }
    }

    LaunchedEffect(initialProcessText) {
        if (!initialProcessText.isNullOrBlank()) {
            viewModel.parseProxy(initialProcessText)
        }
    }

    var showServiceAlert by remember { mutableStateOf<Alert?>(null) }

    LaunchedEffect(Unit) {
        BackendState.alerts.collect { alert ->
            if (alert.type == AlertType.COMMON) {
                if (alert.message.isNotBlank()) {
                    viewModel.showSnackbar(StringOrRes.Direct(alert.message))
                }
            } else {
                showServiceAlert = alert
            }
        }
    }

    fun onDrawerClick() {
        when {
            showBottomNav -> return
            !drawerStateHolder.canCollapse -> return
            else -> scope.launch {
                if (drawerStateHolder.isOpen) {
                    drawerStateHolder.close()
                } else {
                    drawerStateHolder.open()
                }
            }
        }
    }

    remember(koinScope) {
        koinScope.get<DrawerController> {
            parametersOf(::onDrawerClick)
        }
    }

    val mainNavHost: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalResultEventBus provides resultBus,
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = navigator::popBackStack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider,
            )

            profilePickerController.session?.let { session ->
                ProfileSelectSheet(
                    preSelected = session.preSelected,
                    onDismiss = profilePickerController::dismiss,
                    onSelected = profilePickerController::select,
                )
            }
        }
    }

    val mainShell: @Composable () -> Unit = {
        if (PlatformInfo.isAndroid) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    mainNavHost()
                }
                if (showBottomNav && selectedBottomNavTab != null) {
                    MainBottomNavigationBar(
                        selectedTab = selectedBottomNavTab,
                        onTabSelected = { route ->
                            if (route == NavRoutes.Simple) {
                                navigator.navigateToSimpleMode()
                            } else {
                                navigator.navigateToDrawerRoute(route)
                            }
                        },
                    )
                }
            }
        } else {
            mainNavHost()
        }
    }

    if (PlatformInfo.isAndroid) {
        mainShell()
    } else {
        NavigationDrawer(
            drawerStateHolder = drawerStateHolder,
            drawerContent = {
                @Composable
                fun BuildDrawerItem(info: DrawerItemInfo) {
                    DrawerItem(
                        info = info,
                        closeDrawer = ::closeDrawer,
                        selectedDrawerRoute = selectedDrawerRoute,
                        onNavigate = navigator::navigateToDrawerRoute,
                    )
                }

                val dividerPadding = 4.dp
                val items0 = remember {
                    persistentListOf(
                        DrawerItemInfo(
                            Res.string.menu_library,
                            Res.drawable.view_list,
                            NavRoutes.Library,
                        ),
                        DrawerItemInfo(
                            Res.string.menu_route,
                            Res.drawable.directions,
                            NavRoutes.Route,
                        ),
                        DrawerItemInfo(
                            Res.string.settings,
                            Res.drawable.settings,
                            NavRoutes.Settings,
                        ),
                        DrawerItemInfo(
                            Res.string.menu_more,
                            Res.drawable.more_vert,
                            NavRoutes.More,
                        ),
                    )
                }
                for (info in items0) BuildDrawerItem(info)
                HorizontalDivider(modifier = Modifier.padding(vertical = dividerPadding))
                Button(
                    onClick = {
                        closeDrawer()
                        DataStore.simpleMode = true
                        navigator.navigateToSimpleMode()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.home),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(Res.string.simple_mode_switch),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (drawerStateHolder.canCollapse) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = dividerPadding))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        val tooltipState = rememberTooltipState()
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                TooltipAnchorPosition.Above,
                            ),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(Res.string.close))
                                }
                            },
                            state = tooltipState,
                        ) {
                            IconButton(
                                onClick = ::closeDrawer,
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    imageVector = vectorResource(Res.drawable.fast_rewind),
                                    contentDescription = stringResource(Res.string.close),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            },
        ) {
            mainShell()
        }
    }

    if (showQueryPackageDeniedDialog) AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(stringResource(Res.string.ok)) {
                permission.openPermissionSettings()
                showQueryPackageDeniedDialog = false
            }
        },
        dismissButton = {
            TextButton(stringResource(Res.string.no_thanks)) {
                showQueryPackageDeniedDialog = false
                viewModel.showSnackbar(StringOrRes.Res(Res.string.have_a_nice_day))
            }
        },
        icon = {
            Icon(vectorResource(Res.drawable.warning_amber), null)
        },
        title = { Text(stringResource(Res.string.permission_denied)) },
        text = { Text(stringResource(Res.string.query_package_denied)) },
    )

    if (showServiceAlert != null) {
        val alert = showServiceAlert!!
        when (alert.type) {
            AlertType.MISSING_PLUGIN -> {
                val pluginName = alert.message
                val plugin = PluginEntry.find(pluginName)
                if (plugin == null) {
                    showServiceAlert = null
                    viewModel.showSnackbar(
                        StringOrRes.ResWithParams(Res.string.plugin_unknown, pluginName),
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = { showServiceAlert = null },
                        confirmButton = {
                            TextButton(stringResource(Res.string.action_download)) {
                                showServiceAlert = null
                                uriHandler.openUri(
                                    if (PlatformInfo.isAndroid) {
                                        plugin.downloadSource.apk
                                    } else {
                                        plugin.downloadSource.binary
                                    },
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(stringResource(Res.string.cancel)) {
                                showServiceAlert = null
                            }
                        },
                        icon = { Icon(vectorResource(Res.drawable.error), null) },
                        title = { Text(stringResource(plugin.displayName)) },
                        text = { Text(stringResource(Res.string.missing_plugin)) },
                    )
                }
            }

            AlertType.NEED_WIFI_PERMISSION -> {
                AlertDialog(
                    onDismissRequest = { showServiceAlert = null },
                    confirmButton = {
                        TextButton(stringResource(Res.string.ok)) {
                            showServiceAlert = null
                            permission.requestPermission(AppPermission.WifiInfo)
                        }
                    },
                    dismissButton = {
                        TextButton(stringResource(Res.string.no_thanks)) {
                            showServiceAlert = null
                        }
                    },
                    icon = { Icon(vectorResource(Res.drawable.warning_amber), null) },
                    title = { Text(stringResource(Res.string.location_permission_title)) },
                    text = { Text(stringResource(Res.string.location_permission_description)) },
                )
            }

            AlertType.SIMPLE_MODE_ALL_SERVERS_DEAD -> {
                AlertDialog(
                    onDismissRequest = {
                        DataStore.autoConnectPausedUntilGoogle = true
                        resolveRepository().stopService()
                        showServiceAlert = null
                    },
                    confirmButton = {
                        TextButton(stringResource(Res.string.simple_mode_wait_for_google_action)) {
                            DataStore.autoConnectPausedUntilGoogle = true
                            resolveRepository().stopService()
                            showServiceAlert = null
                        }
                    },
                    dismissButton = {
                        TextButton(stringResource(Res.string.simple_mode_exit_app_action)) {
                            showServiceAlert = null
                            exitApplication()
                        }
                    },
                    icon = { Icon(vectorResource(Res.drawable.warning_amber), null) },
                    title = { Text(stringResource(Res.string.simple_mode_all_servers_dead_title)) },
                    text = { Text(stringResource(Res.string.simple_mode_all_servers_dead_message)) },
                )
            }
        }
    }

    val importSubscriptionState by viewModel.importSubscriptionDialog.collectAsStateWithLifecycle()
    importSubscriptionState?.let { state ->
        ImportSubscriptionDialog(
            state = state,
            onConfirm = { viewModel.confirmImportSubscription(it) },
            onDismiss = { viewModel.dismissImportSubscriptionDialog() },
        )
    }

    val firstLaunchOverlay by viewModel.firstLaunchSubscriptionOverlay.collectAsStateWithLifecycle()
    when (val sync = firstLaunchOverlay) {
        is FirstLaunchSubscriptionOverlayState.Running -> {
            Dialog(onDismissRequest = {}) {
                Card(Modifier.padding(8.dp)) {
                    Column(
                        Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            sync.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            sync.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            sync.progressLine,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        else -> Unit
    }

    AppUpdatePromptHost { message ->
        showToast(message)
    }
}

@Immutable
private data class DrawerItemInfo(
    val label: StringResource,
    val icon: DrawableResource,
    val route: NavRoutes,
)

@Composable
private fun DrawerItem(
    modifier: Modifier = Modifier,
    info: DrawerItemInfo,
    closeDrawer: () -> Unit,
    selectedDrawerRoute: NavRoutes?,
    onNavigate: (NavRoutes) -> Unit,
) {
    val selected = selectedDrawerRoute.matchesRoute(info.route)
    DrawerItem(
        label = { Text(stringResource(info.label)) },
        selected = selected,
        onClick = {
            closeDrawer()
            if (!selected) {
                onNavigate(info.route)
            }
        },
        modifier = modifier,
        icon = {
            Icon(vectorResource(info.icon), null)
        },
    )
}

private fun NavRoutes?.matchesRoute(
    route: NavRoutes,
): Boolean {
    val current = this ?: return false
    val currentTab = current.bottomNavTab()
    val routeTab = route.bottomNavTab()
    if (currentTab != null && routeTab != null) {
        return currentTab == routeTab
    }
    return current::class == route::class
}

@Composable
fun MainViewModelAlertDialog(
    dialog: MainViewModelUiEvent.AlertDialog,
    onConsumed: () -> Unit,
) {
    ScrollableDialog(
        onDismissRequest = {
            dialog.onDismiss?.invoke()
            onConsumed()
        },
        confirmButton = {
            TextButton(stringOrRes(dialog.confirmButton.label)) {
                dialog.confirmButton.onClick()
                onConsumed()
            }
        },
        dismissButton = dialog.dismissButton?.let { button ->
            {
                TextButton(stringOrRes(button.label)) {
                    button.onClick()
                    onConsumed()
                }
            }
        },
        icon = {
            Icon(
                vectorResource(
                    if (dialog.dismissButton != null) {
                        Res.drawable.question_mark
                    } else {
                        Res.drawable.error
                    },
                ),
                null,
            )
        },
        title = { Text(stringOrRes(dialog.title)) },
        text = { Text(stringOrRes(dialog.message)) },
    )
}