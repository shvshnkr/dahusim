package fr.husi

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import fr.husi.bg.BackendState
import fr.husi.bootstrap.DefaultUserBootstrap
import fr.husi.bg.DeepLinkDispatcher
import fr.husi.bg.DesktopBackgroundCoordinator
import fr.husi.bg.DesktopLegacySchedulerCleanup
import fr.husi.bg.DesktopTaskRegistry
import fr.husi.bg.DESKTOP_CONTROL_EXPORT_FILE
import fr.husi.bg.DESKTOP_CONTROL_PING_FILE
import fr.husi.bg.DESKTOP_CONTROL_STATUS_FILE
import fr.husi.bg.DESKTOP_CONTROL_UPDATE_FILE
import fr.husi.bg.RouteAssetUpdater
import fr.husi.bg.ServiceState
import fr.husi.bg.SubscriptionUpdater
import fr.husi.compose.theme.AppTheme
import fr.husi.database.DataStore
import fr.husi.di.initHusiKoin
import fr.husi.ktx.Logs
import fr.husi.ktx.exitApplication
import fr.husi.ktx.invariantDirectoryPathString
import fr.husi.ktx.toStringIterator
import fr.husi.libcore.Client
import fr.husi.libcore.Libcore
import fr.husi.libcore.loadCA
import fr.husi.platform.Platform
import fr.husi.platform.PlatformInfo
import fr.husi.repository.DesktopRepository
import fr.husi.update.AppUpdateCoordinator
import fr.husi.repository.resolveDesktopRepository
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import fr.husi.resources.Res
import fr.husi.resources.app_name
import fr.husi.resources.close
import fr.husi.resources.exit
import fr.husi.resources.ic_launcher
import fr.husi.resources.instance_already_running
import fr.husi.resources.instance_already_running_title
import fr.husi.resources.service_mode
import fr.husi.resources.service_mode_proxy
import fr.husi.resources.service_mode_vpn
import fr.husi.resources.start
import fr.husi.resources.stop
import fr.husi.ui.MainScreen
import fr.husi.utils.CrashHandler
import fr.husi.utils.copyBundledRuleSetAssetsIfNeeded
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedReader
import java.util.concurrent.CountDownLatch
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private const val APP_NAME = "fr.husi"

fun main(args: Array<String>) = DesktopMain().main(args)

private class DesktopMain : CliktCommand(APP_NAME) {

    companion object {
        private const val MIN_LOG_LEVEL = 0
        private const val MAX_LOG_LEVEL = 6
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val DAEMON_PROXY_ENV_PORT = "HUSI_PROXY_PORT"
        private const val DAEMON_PROXY_ENV_AUTH = "HUSI_PROXY_AUTH"

        private const val PREFERENCE_NODE_PROPERTY_NAME = "me.zhanghai.compose.preference.node"
        private const val PREFERENCE_NODE_NAME = "/fr/husi/preference"
    }

    val baseDir: File? by option(
        "-d",
        "--dir",
        help = "Data directory",
    ).file(
        canBeFile = false,
        canBeDir = true,
        mustBeWritable = true,
        mustBeReadable = true,
    )

    val logLevel: Int? by option(
        "-l",
        "--log-level",
        help = "Log level override (0-6)",
    ).int().restrictTo(MIN_LOG_LEVEL..MAX_LOG_LEVEL)

    val many: Boolean by option(
        "-m",
        "--many",
        help = "Ignore exist instance",
    ).flag()

    val autoStart: Boolean by option(
        "--autostart",
        hidden = true,
        help = "[Internal] Started by system autostart. This option should only be added by program itself, not by users.",
    ).flag()

    val background: Boolean by option(
        "-b",
        "--background",
        help = "Start without opening the main window",
    ).flag()

    val daemon: Boolean by option(
        "--daemon",
        help = "Run headless daemon (no Compose window/tray)",
    ).flag()

    val daemonProxyPort: Int? by option(
        "--proxy-port",
        help = "Daemon mixed proxy listen port",
    ).int().restrictTo(MIN_PORT..MAX_PORT)

    val daemonProxyAuth: String? by option(
        "--proxy-auth",
        help = "Daemon proxy auth in user:password format; use 'none' to disable auth",
    )

    val control: String? by option(
        "--ctl",
        help = "Control a running daemon: start|stop|reload|status|ping|export-log|update-check|update-install",
    )

    val systemd: String? by option(
        "--systemd",
        help = "Manage Linux systemd unit: install|uninstall|enable|disable|start|stop|restart|status",
    )

    val systemdScope: String by option(
        "--systemd-scope",
        help = "systemd scope: user|system",
    ).default("user")

    val pseudoGui: Boolean by option(
        "--pseudo-gui",
        help = "Open interactive terminal pseudo-GUI for daemon control",
    ).flag()

    val taskId: String? by option(
        "--task",
        hidden = true,
        help = "[Internal] Run a hidden desktop task and exit.",
    )

    val deepLinks: List<String> by argument(
        name = "deep-link",
        help = "Deep links",
    ).multiple()

    override fun run() {
        taskId?.let {
            exitProcess(runTaskMode(it, requireExistingInstance = false))
        }
        control?.let {
            exitProcess(runControlMode(it))
        }
        systemd?.let {
            val launcher = buildLauncherCommand()
            exitProcess(
                DesktopSystemd.run(
                    command = it,
                    scope = systemdScope,
                    launcherCommand = launcher,
                ),
            )
        }
        if (pseudoGui) {
            exitProcess(runPseudoGui())
        }
        if (daemon) {
            runDaemonMode()
            return
        }

        registerMacOSOpenUriHandler()
        initDesktopRuntime()
        runCatching {
            runBlocking {
                SubscriptionUpdater.reconfigureUpdater()
                RouteAssetUpdater.reconfigureUpdater()
                SubscriptionCatalogCoordinator.syncIfDue(manual = false)
                AppUpdateCoordinator.checkForUpdate(manual = false)
            }
        }.onFailure {
            Logs.e("reconfigure desktop tasks on startup", it)
        }
        for (link in deepLinks) {
            DeepLinkDispatcher.emit(link)
        }

        abortIfLinuxUiWithoutDisplay()

        application {
            val repository = resolveDesktopRepository()
            val supportTray = remember { isTraySupported }
            var windowVisible by remember {
                mutableStateOf(!background || !supportTray)
            }

            val trayState = rememberTrayState()
            val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))

            fun openWindow() {
                windowVisible = true
                windowState.isMinimized = false
            }

            fun exitGracefully() {
                DesktopBackgroundCoordinator.stop()
                runCatching {
                    runBlocking {
                        repository.stopService()
                    }
                }
                exitApplication()
            }

            DesktopResourceEnvironmentFix {
                LaunchedEffect(autoStart) {
                    if (shouldAutoConnectOnLaunch()) {
                        repository.startService()
                    }
                }
                if (supportTray) {
                    // In fact, whether on macOS, Windows, or Linux, the advanced tray consistently throws "java.lang.UnsupportedOperationException: java.awt.Menu doesn't support mnemonic."
                    val supportAdvancedTray = false
                    Tray(
                        icon = painterResource(Res.drawable.ic_launcher),
                        state = trayState,
                        tooltip = stringResource(Res.string.app_name),
                        onAction = ::openWindow,
                    ) {
                        val serviceStatus by BackendState.status.collectAsState()
                        Item(
                            text = serviceStatus.profileName ?: stringResource(Res.string.app_name),
                            mnemonic = if (supportAdvancedTray) {
                                'O'
                            } else {
                                null
                            },
                        ) {
                            openWindow()
                        }
                        Item(
                            text = stringResource(
                                if (serviceStatus.state == ServiceState.Connected) {
                                    Res.string.stop
                                } else {
                                    Res.string.start
                                },
                            ),
                            enabled = serviceStatus.state == ServiceState.Connected
                                    || serviceStatus.state == ServiceState.Stopped
                                    || serviceStatus.state == ServiceState.Idle,
                        ) {
                            when (serviceStatus.state) {
                                ServiceState.Stopped -> repository.startService()
                                ServiceState.Idle, ServiceState.Connected -> repository.stopService()
                                else -> {}
                            }
                        }
                        Menu(
                            text = stringResource(Res.string.service_mode),
                        ) {
                            val serviceMode by DataStore.configurationStore
                                .stringFlow(Key.SERVICE_MODE, Key.MODE_VPN)
                                .collectAsState(Key.MODE_VPN)
                            CheckboxItem(
                                text = stringResource(Res.string.service_mode_proxy),
                                checked = serviceMode == Key.MODE_PROXY,
                            ) {
                                if (serviceMode != Key.MODE_PROXY) {
                                    DataStore.serviceMode = Key.MODE_PROXY
                                    repository.reloadService()
                                }
                            }
                            CheckboxItem(
                                text = stringResource(Res.string.service_mode_vpn),
                                checked = serviceMode == Key.MODE_VPN,
                            ) {
                                if (serviceMode != Key.MODE_VPN) {
                                    DataStore.serviceMode = Key.MODE_VPN
                                    repository.reloadService()
                                }
                            }
                        }
                        Item(
                            text = stringResource(Res.string.exit),
                            icon = if (supportAdvancedTray) {
                                painterResource(Res.drawable.close)
                            } else {
                                null
                            },
                            mnemonic = if (supportAdvancedTray) {
                                'E'
                            } else {
                                null
                            },
                            onClick = ::exitGracefully,
                        )
                    }
                }

                Window(
                    onCloseRequest = { windowVisible = false },
                    state = windowState,
                    visible = windowVisible,
                    title = stringResource(Res.string.app_name),
                    icon = painterResource(Res.drawable.ic_launcher),
                ) {
                    AppTheme {
                        MainScreen(moveToBackground = {})
                    }
                }
            }
        }
    }

    private fun abortIfLinuxUiWithoutDisplay() {
        if (background) return
        if (PlatformInfo.platform != Platform.Linux) return
        val display = System.getenv("DISPLAY")?.trim().orEmpty()
        val wayland = System.getenv("WAYLAND_DISPLAY")?.trim().orEmpty()
        if (display.isNotEmpty() || wayland.isNotEmpty()) return
        Logs.e(
            "Linux UI needs DISPLAY or WAYLAND_DISPLAY (WSL: enable WSLg or set DISPLAY). " +
                "Use --background only if your setup supports tray without a display.",
        )
        exitProcess(1)
    }

    private fun shouldAutoConnectOnLaunch(): Boolean {
        return autoStart
                && DataStore.persistAcrossReboot
                && DataStore.selectedProxy > 0L
                && !DataStore.serviceState.started
    }

    private fun initDesktopRuntime() {
        fixComposePreferenceNode()
        val repository = createDesktopRepository()
        val filesDir = repository.filesDir.invariantDirectoryPathString()

        if (!many) {
            when (checkExistingInstance(filesDir, deepLinks)) {
                ExistingInstanceCheckResult.NotFound -> Unit
                ExistingInstanceCheckResult.ExistsNoDeepLink
                    if (autoStart) -> exitApplication()

                ExistingInstanceCheckResult.ExistsNoDeepLink,
                ExistingInstanceCheckResult.ExistsForwardFailed,
                    -> warnForExistInstanceAndExit(repository, filesDir)

                ExistingInstanceCheckResult.ExistsForwarded -> exitApplication()
            }
        }

        bootstrapDesktopRuntime(repository, startCommandServer = true)
    }

    /**
     * @return Exit code
     */
    private fun runTaskMode(taskId: String): Int {
        return runTaskMode(taskId, requireExistingInstance = false)
    }

    private fun runTaskMode(taskId: String, requireExistingInstance: Boolean): Int {
        DesktopTaskRegistry.require(taskId)
        val repository = createDesktopRepository()
        val filesDir = repository.filesDir.invariantDirectoryPathString()

        when (checkExistingTaskInstance(filesDir, taskId)) {
            ExistingTaskDispatchResult.NotFound -> {
                if (requireExistingInstance) {
                    System.err.println("No running daemon instance found")
                    return 2
                }
            }
            ExistingTaskDispatchResult.Forwarded -> return 0
            ExistingTaskDispatchResult.ForwardFailed -> return 1
        }

        bootstrapDesktopRuntime(repository, startCommandServer = false)
        return try {
            runBlocking {
                DesktopTaskRegistry.require(taskId).run()
            }
            0
        } catch (e: Exception) {
            Logs.e("run desktop task $taskId", e)
            1
        }
    }

    private fun runControlMode(command: String): Int {
        val normalized = command.trim().lowercase()
        val taskId = when (normalized) {
            "start" -> "service-start"
            "stop" -> "service-stop"
            "reload" -> "service-reload"
            "status" -> "service-status-snapshot"
            "ping" -> "service-ping"
            "export-log" -> "simple-log-export"
            "update-check" -> "app-update-check"
            "update-install" -> "app-update-install"
            else -> {
                System.err.println("Unknown --ctl command: $command")
                return 2
            }
        }
        val repository = createDesktopRepository()
        val expectedFile = when (normalized) {
            "status" -> repository.cacheDir.resolve(DESKTOP_CONTROL_STATUS_FILE)
            "ping" -> repository.cacheDir.resolve(DESKTOP_CONTROL_PING_FILE)
            "export-log" -> repository.cacheDir.resolve(DESKTOP_CONTROL_EXPORT_FILE)
            "update-check", "update-install" -> repository.cacheDir.resolve(DESKTOP_CONTROL_UPDATE_FILE)
            else -> null
        }
        val previousModified = expectedFile?.takeIf { it.exists() }?.lastModified() ?: -1L
        val code = runTaskMode(taskId, requireExistingInstance = true)
        if (code != 0) return code
        if (expectedFile != null) {
            val content = waitControlFileContent(expectedFile, previousModified)
            if (content.isNotBlank()) {
                println(content)
            }
        }
        return 0
    }

    private fun runPseudoGui(): Int {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        println("daHusiM pseudo-GUI (daemon control)")
        println("Daemon keeps running after you exit this menu.")
        while (true) {
            println()
            println("[1] Status")
            println("[2] Ping")
            println("[3] Start service")
            println("[4] Stop service")
            println("[5] Reload service")
            println("[6] Export simple log")
            println("[7] Check update")
            println("[8] Install update")
            println("[q] Quit")
            print("Select action: ")
            val command = when (reader.readLine()?.trim()?.lowercase()) {
                "1" -> "status"
                "2" -> "ping"
                "3" -> "start"
                "4" -> "stop"
                "5" -> "reload"
                "6" -> "export-log"
                "7" -> "update-check"
                "8" -> "update-install"
                "q", "quit", "exit" -> return 0
                else -> {
                    println("Unknown command")
                    continue
                }
            }
            val result = runControlMode(command)
            if (result != 0) {
                println("Command failed with code=$result")
            }
        }
    }

    private fun runDaemonMode() {
        registerMacOSOpenUriHandler()
        val repository = createDesktopRepository()
        applyDaemonProxyDefaults(repository)
        initDesktopRuntime()
        val runtimeRepository = resolveDesktopRepository()
        runCatching {
            runBlocking {
                SubscriptionUpdater.reconfigureUpdater()
                RouteAssetUpdater.reconfigureUpdater()
                SubscriptionCatalogCoordinator.syncIfDue(manual = false)
                AppUpdateCoordinator.checkForUpdate(manual = false)
            }
        }.onFailure {
            Logs.e("reconfigure desktop tasks on daemon startup", it)
        }
        if (shouldAutoConnectOnLaunch()) {
            runtimeRepository.startService()
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                DesktopBackgroundCoordinator.stop()
                runCatching {
                    runBlocking {
                        runtimeRepository.stopService()
                    }
                }
            },
        )
        val authMode = if (DataStore.inboundUsername.isBlank() && DataStore.inboundPassword.isBlank()) {
            "none"
        } else {
            "userpass"
        }
        println(
            "Daemon started in proxy mode: listen=0.0.0.0:${DataStore.mixedPort} auth=$authMode " +
                "(ctl: start|stop|reload|status|ping|export-log|update-check|update-install)",
        )
        CountDownLatch(1).await()
    }

    private fun fixComposePreferenceNode() {
        System.setProperty(PREFERENCE_NODE_PROPERTY_NAME, PREFERENCE_NODE_NAME)
    }

    private fun createDesktopRepository(): DesktopRepository {
        val baseDir = baseDir ?: File(System.getProperty("user.home"), ".config").resolve("dahusim")
        baseDir.mkdirs()
        return DesktopRepository(baseDir)
    }

    private fun bootstrapDesktopRuntime(
        repository: DesktopRepository,
        startCommandServer: Boolean,
    ) {
        DesktopAutoStart.initialize()
        initHusiKoin(repository)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        val cacheDir = repository.cacheDir.invariantDirectoryPathString()
        val filesDir = repository.filesDir.invariantDirectoryPathString()
        val externalAssetsDir = repository.externalAssetsDir.invariantDirectoryPathString()

        val rulesProvider = DataStore.rulesProvider
        val isOfficialProvider = rulesProvider == RuleProvider.OFFICIAL
        if (isOfficialProvider) {
            runBlocking {
                copyBundledRuleSetAssetsIfNeeded()
            }
        }
        Libcore.initCore(
            true,
            cacheDir,
            filesDir,
            externalAssetsDir,
            DataStore.logMaxLine,
            logLevel ?: DataStore.logLevel,
            isOfficialProvider,
            DataStore.isExpert,
        )
        loadCA(DataStore.certProvider)
        runBlocking {
            DefaultUserBootstrap.bootstrapAll()
        }
        DesktopLegacySchedulerCleanup.run()
        if (startCommandServer) {
            repository.boxService?.start()
            DesktopBackgroundCoordinator.start()
        }
    }

    private fun waitControlFileContent(file: File, previousModified: Long): String {
        repeat(40) {
            if (file.exists() && file.lastModified() > previousModified) {
                return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            }
            Thread.sleep(50)
        }
        return if (file.exists()) {
            runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        } else {
            ""
        }
    }

    private fun applyDaemonProxyDefaults(repository: DesktopRepository) {
        DataStore.daemonAllowOpenProxyInbound = true
        DataStore.serviceMode = Key.MODE_PROXY

        val configFileValues = readDaemonProxyConfig(repository.dataDir.resolve("daemon-proxy.conf"))
        val resolvedPort = daemonProxyPort
            ?: System.getenv(DAEMON_PROXY_ENV_PORT)?.trim()?.toIntOrNull()
            ?: configFileValues.port
        if (resolvedPort != null && resolvedPort in MIN_PORT..MAX_PORT) {
            DataStore.mixedPort = resolvedPort
        }

        // Daemon/headless default is network proxy for external clients.
        DataStore.allowAccess = configFileValues.allowAccess ?: true

        val resolvedAuth = daemonProxyAuth
            ?: System.getenv(DAEMON_PROXY_ENV_AUTH)?.trim()
            ?: configFileValues.auth
        applyDaemonProxyAuth(resolvedAuth)
    }

    private fun applyDaemonProxyAuth(rawAuth: String?) {
        val normalized = rawAuth?.trim().orEmpty()
        if (
            normalized.isEmpty() ||
            normalized.equals("none", ignoreCase = true) ||
            normalized.equals("off", ignoreCase = true) ||
            normalized.equals("disabled", ignoreCase = true)
        ) {
            DataStore.inboundUsername = ""
            DataStore.inboundPassword = ""
            return
        }
        val separator = normalized.indexOf(':')
        require(separator > 0 && separator < normalized.lastIndex) {
            "Invalid --proxy-auth format. Expected user:password or 'none'."
        }
        DataStore.inboundUsername = normalized.substring(0, separator)
        DataStore.inboundPassword = normalized.substring(separator + 1)
    }

    private fun readDaemonProxyConfig(file: File): DaemonProxyConfig {
        if (!file.isFile) return DaemonProxyConfig()
        val values = linkedMapOf<String, String>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0) return@forEach
            val key = trimmed.substring(0, idx).trim().lowercase()
            val value = trimmed.substring(idx + 1).trim()
            values[key] = value
        }
        val allowAccess = values["allow_access"]?.let {
            when (it.lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> null
            }
        }
        return DaemonProxyConfig(
            port = values["proxy_port"]?.toIntOrNull(),
            auth = values["proxy_auth"],
            allowAccess = allowAccess,
        )
    }

    private data class DaemonProxyConfig(
        val port: Int? = null,
        val auth: String? = null,
        val allowAccess: Boolean? = null,
    )
}

private fun registerMacOSOpenUriHandler() {
    if (!PlatformInfo.isMacOs) return
    try {
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return
        desktop.setOpenURIHandler { event ->
            DeepLinkDispatcher.emit(event.uri.toString())
        }
    } catch (e: Exception) {
        Logs.w("register macOS open-uri handler", e)
    }
}

private enum class ExistingInstanceCheckResult {
    NotFound,
    ExistsNoDeepLink,
    ExistsForwarded,
    ExistsForwardFailed,
}

private enum class ExistingTaskDispatchResult {
    NotFound,
    Forwarded,
    ForwardFailed,
}

private fun checkExistingInstance(
    socketBasePath: String,
    deepLinks: List<String>,
): ExistingInstanceCheckResult {
    val client = connectExistingClient(socketBasePath) ?: return ExistingInstanceCheckResult.NotFound
    return try {
        if (deepLinks.isEmpty()) {
            ExistingInstanceCheckResult.ExistsNoDeepLink
        } else if (forwardDeepLinks(client, deepLinks)) {
            ExistingInstanceCheckResult.ExistsForwarded
        } else {
            ExistingInstanceCheckResult.ExistsForwardFailed
        }
    } finally {
        client.close()
    }
}

private fun connectExistingClient(socketBasePath: String): Client? {
    val client = runCatching {
        Libcore.newClient(socketBasePath)
    }.getOrNull() ?: return null
    val helloSucceed = runCatching {
        client.hello()
    }.onFailure {
        Logs.w("probe existing desktop instance", it)
    }.isSuccess
    if (helloSucceed) return client
    runCatching {
        client.close()
    }
    return null
}

private fun forwardDeepLinks(client: Client, deepLinks: List<String>): Boolean {
    return runCatching {
        client.importDeepLinks(deepLinks.toStringIterator(deepLinks.size))
    }.onFailure {
        Logs.e(it)
    }.isSuccess
}

private fun checkExistingTaskInstance(
    socketBasePath: String,
    taskId: String,
): ExistingTaskDispatchResult {
    val client = connectExistingClient(socketBasePath) ?: return ExistingTaskDispatchResult.NotFound
    return try {
        if (forwardTask(client, taskId)) {
            ExistingTaskDispatchResult.Forwarded
        } else {
            ExistingTaskDispatchResult.ForwardFailed
        }
    } finally {
        client.close()
    }
}

private fun forwardTask(client: Client, taskId: String): Boolean {
    return runCatching {
        client.runTask(taskId)
    }.onFailure {
        Logs.e(it)
    }.isSuccess
}

private fun warnForExistInstanceAndExit(repository: DesktopRepository, socketBasePath: String) {
    val socketPath = socketBasePath + Libcore.Socket
    val title = runBlocking { repository.getString(Res.string.instance_already_running_title) }
    val message = runBlocking {
        repository.getString(Res.string.instance_already_running, socketPath)
    }
    try {
        JOptionPane.showMessageDialog(
            null,
            message,
            title,
            JOptionPane.WARNING_MESSAGE,
        )
    } catch (e: Exception) {
        System.err.println("$title: $message")
        System.err.println(e.message)
    }
    exitProcess(1)
}
