@file:OptIn(KoinDelicateAPI::class, KoinExperimentalAPI::class)

package fr.husi.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import fr.husi.Key
import fr.husi.bg.DeepLinkDispatcher
import fr.husi.bg.SagerConnection
import fr.husi.compose.theme.AppTheme
import fr.husi.database.DataStore
import fr.husi.database.ProbeScheduler
import fr.husi.update.AppUpdateCoordinator
import fr.husi.permission.LocalPermissionPlatform
import fr.husi.permission.rememberAndroidPermissionPlatform
import fr.husi.repository.resolveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.android.scope.AndroidScopeComponent
import org.koin.androidx.scope.activityRetainedScope
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.annotation.KoinDelicateAPI
import org.koin.core.annotation.KoinExperimentalAPI

class MainActivity : ComposeActivity(), AndroidScopeComponent {

    override val scope by activityRetainedScope()
    private val serviceConnection =
        SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serviceConnection.connect(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            DataStore.configurationStore
                .stringFlow(Key.SERVICE_MODE, Key.MODE_VPN)
                .drop(1)
                .collect {
                    if (DataStore.serviceState.started) {
                        resolveRepository().reloadService()
                        serviceConnection.reconnect(applicationContext)
                    }
                }
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> onNewIntent(intent)
            else -> {}
        }

        setContent {
            val permissionPlatform = rememberAndroidPermissionPlatform()
            UnboundKoinScope(scope) {
                CompositionLocalProvider(
                    LocalPermissionPlatform provides permissionPlatform,
                ) {
                    AppTheme {
                        MainScreen(
                            moveToBackground = { moveTaskToBack(true) },
                            // One-shot: drop the extra so activity recreation (rotation,
                            // process restore) does not re-run the import dialog.
                            initialProcessText = intent
                                .takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
                                ?.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
                                ?.also { intent.removeExtra(Intent.EXTRA_PROCESS_TEXT) }
                                ?.takeIf { savedInstanceState == null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return
        DeepLinkDispatcher.emit(uri.toString())
    }

    override fun onStart() {
        serviceConnection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { AppUpdateCoordinator.checkForUpdate(manual = false) }
        }
        if (!DataStore.serviceState.connected) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ProbeScheduler.runBackgroundMaintenanceIfDue() }
            }
        }
    }

    override fun onStop() {
        serviceConnection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceConnection.disconnect(applicationContext)
    }

}
