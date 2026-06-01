package fr.husi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.ui.MainTopNavigationIcon
import fr.husi.compose.SagerFab
import fr.husi.compose.SimpleTopAppBar
import fr.husi.compose.StatsBar
import fr.husi.compose.material3.Button
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.compose.rememberScrollHideState
import fr.husi.compose.withNavigation
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.bug_report
import fr.husi.resources.document
import fr.husi.resources.more_section_app
import fr.husi.resources.more_section_monitoring
import fr.husi.resources.construction
import fr.husi.resources.data_usage
import fr.husi.resources.developer_mode
import fr.husi.resources.home
import fr.husi.resources.info
import fr.husi.resources.menu
import fr.husi.resources.menu_about
import fr.husi.resources.menu_app_update
import fr.husi.resources.menu_dashboard
import fr.husi.resources.menu_log
import fr.husi.resources.menu
import fr.husi.resources.menu_more
import fr.husi.resources.menu_dahusim
import fr.husi.resources.menu_tools
import fr.husi.resources.nfc
import fr.husi.resources.plugin
import fr.husi.resources.ok
import fr.husi.resources.settings
import fr.husi.resources.simple_mode_switch
import fr.husi.resources.transform
import fr.husi.resources.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun MoreScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    onDrawerClick: () -> Unit,
    onNavigate: (NavRoutes) -> Unit,
    onOpenSimpleMode: () -> Unit,
) {
    val windowInsets = WindowInsets.safeDrawing
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollHideVisible by rememberScrollHideState(listState)
    val serviceStatus by BackendState.status.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    val sections = rememberMoreSections()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = windowInsets,
        topBar = {
            SimpleTopAppBar(
                title = { Text(stringResource(Res.string.menu_more)) },
                navigationIcon = {
                    MainTopNavigationIcon(
                        useBack = false,
                        onClick = onDrawerClick,
                        hideOnAndroidBottomNavRoot = true,
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding.withNavigation()),
        ) {
            sections.forEach { section ->
                section.title?.let { title ->
                    item(key = "title-$title") {
                        Text(
                            text = stringResource(title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
                section.entries.forEach { entry ->
                    item(key = entry.key) {
                        MoreRow(
                            icon = entry.icon,
                            label = stringResource(entry.label),
                            onClick = {
                                when (entry) {
                                    is MoreEntry.Route -> onNavigate(entry.route)
                                    is MoreEntry.External -> uriHandler.openUri(entry.url)
                                }
                            },
                        )
                    }
                }
                if (section.showDividerAfter) {
                    item(key = "divider-${section.title}") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
            item(key = "simple-mode") {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenSimpleMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.home),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(Res.string.simple_mode_switch))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MoreRow(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = vectorResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class MoreSection(
    val title: StringResource?,
    val entries: List<MoreEntry>,
    val showDividerAfter: Boolean = true,
)

private sealed interface MoreEntry {
    val key: String
    val icon: DrawableResource
    val label: StringResource

    data class Route(
        override val key: String,
        override val icon: DrawableResource,
        override val label: StringResource,
        val route: NavRoutes,
    ) : MoreEntry

    data class External(
        override val key: String,
        override val icon: DrawableResource,
        override val label: StringResource,
        val url: String,
    ) : MoreEntry
}

@Composable
private fun rememberMoreSections(): List<MoreSection> {
    return listOf(
        MoreSection(
            title = null,
            entries = listOf(
                MoreEntry.Route(
                    "dahusim",
                    Res.drawable.developer_mode,
                    Res.string.menu_dahusim,
                    NavRoutes.DahusimHub,
                ),
            ),
            showDividerAfter = true,
        ),
        MoreSection(
            title = Res.string.more_section_monitoring,
            entries = listOf(
                MoreEntry.Route("log", Res.drawable.bug_report, Res.string.menu_log, NavRoutes.Log),
                MoreEntry.Route(
                    "dashboard",
                    Res.drawable.transform,
                    Res.string.menu_dashboard,
                    NavRoutes.Dashboard,
                ),
                MoreEntry.Route("tools", Res.drawable.construction, Res.string.menu_tools, NavRoutes.Tools),
            ),
        ),
        MoreSection(
            title = Res.string.more_section_app,
            entries = listOf(
                MoreEntry.Route("settings", Res.drawable.settings, Res.string.settings, NavRoutes.Settings),
                MoreEntry.Route("plugin", Res.drawable.nfc, Res.string.plugin, NavRoutes.Plugin),
                MoreEntry.Route("app-update", Res.drawable.update, Res.string.menu_app_update, NavRoutes.AppUpdate),
                MoreEntry.Route("about", Res.drawable.info, Res.string.menu_about, NavRoutes.About),
                MoreEntry.External(
                    "wiki",
                    Res.drawable.data_usage,
                    Res.string.document,
                    "https://codeberg.org/xchacha20-poly1305/husi/wiki",
                ),
            ),
            showDividerAfter = false,
        ),
    )
}
