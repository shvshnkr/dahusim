package fr.husi.ui.dahusim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import fr.husi.database.Probe2kProgress
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
import fr.husi.resources.dahusim_nav_autoselect
import fr.husi.resources.dahusim_nav_diagnostics
import fr.husi.resources.dahusim_nav_network
import fr.husi.resources.dahusim_nav_subscriptions
import fr.husi.ui.MainViewModel
import fr.husi.ui.connectionTestConcurrentPreference
import fr.husi.ui.dahusimDiagnosticsPreferences
import fr.husi.ui.probe2kSettings
import fr.husi.ui.probeParallelismCategory
import fr.husi.ui.proxyAppsPreferences
import fr.husi.ui.simpleModeProbeSettings
import fr.husi.ui.subscriptionCatalogSettings
import fr.husi.ui.subscriptionUpdateParallelismPreferences
import fr.husi.ui.subscriptionUserAgentTemplatesSettings

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
) {
    DahusimPreferenceScaffold(
        title = Res.string.dahusim_nav_subscriptions,
        mainViewModel = mainViewModel,
        onBackPress = onBackPress,
    ) { showMessage ->
        subscriptionCatalogSettings(showMessage)
        subscriptionUserAgentTemplatesSettings()
        subscriptionUpdateParallelismPreferences()
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
        simpleModeProbeSettings()
        probe2kSettings(showMessage)
        probeParallelismCategory()
        connectionTestConcurrentPreference()
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
    ) { _ ->
        dahusimDiagnosticsPreferences()
    }
}
