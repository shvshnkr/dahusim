package fr.husi.ui.dahusim

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
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
import fr.husi.database.DataStore
import fr.husi.resources.Res
import fr.husi.resources.dahusim_hub_subtitle
import fr.husi.resources.dahusim_hub_title
import fr.husi.resources.dahusim_nav_autoselect
import fr.husi.resources.dahusim_nav_diagnostics
import fr.husi.resources.dahusim_nav_network
import fr.husi.resources.dahusim_nav_subscriptions
import fr.husi.resources.dahusim_quick_access_enabled
import fr.husi.resources.dahusim_quick_access_enabled_sum
import fr.husi.resources.dahusim_section_settings
import fr.husi.resources.developer_mode
import fr.husi.resources.fast_forward
import fr.husi.resources.ok
import fr.husi.resources.security
import fr.husi.resources.transform
import fr.husi.ui.MainTopNavigationIcon
import fr.husi.ui.MainViewModel
import fr.husi.ui.NavRoutes
import fr.husi.ui.getStringOrRes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference

@Composable
fun DahusimHubScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    onBackPress: () -> Unit,
    onNavigate: (NavRoutes) -> Unit,
    onOpenAppUpdate: () -> Unit,
) {
    val windowInsets = WindowInsets.safeDrawing
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollHideVisible by rememberScrollHideState(listState)
    val serviceStatus by BackendState.status.collectAsStateWithLifecycle()
    val quickAccessEnabled by DataStore.configurationStore
        .booleanFlow(Key.DAHUSIM_QUICK_ACCESS_ENABLED, true)
        .collectAsStateWithLifecycle(true)

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
        contentWindowInsets = windowInsets,
        topBar = {
            SimpleTopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.dahusim_hub_title))
                        Text(
                            text = stringResource(Res.string.dahusim_hub_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    MainTopNavigationIcon(useBack = true, onClick = onBackPress)
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding.withNavigation()),
            ) {
            item("quick-access-toggle") {
                SwitchPreference(
                    value = quickAccessEnabled,
                    onValueChange = { DataStore.dahusimQuickAccessEnabled = it },
                    title = { Text(stringResource(Res.string.dahusim_quick_access_enabled)) },
                    summary = { Text(stringResource(Res.string.dahusim_quick_access_enabled_sum)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (quickAccessEnabled) {
                item("quick-access") {
                    DahusimQuickAccessRow(
                        onOpenAppUpdate = onOpenAppUpdate,
                        showMessage = ::showMessage,
                    )
                }
            }
            item("settings-title") {
                Text(
                    text = stringResource(Res.string.dahusim_section_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            item("nav-network") {
                DahusimNavCard(
                    icon = Res.drawable.security,
                    title = Res.string.dahusim_nav_network,
                    onClick = { onNavigate(NavRoutes.DahusimNetwork) },
                )
            }
            item("nav-subs") {
                DahusimNavCard(
                    icon = Res.drawable.developer_mode,
                    title = Res.string.dahusim_nav_subscriptions,
                    onClick = { onNavigate(NavRoutes.DahusimSubscriptions) },
                )
            }
            item("nav-auto") {
                DahusimNavCard(
                    icon = Res.drawable.fast_forward,
                    title = Res.string.dahusim_nav_autoselect,
                    onClick = { onNavigate(NavRoutes.DahusimAutoselect) },
                )
            }
            item("nav-diag") {
                DahusimNavCard(
                    icon = Res.drawable.transform,
                    title = Res.string.dahusim_nav_diagnostics,
                    onClick = { onNavigate(NavRoutes.DahusimDiagnostics) },
                )
            }
        }
        }
    }
}

@Composable
private fun DahusimNavCard(
    icon: DrawableResource,
    title: StringResource,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = vectorResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
