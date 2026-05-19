package fr.husi.bg

import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.ktx.Logs
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.libcore.Libcore
import fr.husi.repository.resolveDesktopRepository
import fr.husi.update.AppUpdateCheckResult
import fr.husi.update.AppUpdateCoordinator
import fr.husi.update.AppUpdateInstallResult
import fr.husi.utils.SimpleModeLogStore
import java.io.File
import kotlinx.coroutines.flow.firstOrNull

internal interface DesktopTaskDefinition {
    val id: String
    suspend fun run()
}

internal const val DESKTOP_CONTROL_STATUS_FILE = "desktop-control-status.txt"
internal const val DESKTOP_CONTROL_EXPORT_FILE = "desktop-control-export.txt"
internal const val DESKTOP_CONTROL_UPDATE_FILE = "desktop-control-update.txt"
internal const val DESKTOP_CONTROL_PING_FILE = "desktop-control-ping.txt"

internal object DesktopTaskRegistry {
    private val subscriptionAutoUpdateTask = object : DesktopTaskDefinition {
        override val id: String = "subscription-auto-update"

        override suspend fun run() {
            SubscriptionAutoUpdateRunner.run(mode = SubscriptionUpdateMode.BackgroundEco)
        }
    }

    private val routeAssetAutoUpdateTask = object : DesktopTaskDefinition {
        override val id: String = "route-asset-auto-update"

        override suspend fun run() {
            RouteAssetAutoUpdateRunner.run()
        }
    }

    private val serviceStartTask = object : DesktopTaskDefinition {
        override val id: String = "service-start"

        override suspend fun run() {
            resolveDesktopRepository().startService()
        }
    }

    private val serviceStopTask = object : DesktopTaskDefinition {
        override val id: String = "service-stop"

        override suspend fun run() {
            resolveDesktopRepository().stopService()
        }
    }

    private val serviceReloadTask = object : DesktopTaskDefinition {
        override val id: String = "service-reload"

        override suspend fun run() {
            resolveDesktopRepository().reloadService()
        }
    }

    private val serviceStatusSnapshotTask = object : DesktopTaskDefinition {
        override val id: String = "service-status-snapshot"

        override suspend fun run() {
            val status = BackendState.status.value
            val profile = DataStore.currentProfile
                .takeIf { it > 0L }
                ?.let { SagerDatabase.proxyDao.getById(it) }
            val group = profile
                ?.groupId
                ?.let { SagerDatabase.groupDao.getById(it).firstOrNull() }
            val content = buildString {
                appendLine("timestamp=${System.currentTimeMillis()}")
                appendLine("state=${status.state.name}")
                appendLine("connected=${status.state.connected}")
                appendLine("profileName=${status.profileName.orEmpty()}")
                appendLine("selectedProxy=${DataStore.selectedProxy}")
                appendLine("currentProfile=${DataStore.currentProfile}")
                appendLine("currentProfileName=${profile?.displayName().orEmpty()}")
                appendLine("currentGroupId=${group?.id ?: 0L}")
                appendLine("currentGroupName=${group?.displayName().orEmpty()}")
                appendLine("currentSubscriptionSource=${group?.subscription?.link.orEmpty()}")
                appendLine("serviceMode=${DataStore.serviceMode}")
            }
            controlFile(DESKTOP_CONTROL_STATUS_FILE).writeText(content, Charsets.UTF_8)
        }
    }

    private val simpleLogExportTask = object : DesktopTaskDefinition {
        override val id: String = "simple-log-export"

        override suspend fun run() {
            val exported = SimpleModeLogStore.buildExportCopy()
            val content = buildString {
                appendLine("timestamp=${System.currentTimeMillis()}")
                appendLine("path=${exported.absolutePath}")
            }
            controlFile(DESKTOP_CONTROL_EXPORT_FILE).writeText(content, Charsets.UTF_8)
        }
    }

    private val appUpdateCheckTask = object : DesktopTaskDefinition {
        override val id: String = "app-update-check"

        override suspend fun run() {
            val result = AppUpdateCoordinator.checkForUpdate(manual = true)
            val content = buildString {
                appendLine("timestamp=${System.currentTimeMillis()}")
                when (result) {
                    is AppUpdateCheckResult.Available -> {
                        appendLine("result=available")
                        appendLine("versionName=${result.offer.versionName}")
                        appendLine("versionCode=${result.offer.versionCode}")
                        appendLine("mandatory=${result.offer.mandatory}")
                        val linux = result.offer.linuxAsset
                        appendLine("linuxAssetKind=${linux?.kind.orEmpty()}")
                        appendLine("linuxAssetUrl=${linux?.binary?.url.orEmpty()}")
                    }
                    is AppUpdateCheckResult.Error -> {
                        appendLine("result=error")
                        appendLine("message=${result.message}")
                    }
                    is AppUpdateCheckResult.Disabled -> appendLine("result=disabled")
                    is AppUpdateCheckResult.NoPlatformArtifact -> appendLine("result=no_platform_artifact")
                    is AppUpdateCheckResult.UpToDate -> appendLine("result=up_to_date")
                }
            }
            controlFile(DESKTOP_CONTROL_UPDATE_FILE).writeText(content, Charsets.UTF_8)
        }
    }

    private val appUpdateInstallTask = object : DesktopTaskDefinition {
        override val id: String = "app-update-install"

        override suspend fun run() {
            val checkResult = AppUpdateCoordinator.checkForUpdate(manual = true)
            val installResult = when (checkResult) {
                is AppUpdateCheckResult.Available -> AppUpdateCoordinator.installPendingOffer()
                else -> AppUpdateInstallResult.Cancelled
            }
            val content = buildString {
                appendLine("timestamp=${System.currentTimeMillis()}")
                when (checkResult) {
                    is AppUpdateCheckResult.Available -> {
                        appendLine("check=available")
                        appendLine("versionCode=${checkResult.offer.versionCode}")
                    }
                    is AppUpdateCheckResult.UpToDate -> appendLine("check=up_to_date")
                    is AppUpdateCheckResult.NoPlatformArtifact -> appendLine("check=no_platform_artifact")
                    is AppUpdateCheckResult.Disabled -> appendLine("check=disabled")
                    is AppUpdateCheckResult.Error -> {
                        appendLine("check=error")
                        appendLine("checkMessage=${checkResult.message}")
                    }
                }
                when (installResult) {
                    is AppUpdateInstallResult.Success -> appendLine("install=success")
                    is AppUpdateInstallResult.PendingUserAction -> appendLine("install=pending_user_action")
                    is AppUpdateInstallResult.Cancelled -> appendLine("install=cancelled")
                    is AppUpdateInstallResult.Failed -> {
                        appendLine("install=failed")
                        appendLine("installMessage=${installResult.message}")
                    }
                }
            }
            controlFile(DESKTOP_CONTROL_UPDATE_FILE).writeText(content, Charsets.UTF_8)
        }
    }

    private val servicePingTask = object : DesktopTaskDefinition {
        override val id: String = "service-ping"

        override suspend fun run() {
            val content = buildString {
                appendLine("timestamp=${System.currentTimeMillis()}")
                if (!DataStore.serviceState.connected) {
                    appendLine("result=not_started")
                } else {
                    runCatching {
                        val client = Libcore.newClient(null)
                        try {
                            client.urlTest(
                                "",
                                DataStore.connectionTestURL,
                                DataStore.connectionTestTimeout,
                            )
                        } finally {
                            runCatching { client.close() }
                        }
                    }.fold(
                        onSuccess = { delayMs ->
                            appendLine("result=success")
                            appendLine("delayMs=$delayMs")
                            appendLine("url=${DataStore.connectionTestURL}")
                        },
                        onFailure = { error ->
                            appendLine("result=failed")
                            appendLine("message=${error.message ?: error.toString()}")
                        },
                    )
                }
            }
            controlFile(DESKTOP_CONTROL_PING_FILE).writeText(content, Charsets.UTF_8)
        }
    }

    private val definitions = listOf(
        subscriptionAutoUpdateTask,
        routeAssetAutoUpdateTask,
        serviceStartTask,
        serviceStopTask,
        serviceReloadTask,
        serviceStatusSnapshotTask,
        simpleLogExportTask,
        appUpdateCheckTask,
        appUpdateInstallTask,
        servicePingTask,
    )
        .associateBy(DesktopTaskDefinition::id)

    fun require(taskId: String): DesktopTaskDefinition {
        return definitions[taskId] ?: error("Unknown desktop task: $taskId")
    }

    fun dispatch(taskId: String) {
        runOnDefaultDispatcher {
            runCatching {
                require(taskId).run()
            }.onFailure {
                Logs.e("run desktop task $taskId", it)
            }
        }
    }

    private fun controlFile(name: String): File {
        return resolveDesktopRepository().cacheDir.resolve(name)
    }
}
