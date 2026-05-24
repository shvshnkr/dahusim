package fr.husi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.compose.BoxedVerticalScrollbar
import fr.husi.compose.PlatformMenuIcon
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.compose.SagerFab
import fr.husi.compose.SimpleTopAppBar
import fr.husi.compose.StatsBar
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.compose.rememberScrollHideState
import fr.husi.compose.withNavigation
import fr.husi.database.Probe2kProgress
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.menu
import fr.husi.resources.ok
import fr.husi.resources.quick_settings_open_app_update
import fr.husi.resources.quick_settings_screen_title
import fr.husi.resources.quick_settings_section_network
import fr.husi.resources.quick_settings_section_probes
import fr.husi.resources.quick_settings_section_subscriptions
import fr.husi.resources.quick_settings_section_updates
import fr.husi.resources.update
import io.github.oikvpqya.compose.fastscroller.material3.defaultMaterialScrollbarStyle
import io.github.oikvpqya.compose.fastscroller.rememberScrollbarAdapter
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun QuickSettingsScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    onDrawerClick: () -> Unit,
    openAppManager: () -> Unit,
    onOpenAppUpdate: () -> Unit,
) {
    val windowInsets = WindowInsets.safeDrawing
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollHideVisible by rememberScrollHideState(listState)
    val serviceStatus by BackendState.status.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        onDefaultDispatcher { Probe2kProgress.refreshPoolCounts() }
    }

    fun showMessage(message: String) {
        scope.launch {
            snackbarState.showSnackbar(
                message = message,
                actionLabel = resolveRepository().getString(Res.string.ok),
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SimpleTopAppBar(
                title = { Text(stringResource(Res.string.quick_settings_screen_title)) },
                navigationIcon = {
                    PlatformMenuIcon(
                        imageVector = vectorResource(Res.drawable.menu),
                        contentDescription = stringResource(Res.string.menu),
                        onClick = onDrawerClick,
                    )
                },
                windowInsets = windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
        floatingActionButton = {
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
        ProvidePreferenceLocals {
            val contentPadding = innerPadding.withNavigation()
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = contentPadding,
                ) {
                    item("quick_settings_section_network", PreferenceType.CATEGORY) {
                        PreferenceCategory(
                            compactTop = true,
                            text = { Text(stringResource(Res.string.quick_settings_section_network)) },
                        )
                    }
                    proxyAppsPreferences(openAppManager)

                    item("quick_settings_section_updates", PreferenceType.CATEGORY) {
                        PreferenceCategory(text = { Text(stringResource(Res.string.quick_settings_section_updates)) })
                    }
                    item("quick_settings_open_app_update", PreferenceType.TEXT_FIELD) {
                        Preference(
                            title = { Text(stringResource(Res.string.quick_settings_open_app_update)) },
                            icon = { Icon(vectorResource(Res.drawable.update), null) },
                            onClick = onOpenAppUpdate,
                        )
                    }

                    item("quick_settings_section_subscriptions", PreferenceType.CATEGORY) {
                        PreferenceCategory(text = { Text(stringResource(Res.string.quick_settings_section_subscriptions)) })
                    }
                    subscriptionCatalogSettings(::showMessage)
                    subscriptionUserAgentTemplatesSettings()
                    subscriptionUpdateParallelismPreferences()

                    item("quick_settings_section_probes", PreferenceType.CATEGORY) {
                        PreferenceCategory(text = { Text(stringResource(Res.string.quick_settings_section_probes)) })
                    }
                    probe2kSettings(::showMessage)
                    probeParallelismCategory()
                    connectionTestConcurrentPreference()

                    quickSettingsDiagnostics(::showMessage)
                }

                BoxedVerticalScrollbar(
                    modifier = Modifier
                        .padding(contentPadding)
                        .fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(scrollState = listState),
                    style = defaultMaterialScrollbarStyle().copy(thickness = 12.dp),
                )
            }
        }
    }
}
