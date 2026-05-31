package fr.husi.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import fr.husi.ui.VpnRequestActivity

@Composable
actual fun rememberVpnServiceLauncher(onFailed: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(VpnRequestActivity.StartService()) { failed ->
        if (failed) onFailed()
    }
    DisposableEffect(launcher) {
        VpnServiceLaunchRegistry.register(launcher)
        onDispose { VpnServiceLaunchRegistry.unregister(launcher) }
    }
    return remember(onFailed) {
        { VpnServiceLaunchRegistry.launch(onDenied = onFailed) }
    }
}
