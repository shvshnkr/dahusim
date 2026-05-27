package fr.husi.repository

import fr.husi.AlertType
import fr.husi.Key
import fr.husi.bg.BackendState
import fr.husi.bg.GuardedProcessPool
import fr.husi.bg.RuleSetBootstrapCallbacks
import fr.husi.bg.ServiceState
import fr.husi.bg.connectWithRuleSetBootstrap
import fr.husi.bg.initPlugins
import fr.husi.bg.launchPlugins
import fr.husi.bg.proto.TrafficLooper
import fr.husi.RuleProvider
import fr.husi.database.DataStore
import fr.husi.database.ProfileManager
import fr.husi.ktx.Logs
import fr.husi.ktx.ensureMixedPortAvailable
import fr.husi.ktx.isMixedPortBindFailure
import fr.husi.ktx.readableMessage
import fr.husi.utils.simpleModeLog
import fr.husi.libcore.Service
import fr.husi.plugin.PluginNotFoundException
import fr.husi.resources.Res
import fr.husi.resources.invalid_server
import fr.husi.resources.profile_empty
import fr.husi.resources.service_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.UnknownHostException

internal class DesktopServiceRuntime(
    private val boxService: Service?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val access = Mutex()

    private var runningProfileName: String? = null
    private var processes: GuardedProcessPool? = null
    var trafficLooper: TrafficLooper? = null
        private set
    private val cacheFiles = ArrayList<java.io.File>()

    fun start() {
        runExclusive { startLocked() }
    }

    fun reload() {
        runExclusive {
            when {
                DataStore.selectedProxy == 0L -> {
                    stopLocked(resolveRepository().getString(Res.string.profile_empty))
                }

                DataStore.serviceState == ServiceState.Stopped || DataStore.serviceState == ServiceState.Idle -> {
                    startLocked()
                }

                DataStore.serviceState.canStop -> {
                    stopLocked()
                    startLocked()
                }

                else -> Logs.w("Illegal state ${DataStore.serviceState} when invoking reload")
            }
        }
    }

    fun stop() {
        runExclusive { stopLocked() }
    }

    private fun runExclusive(block: suspend () -> Unit): Job = scope.launch {
        access.withLock { block() }
    }

    private suspend fun startLocked() {
        val state = DataStore.serviceState
        if (state.canStop || state == ServiceState.Stopping) return

        val profile = ProfileManager.getProfile(DataStore.selectedProxy)
        if (profile == null) {
            stopLocked(resolveRepository().getString(Res.string.profile_empty))
            return
        }

        val service = boxService
        if (service == null) {
            stopLocked("${resolveRepository().getString(Res.string.service_failed)}: Service unavailable")
            return
        }

        if (service.hasInstance()) {
            cleanupLocked()
        }

        changeState(ServiceState.Connecting)
        BackendState.setConnected(false)

        var bindRetries = 0
        while (true) {
            try {
                ensureMixedPortAvailable()
                connectWithRuleSetBootstrap(
                    callbacks = desktopRuleSetBootstrapCallbacks(profile),
                    onBeforeRetry = { cleanupLocked() },
                ) { preferLocal ->
                    val config = fr.husi.fmt.buildConfig(
                        profile,
                        preferLocalRuleSet = preferLocal,
                    )
                    cacheFiles.clear()
                    val pluginConfigs = initPlugins(
                        config = config,
                        isVPN = DataStore.serviceMode == Key.MODE_VPN,
                        cacheFiles = cacheFiles,
                    )
                    val pool = GuardedProcessPool { throwable ->
                        handleFatal(throwable)
                    }
                    processes = pool
                    launchPlugins(
                        config = config,
                        pluginConfigs = pluginConfigs,
                        processes = pool,
                        cacheFiles = cacheFiles,
                    )

                    service.newInstance(config.config)
                    service.startInstance()

                    trafficLooper = TrafficLooper(
                        box = service,
                        config = config,
                        scope = scope,
                    )
                    trafficLooper?.start()

                    DataStore.currentProfile = profile.id
                    runningProfileName = profile.displayNameForService()
                    changeState(ServiceState.Connected, runningProfileName)
                    BackendState.setConnected(true)
                }
                return
            } catch (e: Throwable) {
                if (bindRetries < 1 && isMixedPortBindFailure(e)) {
                    bindRetries++
                    val nextPort = DataStore.mixedPort + 1
                    simpleModeLog(
                        "SimpleMode",
                        "H32 mixed_port_bind_retry from=${DataStore.mixedPort} to=$nextPort err=${e.readableMessage}",
                    )
                    DataStore.mixedPort = nextPort
                    cleanupLocked()
                    continue
                }
                when (e) {
                    is UnknownHostException -> stopLocked(resolveRepository().getString(Res.string.invalid_server))
                    is PluginNotFoundException ->
                        stopLocked(e.readableMessage, AlertType.MISSING_PLUGIN, e.plugin)

                    else -> stopLocked(
                        "${resolveRepository().getString(Res.string.service_failed)}: ${e.readableMessage}",
                    )
                }
                return
            }
        }
    }

    private fun desktopRuleSetBootstrapCallbacks(profile: fr.husi.database.ProxyEntity) =
        RuleSetBootstrapCallbacks(
            hasLocalRuleSetFiles = { hasLocalRuleSetFiles() },
            onAttempt = { preferLocal ->
                simpleModeLog(
                    "SimpleMode",
                    "H36 desktop_ruleset_bootstrap profileId=${profile.id} rulesProvider=${DataStore.rulesProvider} " +
                        "preferLocal=$preferLocal localGeo=${hasLocalRuleSetFiles()} " +
                        "route=singbox_remote_http provider=${rulesProviderLabel(DataStore.rulesProvider)}",
                )
            },
            onBootstrapFailure = { preferLocal, error ->
                simpleModeLog(
                    "SimpleMode",
                    "H36 desktop_ruleset_bootstrap_failed preferLocal=$preferLocal " +
                        "localGeo=${hasLocalRuleSetFiles()} err=${error.readableMessage}",
                )
            },
            onRetryWithLocal = {
                simpleModeLog(
                    "SimpleMode",
                    "H36 desktop_ruleset_retry mode=local reason=remote_ruleset_bootstrap_failed " +
                        "note=github_may_be_reachable_via_browser",
                )
            },
        )

    private fun rulesProviderLabel(provider: Int): String = when (provider) {
        RuleProvider.OFFICIAL -> "official"
        RuleProvider.LOYALSOLDIER -> "loyalsoldier"
        RuleProvider.CHOCOLATE4U -> "chocolate4u"
        RuleProvider.CUSTOM -> "custom"
        else -> "fallback_sagernet"
    }

    private fun hasLocalRuleSetFiles(): Boolean {
        val geoDir = resolveRepository().externalAssetsDir.resolve("geo")
        return geoDir.exists() &&
            geoDir.isDirectory &&
            geoDir.listFiles()?.any { it.extension.equals("srs", ignoreCase = true) } == true
    }

    private suspend fun stopLocked(
        message: String? = null,
        alertType: Int = AlertType.COMMON,
        alertMessage: String = message.orEmpty(),
    ) {
        if (DataStore.serviceState == ServiceState.Stopping) return

        changeState(ServiceState.Stopping, runningProfileName)
        BackendState.setConnected(false)

        cleanupLocked()
        runningProfileName = null

        BackendState.reset()
        changeState(ServiceState.Stopped)

        if (alertMessage.isNotBlank()) {
            BackendState.emitAlert(alertType, alertMessage)
        }
        if (!message.isNullOrBlank()) {
            Logs.w(message)
        }
    }

    private suspend fun cleanupLocked() {
        val service = boxService
        val pool = processes
        processes = null

        trafficLooper?.stop()
        trafficLooper = null

        pool?.close(scope)

        runCatching {
            if (service?.hasInstance() == true) {
                service.stopInstance()
            }
        }.onFailure {
            Logs.w(it)
        }

        cacheFiles.forEach { file ->
            runCatching { file.delete() }
        }
        cacheFiles.clear()
    }

    private suspend fun handleFatal(throwable: Throwable) {
        access.withLock {
            if (!DataStore.serviceState.canStop) return
            stopLocked("${resolveRepository().getString(Res.string.service_failed)}: ${throwable.readableMessage}")
        }
    }

    private fun changeState(state: ServiceState, profileName: String? = null) {
        DataStore.serviceState = state
        BackendState.updateState(state, profileName)
    }
}
