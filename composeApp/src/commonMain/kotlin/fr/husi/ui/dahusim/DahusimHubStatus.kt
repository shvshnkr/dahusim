package fr.husi.ui.dahusim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.database.DataStore
import fr.husi.database.Probe2kProgress
import fr.husi.database.UserPoolMode
import fr.husi.resources.Res
import fr.husi.resources.dahusim_hub_status_autoselect
import fr.husi.resources.dahusim_hub_status_catalog_off
import fr.husi.resources.dahusim_hub_status_catalog_on
import fr.husi.resources.dahusim_hub_status_diagnostics_empty
import fr.husi.resources.dahusim_hub_status_diagnostics_pool
import fr.husi.resources.dahusim_hub_status_network
import fr.husi.resources.dahusim_hub_status_telegram_off
import fr.husi.resources.dahusim_hub_status_telegram_on
import fr.husi.ui.userPoolModeLabel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun dahusimNetworkHubStatus(): String =
    stringResource(Res.string.dahusim_hub_status_network)

@Composable
internal fun dahusimSubscriptionsHubStatus(): String {
    val catalogEnabled by DataStore.configurationStore
        .booleanFlow(Key.SUBSCRIPTION_CATALOG_ENABLED, true)
        .collectAsStateWithLifecycle(true)
    return stringResource(
        if (catalogEnabled) Res.string.dahusim_hub_status_catalog_on
        else Res.string.dahusim_hub_status_catalog_off,
    )
}

@Composable
internal fun dahusimAutoselectHubStatus(): String {
    val telegramProbe by DataStore.configurationStore
        .booleanFlow(Key.SIMPLE_MODE_TELEGRAM_PROBE, true)
        .collectAsStateWithLifecycle(true)
    val poolWire by DataStore.configurationStore
        .intFlow(Key.USER_POOL_MODE, UserPoolMode.OFF.wire)
        .collectAsStateWithLifecycle(UserPoolMode.OFF.wire)
    val poolLabel = userPoolModeLabel(UserPoolMode.fromWire(poolWire))
    val probeLabel = stringResource(
        if (telegramProbe) Res.string.dahusim_hub_status_telegram_on
        else Res.string.dahusim_hub_status_telegram_off,
    )
    return stringResource(Res.string.dahusim_hub_status_autoselect, probeLabel, poolLabel)
}

@Composable
internal fun dahusimDiagnosticsHubStatus(): String {
    if (!Probe2kProgress.hasPoolSummary()) {
        return stringResource(Res.string.dahusim_hub_status_diagnostics_empty)
    }
    return stringResource(
        Res.string.dahusim_hub_status_diagnostics_pool,
        DataStore.probe2kPoolAlive,
        DataStore.probe2kPoolCandidate,
        DataStore.probe2kPoolDead + DataStore.probe2kPoolCemetery,
    )
}
