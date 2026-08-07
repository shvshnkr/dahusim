package fr.husi.ui.dahusim

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.database.Probe2kProgress
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
import fr.husi.resources.dahusim_nav_autoselect
import fr.husi.resources.dahusim_nav_diagnostics
import fr.husi.resources.dahusim_nav_network
import fr.husi.resources.dahusim_nav_subscriptions
import fr.husi.resources.dahusim_nav_user_agents
import fr.husi.resources.grid_3x3
import fr.husi.ui.MainViewModel
import fr.husi.ui.NavRoutes
import fr.husi.ui.dahusimDiagnosticsPreferences
import fr.husi.ui.probe2kAutoselectSettings
import fr.husi.ui.proxyAppsPreferences
import fr.husi.ui.subscriptionCatalogSettings
import fr.husi.ui.subscriptionUpdateParallelismPreferences
import fr.husi.ui.subscriptionUserAgentTemplatesSettings
import org.jetbrains.compose.resources.vectorResource

@Composable
fun DahusimNetworkScreen(
    mainViewModel: MainViewModel,
    onBackPress: () -> Unit,
    openAppManager: () -> Unit,
) {
    DahusimPreferenceScaffold(
        title = Res.string.dahusim_nav_network,
        mainViewModel = mainViewModel,
        onBackPress = onBackPress,
    ) { _ ->
        proxyAppsPreferences(openAppManager)
    }
}

@Composable
fun DahusimSubscriptionsScreen(
    mainViewModel: MainViewModel,
    onBackPress: () -> Unit,
    onNavigate: (NavRoutes) -> Unit,
) {
    DahusimPreferenceScaffold(
        title = Res.string.dahusim_nav_subscriptions,
        mainViewModel = mainViewModel,
        onBackPress = onBackPress,
    ) { showMessage ->
        subscriptionCatalogSettings(showMessage)
        item("nav-user-agents") {
            DahusimSettingsNavRow(
                title = Res.string.dahusim_nav_user_agents,
                onClick = { onNavigate(NavRoutes.DahusimUserAgents) },
            )
        }
        subscriptionUpdateParallelismPreferences()
    }
}

@Composable
fun DahusimUserAgentsScreen(
    mainViewModel: MainViewModel,
    onBackPress: () -> Unit,
) {
    DahusimPreferenceScaffold(
        title = Res.string.dahusim_nav_user_agents,
        mainViewModel = mainViewModel,
        onBackPress = onBackPress,
    ) { _ ->
        subscriptionUserAgentTemplatesSettings()
    }
}

@Composable
fun DahusimAutoselectScreen(
    mainViewModel: MainViewModel,
    onBackPress: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onDefaultDispatcher { Probe2kProgress.refreshPoolCounts() }
    }

    DahusimPreferenceScaffold(
        title = Res.string.dahusim_nav_autoselect,
        mainViewModel = mainViewModel,
        onBackPress = onBackPress,
    ) { showMessage ->
        probe2kAutoselectSettings(showMessage)
    }
}

@Composable
fun DahusimDiagnosticsScreen(
    mainViewModel: MainViewModel,
    onBackPress: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onDefaultDispatcher { Probe2kProgress.refreshPoolCounts() }
    }

    DahusimPreferenceScaffold(
        title = Res.string.dahusim_nav_diagnostics,
        mainViewModel = mainViewModel,
        onBackPress = onBackPress,
    ) { showMessage ->
        dahusimDiagnosticsPreferences(showMessage)
    }
}

@Composable
internal fun DahusimSettingsNavRow(
    title: org.jetbrains.compose.resources.StringResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier
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
                imageVector = vectorResource(Res.drawable.grid_3x3),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = org.jetbrains.compose.resources.stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
