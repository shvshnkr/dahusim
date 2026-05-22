package fr.husi.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.service.quicksettings.TileService
import androidx.core.content.getSystemService
import fr.husi.Action
import fr.husi.AlertType
import fr.husi.aidl.IServiceControlStub
import fr.husi.aidl.IServiceObserver
import fr.husi.aidl.SpeedDisplayData
import fr.husi.bg.proto.ProxyInstance
import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.database.DirectProfileUrlProbe
import fr.husi.database.ProxyEntity
import fr.husi.database.SagerDatabase
import fr.husi.ktx.Logs
import fr.husi.ktx.broadcastReceiver
import fr.husi.ktx.hasPermission
import fr.husi.ktx.onMainDispatcher
import fr.husi.ktx.readableMessage
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.ktx.runOnMainDispatcher
import fr.husi.ktx.showToast
import fr.husi.libcore.Libcore
import fr.husi.plugin.PluginNotFoundException
import fr.husi.repository.resolveRepository
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.SimpleModeConnectedMaintenance
import fr.husi.simplemode.SimpleModeSessionHealth
import fr.husi.simplemode.SimpleModeTunnelRestart
import fr.husi.simplemode.SimpleModeVpnCoordinator
import fr.husi.resources.*
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.UnknownHostException
import fr.husi.aidl.ServiceStatus as AidlServiceStatus

class BaseService {

    interface BackendEngine {
        var proxy: ProxyInstance?

        suspend fun init(profile: ProxyEntity)

        suspend fun start(onFatal: suspend (Throwable) -> Unit)

        fun stop()

        fun resetNetwork()
    }

    private class AndroidBackendEngine(private val service: Interface) : BackendEngine {
        override var proxy: ProxyInstance? = null

        override suspend fun init(profile: ProxyEntity) {
            proxy = ProxyInstance(profile, service).also {
                it.init(service is VpnService)
            }
        }

        override suspend fun start(onFatal: suspend (Throwable) -> Unit) {
            val proxy = proxy ?: return
            proxy.processes = GuardedProcessPool {
                Logs.w(it)
                onFatal(it)
            }
            proxy.launch()
        }

        override fun stop() {
            proxy?.close()
            proxy = null
        }

        override fun resetNetwork() {
            val proxy = proxy
            if (proxy != null && proxy.isInitialized()) {
                runCatching {
                    resolveRepository().boxService?.resetNetwork()
                }
            }
        }
    }

    class Data internal constructor(val service: Interface) {
        var state = ServiceState.Stopped
        val backend = service.createBackendEngine()
        var proxy: ProxyInstance?
            get() = backend.proxy
            set(value) {
                backend.proxy = value
            }
        var notification: ServiceNotifier = NoopServiceNotifier

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val powerManager = (service as Context).getSystemService<PowerManager>()!!
                    val proxy = proxy
                    if (proxy != null && proxy.isInitialized()) {
                        if (powerManager.isDeviceIdleMode) {
                            resolveRepository().boxService?.pause()
                        } else {
                            resolveRepository().boxService?.wake()
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    withTimeoutOrNull(1000L) {
                        resetNetwork()
                        onMainDispatcher {
                            collapseStatusBar(ctx)
                            showToast(resolveRepository().getString(Res.string.have_reset_network))
                        }
                    }
                }

                Intent.ACTION_USER_PRESENT -> runOnDefaultDispatcher {
                    SimpleModeSessionHealth.triggerQuickCheck("user_present")
                }

                else -> service.stopRunner()
            }
        }

        @SuppressLint("WrongConstant")
        private fun collapseStatusBar(context: Context) {
            try {
                val statusBarManager = context.getSystemService("statusbar")
                val collapse = statusBarManager.javaClass.getMethod("collapsePanels")
                collapse.invoke(statusBarManager)
            } catch (_: Exception) {
            }
        }

        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: ServiceState, message: String? = null) {
            if (state == s && message == null) return
            val previous = state
            state = s
            DataStore.serviceState = s
            BackendState.updateState(s, proxy?.displayProfileName)
            binder.notifyState()
            simpleModeLog(
                "SimpleMode",
                "H22 state_transition from=${previous.name} to=${s.name} " +
                    "message=${message?.replace('\n', ' ') ?: "-"} connectingJobActive=${connectingJob?.isActive == true}",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val context = service as Context
                TileService.requestListeningState(
                    context,
                    ComponentName(context, fr.husi.bg.TileService::class.java),
                )
            }
        }

        fun resetNetwork() {
            backend.resetNetwork()
        }
    }

    class Binder(private var data: Data? = null) : IServiceControlStub(), CoroutineScope,
        AutoCloseable {
        override val coroutineContext = Dispatchers.Main.immediate + Job()
        private val observers = RemoteCallbackList<IServiceObserver>()

        override fun getStatus(): AidlServiceStatus {
            return currentStatus()
        }

        override fun registerObserver(observer: IServiceObserver?) {
            if (observer == null) return
            observers.register(observer)
            try {
                observer.onState(currentStatus())
            } catch (_: RemoteException) {
            }
        }

        override fun unregisterObserver(observer: IServiceObserver?) {
            if (observer == null) return
            observers.unregister(observer)
        }

        fun notifyState() {
            val status = currentStatus()
            notifyObservers { it.onState(status) }
        }

        fun notifyAlert(type: Int, message: String) {
            notifyObservers { it.onAlert(type, message) }
        }

        fun notifySpeed(speed: SpeedDisplayData) {
            notifyObservers { it.onSpeed(speed) }
        }

        override fun close() {
            observers.kill()
            cancel()
            data = null
        }

        private fun currentStatus(): AidlServiceStatus {
            val data = data ?: return AidlServiceStatus()
            val state = data.state
            return AidlServiceStatus(
                state = state.ordinal,
                profileName = data.proxy?.displayProfileName,
                started = state.started,
                connected = state.connected,
            )
        }

        private fun notifyObservers(block: (IServiceObserver) -> Unit) = launch {
            val count = observers.beginBroadcast()
            try {
                for (index in 0 until count) {
                    try {
                        block(observers.getBroadcastItem(index))
                    } catch (_: RemoteException) {
                    }
                }
            } finally {
                observers.finishBroadcast()
            }
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createBackendEngine(): BackendEngine = AndroidBackendEngine(this)
        fun createNotifier(profileName: String): ServiceNotifier = NoopServiceNotifier

        fun onBind(intent: Intent): IBinder? = if (intent.action == Action.SERVICE) {
            data.binder
        } else {
            null
        }

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, runBlocking { resolveRepository().getString(Res.string.profile_empty) })
                return
            }

            val state = data.state
            val restartCurrentService = javaClass == SagerConnection.serviceClass
            when {
                state == ServiceState.Stopped -> {
                    if (restartCurrentService) {
                        startRunner()
                    } else {
                        resolveRepository().startService()
                    }
                }

                state.canStop -> {
                    if (restartCurrentService) {
                        stopRunner(true)
                    } else {
                        stopRunner(afterStop = resolveRepository()::startService)
                    }
                }

                else -> Logs.w("Illegal state $state when invoking use")
            }
        }

        suspend fun startProcesses() {
            data.backend.start { throwable ->
                stopRunner(false, throwable.readableMessage)
            }
            if (resolveRepository().boxService?.needWIFIState() == true) {
                val wifiPermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
                this as Context
                if (!hasPermission(wifiPermission)) {
                    data.binder.notifyAlert(AlertType.NEED_WIFI_PERMISSION, "")
                }
            }
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, javaClass))
            } else {
                startService(Intent(this, javaClass))
            }
        }

        fun killProcesses() {
            data.backend.stop()
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkMonitor.stop()
            }
        }

        fun stopRunner(
            restart: Boolean = false,
            msg: String? = null,
            afterStop: (() -> Unit)? = null,
        ) {
            ServiceRegistry.baseService = null
            ServiceRegistry.vpnService = null

            if (data.state == ServiceState.Stopping) return
            data.notification.destroy()
            data.notification = NoopServiceNotifier
            this as Service

            data.changeState(ServiceState.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                }

                // change the state
                data.changeState(ServiceState.Stopped, msg)
                SimpleModeConnectedMaintenance.cancel()
                SimpleModeSessionHealth.cancel()
                SimpleModeVpnCoordinator.cancelAdaptation()
                WhitelistNetworkRoutingState.reset()
                DataStore.simpleModeActivity = ""
                if (!msg.isNullOrBlank()) {
                    data.binder.notifyAlert(AlertType.COMMON, msg)
                }
                // stop the service if nothing has bound to it
                if (restart) startRunner() else {
                    afterStop?.invoke()
                    stopSelf()
                }
            }
        }

        // networks
        var upstreamInterfaceName: String?

        fun setPausedUntilGoogle(
            reason: String,
            profileId: Long? = null,
            extra: String = "",
        ) {
            val previous = DataStore.autoConnectPausedUntilGoogle
            DataStore.autoConnectPausedUntilGoogle = true
            val profilePart = profileId?.toString() ?: "-"
            val extraPart = if (extra.isBlank()) "" else " $extra"
            simpleModeLog(
                "SimpleMode",
                "H8 pause_until_google_enabled reason=$reason previous=$previous profileId=$profilePart$extraPart",
            )
        }

        suspend fun preInit() {
            DefaultNetworkMonitor.start()
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification.onWakeLock(true)
            } else {
                data.notification.onWakeLock(false)
            }
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            ServiceRegistry.baseService = this

            val data = data
            if (data.state != ServiceState.Stopped) {
                // #region agent log
                simpleModeDebugEvent(
                    runId = "run1",
                    hypothesisId = "H3",
                    location = "BaseService.kt:onStartCommand",
                    message = "start command ignored because service not stopped",
                    data = mapOf("state" to data.state.name),
                )
                // #endregion
                simpleModeLog(
                    "SimpleMode",
                    "H3 start_ignored state=${data.state.name} activity=${DataStore.simpleModeActivity.ifBlank { "-" }} " +
                        "paused=${DataStore.autoConnectPausedUntilGoogle} connectingJobActive=${data.connectingJob?.isActive == true}",
                )
                return Service.START_NOT_STICKY
            }
            data.notification = createNotifier("")
            val profile = runBlocking { SagerDatabase.proxyDao.getById(DataStore.selectedProxy) }
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                stopRunner(false, runBlocking { resolveRepository().getString(Res.string.profile_empty) })
                return Service.START_NOT_STICKY
            }

            setBootReceiverEnabled(DataStore.persistAcrossReboot)
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.permission.SERVICE",
                        null,
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.permission.SERVICE",
                        null,
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(ServiceState.Connecting)
            if (DataStore.simpleMode) {
                DataStore.simpleModeActivity = "Checking network reachability…"
            }
            data.connectingJob = runOnDefaultDispatcher {
                try {
                    preInit()
                    val reachability = resolveConnectReachability()
                    simpleModeLog(
                        "SimpleMode",
                        "H7 reachability google=${reachability.googleReachable} dzen=${reachability.dzenReachable} ya=${reachability.yaReachable} whitelistSource=${reachability.whitelistSourceReachable} paused=${DataStore.autoConnectPausedUntilGoogle}",
                    )
                    if (DataStore.autoConnectPausedUntilGoogle &&
                        !reachability.googleReachable &&
                        !reachability.whitelistOnly
                    ) {
                        simpleModeLog(
                            "SimpleMode",
                            "H8 pause_blocked_wait_google google=${reachability.googleReachable} " +
                                "dzen=${reachability.dzenReachable} ya=${reachability.yaReachable}",
                        )
                        stopRunner(
                            false,
                            resolveRepository().getString(Res.string.simple_mode_wait_for_google),
                        )
                        return@runOnDefaultDispatcher
                    }
                    if (DataStore.autoConnectPausedUntilGoogle && reachability.googleReachable) {
                        val wasPaused = DataStore.autoConnectPausedUntilGoogle
                        DataStore.autoConnectPausedUntilGoogle = false
                        simpleModeLog(
                            "SimpleMode",
                            "H8 auto_connect_resumed_google_reachable previous=$wasPaused",
                        )
                    }
                    if (!reachability.hasInternet) {
                        setPausedUntilGoogle(
                            reason = "no_internet_probe",
                            extra = "google=${reachability.googleReachable} dzen=${reachability.dzenReachable} ya=${reachability.yaReachable}",
                        )
                        stopRunner(
                            false,
                            resolveRepository().getString(Res.string.simple_mode_no_internet_pause),
                        )
                        return@runOnDefaultDispatcher
                    }
                    if (reachability.whitelistOnly) {
                        data.binder.notifyAlert(
                            AlertType.COMMON,
                            resolveRepository().getString(Res.string.simple_mode_whitelist_warning),
                        )
                    }
                    WhitelistNetworkRoutingState.applyReachability(
                        reachability,
                        requestReloadOnChange = false,
                    )
                    simpleModeLog(
                        "SimpleMode",
                        "H9 connect_profile id=${profile.id} group=${profile.groupId} status=${profile.status} ping=${profile.ping} fallbackIndex=${DataStore.autoSelectFallbackIndex}",
                    )
                    DataStore.simpleModeActivity = "Connecting to server..."
                    val bean = profile.requireBean()
                    // #region agent log
                    simpleModeDebugEvent(
                        runId = "run2",
                        hypothesisId = "H2",
                        location = "BaseService.kt:connect_profile",
                        message = "selected profile metadata",
                        data = mapOf(
                            "profileId" to profile.id.toString(),
                            "type" to profile.type.toString(),
                            "server" to bean.serverAddress,
                            "port" to bean.serverPort.toString(),
                            "status" to profile.status.toString(),
                            "ping" to profile.ping.toString(),
                        ),
                    )
                    // #endregion

                    data.notification.onTitle(profile.displayNameForService())

                    if (DataStore.vpnExitProbeProfileId != profile.id) {
                        fr.husi.routing.VpnExitProbe.clearCache()
                    }
                    Executable.killAll()    // clean up old processes
                    data.backend.init(profile)
                    DataStore.currentProfile = profile.id

                    startProcesses()
                    data.changeState(ServiceState.Connected)
                    simpleModeLog("SimpleMode", "H9 connected_profile id=${profile.id}")
                    DataStore.simpleModeActivity = "Verifying internet access..."
                    var postConnectHealthy = true
                    var postConnectLatencyMs = 0
                    val baseTimeoutMs = DataStore.connectionTestTimeout
                    val postConnectTimeoutMs = (baseTimeoutMs * 2).coerceIn(5000, 20_000)
                    val outboundTag = data.proxy?.config?.mainTag.orEmpty()
                    val postConnectUrls = SimpleModeHealthRoute.healthCheckUrls(reachability.whitelistOnly)
                    val healthRoute = if (DataStore.simpleMode && !reachability.whitelistOnly) {
                        SimpleModeHealthRoute.Route.DIRECT_PROFILE
                    } else {
                        SimpleModeHealthRoute.Route.TUNNEL_OUTBOUND
                    }
                    SimpleModeHealthRoute.logProbeConfig(
                        phase = "post_connect",
                        whitelistOnly = reachability.whitelistOnly,
                        route = healthRoute,
                        outboundTag = outboundTag,
                        urls = postConnectUrls,
                        timeoutMs = postConnectTimeoutMs,
                    )
                    // #region agent log
                    simpleModeDebugEvent(
                        runId = "post-connect-probe",
                        hypothesisId = "H_pc_cfg",
                        location = "BaseService.kt:pre_post_connect_url_test",
                        message = "post-connect probe parameters",
                        data = mapOf(
                            "profileId" to profile.id.toString(),
                            "baseTimeoutMs" to baseTimeoutMs.toString(),
                            "postConnectTimeoutMs" to postConnectTimeoutMs.toString(),
                            "outboundTagLen" to outboundTag.length.toString(),
                            "warmupMs" to "400",
                            "url" to postConnectUrls.joinToString(","),
                        ),
                    )
                    // #endregion
                    simpleModeLog(
                        "SimpleMode",
                        "H3b post_connect_probe_cfg baseTimeoutMs=$baseTimeoutMs postTimeoutMs=$postConnectTimeoutMs " +
                            "outboundTagLen=${outboundTag.length} warmupMs=400",
                    )
                    delay(400)
                    val useDirectProbe = healthRoute == SimpleModeHealthRoute.Route.DIRECT_PROFILE
                    runCatching {
                            val latencyMs = if (useDirectProbe) {
                            val delay = DirectProfileUrlProbe.urlTestDelay(profile)?.toLong()
                            SimpleModeHealthRoute.logProbeAttempt(
                                phase = "post_connect",
                                whitelistOnly = reachability.whitelistOnly,
                                route = healthRoute,
                                outboundTag = outboundTag,
                                url = postConnectUrls.first(),
                                ok = delay != null && delay > 0L,
                                delayMs = delay ?: 0L,
                                error = if (delay == null || delay <= 0L) "direct url test failed" else null,
                            )
                            delay ?: error("direct url test failed")
                        } else {
                            val client = Libcore.newClient(null)
                            try {
                                    var successLatency = -1L
                                    var lastError: Throwable? = null
                                    for (testUrl in postConnectUrls) {
                                        val attempt = runCatching {
                                            client.urlTest(outboundTag, testUrl, postConnectTimeoutMs)
                                        }
                                        val latency = attempt.getOrNull()
                                        if (latency != null && latency > 0) {
                                            SimpleModeHealthRoute.logProbeAttempt(
                                                phase = "post_connect",
                                                whitelistOnly = reachability.whitelistOnly,
                                                route = healthRoute,
                                                outboundTag = outboundTag,
                                                url = testUrl,
                                                ok = true,
                                                delayMs = latency,
                                            )
                                            successLatency = latency
                                            break
                                        }
                                        val err = attempt.exceptionOrNull()
                                        lastError = err
                                        SimpleModeHealthRoute.logProbeAttempt(
                                            phase = "post_connect",
                                            whitelistOnly = reachability.whitelistOnly,
                                            route = healthRoute,
                                            outboundTag = outboundTag,
                                            url = testUrl,
                                            ok = false,
                                            error = err?.readableMessage,
                                        )
                                    }
                                    if (successLatency > 0L) {
                                        successLatency
                                    } else {
                                        throw (lastError ?: IllegalStateException("post-connect url test failed"))
                                    }
                            } finally {
                                runCatching { client.close() }
                            }
                        }
                        // #region agent log
                        simpleModeDebugEvent(
                            runId = "post-connect-probe",
                            hypothesisId = "H3",
                            location = "BaseService.kt:post_connect_url_test",
                            message = "post-connect url test success",
                            data = mapOf(
                                "profileId" to profile.id.toString(),
                                "delayMs" to latencyMs.toString(),
                                "url" to postConnectUrls.joinToString(","),
                                "direct" to useDirectProbe.toString(),
                            ),
                        )
                        // #endregion
                        postConnectLatencyMs = latencyMs.toInt().coerceAtLeast(0)
                        simpleModeLog(
                            "SimpleMode",
                            "H3 post_connect_url_test_success profileId=${profile.id} delayMs=$latencyMs direct=$useDirectProbe",
                        )
                        DataStore.simpleModeActivity = ""
                    }.onFailure { err ->
                        // #region agent log
                        simpleModeDebugEvent(
                            runId = "run2",
                            hypothesisId = "H3",
                            location = "BaseService.kt:post_connect_url_test",
                            message = "post-connect url test failed",
                            data = mapOf(
                                "profileId" to profile.id.toString(),
                                "errorClass" to err.javaClass.name,
                                "error" to err.readableMessage,
                                "url" to postConnectUrls.joinToString(","),
                                "direct" to useDirectProbe.toString(),
                            ),
                        )
                        // #endregion
                        simpleModeLog(
                            "SimpleMode",
                            "H3 post_connect_url_test_failed profileId=${profile.id} class=${err.javaClass.simpleName} " +
                                "error=${err.readableMessage} direct=$useDirectProbe",
                        )
                        DataStore.simpleModeActivity = "Server unstable, switching..."
                        postConnectHealthy = false
                        AutoServerSelector.recordProbeFailure(profile.id)
                    }
                    if (!postConnectHealthy) {
                        val wlOnly = reachability.whitelistOnly ||
                            DataStore.activeWhitelistRestrictedNetwork
                        if (DataStore.simpleMode) {
                            val recovered = SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession(
                                failedProfileId = profile.id,
                            )
                            if (recovered) {
                                return@runOnDefaultDispatcher
                            }
                        }
                        runCatching {
                            val fallbackRefreshBudgetMs =
                                DataStore.subscriptionFallbackRefreshBudgetMs.coerceIn(200L, 5000L)
                            SubscriptionAutoUpdateRunner.refreshDueWithBudget(
                                mode = SubscriptionUpdateMode.ForegroundInteractive,
                                budgetMs = fallbackRefreshBudgetMs,
                            )
                        }
                        val fallback = AutoServerSelector.tryMoveToFallback(profile.id)
                        if (fallback != null) {
                            simpleModeLog(
                                "SimpleMode",
                                "H10 post_connect_unhealthy_switch profileId=${profile.id} nextId=$fallback",
                            )
                            // #region agent log
                            simpleModeDebugEvent(
                                runId = "run2",
                                hypothesisId = "H4",
                                location = "BaseService.kt:post_connect_fallback",
                                message = "post-connect unhealthy, switching fallback",
                                data = mapOf(
                                    "profileId" to profile.id.toString(),
                                    "nextId" to fallback.toString(),
                                ),
                            )
                            // #endregion
                            if (DataStore.simpleMode) {
                                SimpleModeTunnelRestart.markReconnect(reachability)
                            }
                            stopRunner(restart = true)
                        } else {
                            if (!wlOnly) {
                                setPausedUntilGoogle(
                                    reason = "post_connect_unhealthy_exhausted",
                                    profileId = profile.id,
                                )
                            }
                            simpleModeLog(
                                "SimpleMode",
                                "H10 post_connect_unhealthy_exhausted profileId=${profile.id}",
                            )
                            DataStore.simpleModeActivity = ""
                            if (DataStore.simpleMode) {
                                BackendState.emitAlert(AlertType.SIMPLE_MODE_ALL_SERVERS_DEAD, "")
                            }
                            stopRunner(false)
                        }
                        return@runOnDefaultDispatcher
                    }
                    AutoServerSelector.markConnected(profile.id)
                    simpleModeLog("SimpleMode", "H10 post_connect_healthy_mark_connected profileId=${profile.id}")
                    if (DataStore.simpleMode && outboundTag.isNotBlank()) {
                        SimpleModeSessionHealth.schedule(profile.id, outboundTag)
                    }
                    SimpleModeConnectedMaintenance.scheduleAfterHealthyConnect(
                        profileId = profile.id,
                        postConnectLatencyMs = postConnectLatencyMs,
                        connectWhitelistOnly = reachability.whitelistOnly,
                        googleReachable = reachability.googleReachable,
                        whitelistSourceReachable = reachability.whitelistSourceReachable,
                    )

                    if (reachability.whitelistOnly && outboundTag.isNotBlank()) {
                        val routingBefore = fr.husi.routing.WhitelistRuRouting
                            .shouldRouteRuGeoViaProxy(profile)
                        val probed = fr.husi.routing.VpnExitProbe.probeAndStore(
                            profile.id,
                            outboundTag,
                            postConnectTimeoutMs,
                        )
                        if (probed != null) {
                            val routingAfter = fr.husi.routing.WhitelistRuRouting
                                .shouldRouteRuGeoViaProxy(profile)
                            if (routingBefore != routingAfter) {
                                WhitelistNetworkRoutingState.requestReloadIfConnected(
                                    "exit_country_ru_routing",
                                )
                            }
                        }
                    }

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (e: UnknownHostException) {
                    Logs.e(e)
                    DataStore.simpleModeActivity = "Server unreachable, trying next..."
                    // #region agent log
                    simpleModeDebugEvent(
                        runId = "run1",
                        hypothesisId = "H1",
                        location = "BaseService.kt:UnknownHostException",
                        message = "connect failed, trying fallback",
                        data = mapOf(
                            "profileId" to profile.id.toString(),
                            "error" to (e.message ?: "unknown_host"),
                        ),
                    )
                    // #endregion
                    simpleModeLog(
                        "SimpleMode",
                        "H1 unknown_host profileId=${profile.id} error=${e.message ?: "unknown_host"}",
                    )
                    AutoServerSelector.recordProbeFailure(profile.id)
                    runCatching {
                        val fallbackRefreshBudgetMs =
                            DataStore.subscriptionFallbackRefreshBudgetMs.coerceIn(200L, 5000L)
                        SubscriptionAutoUpdateRunner.refreshDueWithBudget(
                            mode = SubscriptionUpdateMode.ForegroundInteractive,
                            budgetMs = fallbackRefreshBudgetMs,
                        )
                    }
                    val fallback = AutoServerSelector.tryMoveToFallback(profile.id)
                    if (fallback != null) {
                        if (DataStore.simpleMode) {
                            SimpleModeTunnelRestart.markModeReconnect(DataStore.activeWhitelistRestrictedNetwork)
                        }
                        stopRunner(restart = true)
                    } else {
                        setPausedUntilGoogle(
                            reason = "unknown_host_exhausted",
                            profileId = profile.id,
                        )
                        if (DataStore.simpleMode) {
                            BackendState.emitAlert(AlertType.SIMPLE_MODE_ALL_SERVERS_DEAD, "")
                            stopRunner(false)
                        } else {
                            stopRunner(
                                false,
                                resolveRepository().getString(Res.string.invalid_server) + "\n" +
                                    resolveRepository().getString(Res.string.simple_mode_wait_for_google),
                            )
                        }
                    }
                } catch (e: PluginNotFoundException) {
                    onMainDispatcher {
                        showToast(e.readableMessage)
                    }
                    Logs.w(e)
                    stopRunner(false, e.readableMessage)
                    data.binder.notifyAlert(AlertType.MISSING_PLUGIN, e.plugin)
                } catch (exc: Throwable) {
                    DataStore.simpleModeActivity = "Connection error, trying next..."
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.e(exc.readableMessage)
                    } else {
                        Logs.e(exc)
                    }
                    // #region agent log
                    simpleModeDebugEvent(
                        runId = "run1",
                        hypothesisId = "H5",
                        location = "BaseService.kt:Throwable",
                        message = "connect failed, throwable branch",
                        data = mapOf(
                            "profileId" to profile.id.toString(),
                            "errorClass" to exc.javaClass.name,
                            "error" to exc.readableMessage,
                        ),
                    )
                    // #endregion
                    simpleModeLog(
                        "SimpleMode",
                        "H5 throwable profileId=${profile.id} class=${exc.javaClass.simpleName} error=${exc.readableMessage}",
                    )
                    runCatching {
                        val fallbackRefreshBudgetMs =
                            DataStore.subscriptionFallbackRefreshBudgetMs.coerceIn(200L, 5000L)
                        SubscriptionAutoUpdateRunner.refreshDueWithBudget(
                            mode = SubscriptionUpdateMode.ForegroundInteractive,
                            budgetMs = fallbackRefreshBudgetMs,
                        )
                    }
                    val fallback = AutoServerSelector.tryMoveToFallback(profile.id)
                    if (fallback != null) {
                        if (DataStore.simpleMode) {
                            SimpleModeTunnelRestart.markModeReconnect(DataStore.activeWhitelistRestrictedNetwork)
                        }
                        stopRunner(restart = true)
                    } else {
                        setPausedUntilGoogle(
                            reason = "throwable_exhausted",
                            profileId = profile.id,
                            extra = "errorClass=${exc.javaClass.simpleName}",
                        )
                        if (DataStore.simpleMode) {
                            BackendState.emitAlert(AlertType.SIMPLE_MODE_ALL_SERVERS_DEAD, "")
                            stopRunner(false)
                        } else {
                            stopRunner(
                                false,
                                "${resolveRepository().getString(Res.string.service_failed)}: ${exc.readableMessage}\n" +
                                    resolveRepository().getString(Res.string.simple_mode_wait_for_google),
                            )
                        }
                    }
                } finally {
                    if (data.state == ServiceState.Stopped) {
                        DataStore.simpleModeActivity = ""
                    }
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}

private suspend fun resolveConnectReachability(): NetworkReachability {
    val startedAt = System.currentTimeMillis()
    SimpleModeTunnelRestart.takeCachedReachability()?.let { cached ->
        simpleModeLog(
            "SimpleMode",
            "H7 reachability_reused_tunnel_restart google=${cached.googleReachable} " +
                "whitelistOnly=${cached.whitelistOnly}",
        )
        simpleModeLog(
            "SimpleMode",
            "H7 reachability_probe source=cache elapsedMs=${System.currentTimeMillis() - startedAt}",
        )
        return cached
    }
    val result = NetworkReachabilityProbe.probe(fast = DataStore.simpleMode)
    simpleModeLog(
        "SimpleMode",
        "H7 reachability_probe source=network elapsedMs=${System.currentTimeMillis() - startedAt} fast=${DataStore.simpleMode}",
    )
    return result
}
