package fr.husi.bg

import android.net.Network
import fr.husi.database.DataStore
import fr.husi.simplemode.SimpleModeCarrierReconnect
import fr.husi.simplemode.SimpleModeSessionHealth
import fr.husi.libcore.InterfaceUpdateListener
import fr.husi.repository.resolveAndroidRepository
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

object DefaultNetworkMonitor {
    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null
    private var lastInterfaceName: String? = null
    private var lastInterfaceIndex: Int = -2
    /** Kept across brief default-network loss so Wi‑Fi→cellular handoff still runs while VPN is up. */
    private var previousInterfaceForHandoff: String? = null
    /** Set when default network is lost while VPN is up (same-iface Wi‑Fi reconnect, DHCP renew, etc.). */
    private var underlyingCarrierLostWhileConnected = false
    private var underlyingCarrierLostAtMs = 0L
    private var lastConnectedState: Boolean = false
    private val access = Mutex()
    private var refCount = 0
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val interfaceCheckGeneration = AtomicInteger(0)
    private var lastHandoffDispatchAt = 0L
    private var restoreWatchdogJob: Job? = null

    private const val RESTORE_WATCHDOG_POLL_MS = 2_000L
    private const val RESTORE_WATCHDOG_MAX_MS = 120_000L

    private data class PolledInterface(
        val interfaceName: String,
        val interfaceIndex: Int,
    )

    suspend fun start() {
        access.withLock {
            if (refCount++ > 0) return
            AndroidDefaultNetworkListener.start(this) {
                defaultNetwork = it
                scheduleDefaultInterfaceCheck(it)
            }
            defaultNetwork = resolveAndroidRepository().connectivity.activeNetwork
        }
    }

    suspend fun stop() {
        access.withLock {
            if (refCount == 0) return
            if (--refCount > 0) return
            if (!SimpleModeCarrierReconnect.isPendingValid()) {
                stopCarrierRestoreWatchdog()
            }
            AndroidDefaultNetworkListener.stop(this)
        }
    }

    suspend fun <T> withDefaultNetwork(block: suspend (Network) -> T): T {
        start()
        try {
            return block(require())
        } finally {
            stop()
        }
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return AndroidDefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        scheduleDefaultInterfaceCheck(defaultNetwork)
    }

    private fun scheduleDefaultInterfaceCheck(newNetwork: Network?) {
        val generation = interfaceCheckGeneration.incrementAndGet()
        monitorScope.launch {
            checkDefaultInterfaceUpdate(newNetwork, generation)
        }
    }

    private fun shouldContinueRestoreWatchdog(): Boolean =
        UnderlyingCarrierState.awaitingRestore || SimpleModeCarrierReconnect.isPendingValid()

    fun schedulePendingReconnectWatchdog() {
        if (!SimpleModeCarrierReconnect.isPendingValid()) return
        if (restoreWatchdogJob?.isActive == true) return
        startCarrierRestoreWatchdog()
    }

    private fun startCarrierRestoreWatchdog() {
        restoreWatchdogJob?.cancel()
        restoreWatchdogJob = monitorScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - startedAt < RESTORE_WATCHDOG_MAX_MS) {
                if (!shouldContinueRestoreWatchdog()) break
                delay(RESTORE_WATCHDOG_POLL_MS)
                if (!shouldContinueRestoreWatchdog()) break
                val polled = pollActiveUnderlyingNetwork() ?: continue
                if (!VpnTunnelHandoffSuppress.isPollableUplink(polled.interfaceName)) continue
                simpleModeLog(
                    "SimpleMode",
                    "H15 carrier_restore_watchdog_poll iface=${polled.interfaceName} index=${polled.interfaceIndex} " +
                        "connected=${DataStore.serviceState.connected}",
                )
                val vpnSessionActive = DataStore.serviceState.connected ||
                    DataStore.serviceState == ServiceState.Connecting
                val handoffReason = UnderlyingNetworkHandoffPolicy.evaluate(
                    UnderlyingNetworkHandoffPolicy.Snapshot(
                        vpnSessionActive = vpnSessionActive,
                        interfaceName = polled.interfaceName,
                        interfaceIndex = polled.interfaceIndex,
                        lastInterfaceName = lastInterfaceName,
                        lastInterfaceIndex = lastInterfaceIndex,
                        previousInterfaceForHandoff = previousInterfaceForHandoff,
                        underlyingCarrierLostWhileConnected = underlyingCarrierLostWhileConnected,
                    ),
                )
                if (handoffReason != null && DataStore.serviceState.connected) {
                    dispatchUnderlyingHandoff(
                        interfaceName = polled.interfaceName,
                        interfaceIndex = polled.interfaceIndex,
                        handoffReason = handoffReason,
                        source = "watchdog",
                    )
                    break
                }
                if (underlyingCarrierLostWhileConnected || UnderlyingCarrierState.awaitingRestore) {
                    finishCarrierRestore(
                        interfaceName = polled.interfaceName,
                        interfaceIndex = polled.interfaceIndex,
                    )
                }
                if (!DataStore.serviceState.connected) {
                    SimpleModeCarrierReconnect.tryResumeIfDue("watchdog")
                    if (!SimpleModeCarrierReconnect.isPendingValid()) break
                    continue
                }
                break
            }
            restoreWatchdogJob = null
        }
    }

    private fun stopCarrierRestoreWatchdog() {
        restoreWatchdogJob?.cancel()
        restoreWatchdogJob = null
    }

    private fun pollActiveUnderlyingNetwork(): PolledInterface? {
        val network = resolveAndroidRepository().connectivity.activeNetwork ?: return null
        val interfaceName =
            resolveAndroidRepository().connectivity.getLinkProperties(network)?.interfaceName
                ?: return null
        val interfaceIndex = try {
            NetworkInterface.getByName(interfaceName)?.index ?: return null
        } catch (_: Exception) {
            return null
        }
        return PolledInterface(interfaceName, interfaceIndex)
    }

    private fun finishCarrierRestore(interfaceName: String, interfaceIndex: Int) {
        UnderlyingCarrierState.onCarrierRestored()
        underlyingCarrierLostWhileConnected = false
        underlyingCarrierLostAtMs = 0L
        lastInterfaceName = interfaceName
        lastInterfaceIndex = interfaceIndex
        previousInterfaceForHandoff = interfaceName
        stopCarrierRestoreWatchdog()
    }

    private fun dispatchUnderlyingHandoff(
        interfaceName: String?,
        interfaceIndex: Int,
        handoffReason: String,
        source: String,
        ifaceChanged: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastHandoffDispatchAt < 1_200L) {
            simpleModeLog(
                "SimpleMode",
                "H-D2 network_handoff_coalesced reason=$handoffReason iface=${interfaceName ?: "unknown"} source=$source",
            )
            return
        }
        lastHandoffDispatchAt = now
        val elapsedFromLossMs = if (underlyingCarrierLostAtMs > 0L) {
            (now - underlyingCarrierLostAtMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        if (DataStore.simpleMode && DataStore.serviceState.connected && elapsedFromLossMs >= 10_000L) {
            SimpleModeSessionHealth.triggerQuickCheck(
                "net_available_after_loss gapMs=$elapsedFromLossMs",
            )
        }
        val interfaceRebound = handoffReason == UnderlyingNetworkHandoffPolicy.REASON_LINK_REBOUND
        simpleModeLog(
            "SimpleMode",
            "H-D2 network_handoff_triggered from=${previousInterfaceForHandoff ?: "none"} " +
                "to=$interfaceName reason=$handoffReason lossMs=$elapsedFromLossMs " +
                "rebound=$interfaceRebound source=$source",
        )
        if (interfaceName != null) {
            previousInterfaceForHandoff = interfaceName
        }
        if (ifaceChanged && interfaceName != null) {
            lastInterfaceName = interfaceName
            lastInterfaceIndex = interfaceIndex
        }
        UnderlyingCarrierState.onCarrierRestored()
        underlyingCarrierLostWhileConnected = false
        underlyingCarrierLostAtMs = 0L
        stopCarrierRestoreWatchdog()
        WhitelistNetworkRoutingState.onUnderlyingInterfaceHandoff(
            iface = interfaceName,
            handoffReason = handoffReason,
            elapsedFromLossMs = elapsedFromLossMs,
            interfaceRebound = interfaceRebound,
        )
    }

    private suspend fun checkDefaultInterfaceUpdate(newNetwork: Network?, generation: Int) {
        if (generation != interfaceCheckGeneration.get()) return
        val listener = listener ?: return
        if (newNetwork != null) {
            val interfaceName =
                resolveAndroidRepository().connectivity.getLinkProperties(newNetwork)?.interfaceName
            // #region agent log
            simpleModeLog(
                "SimpleMode",
                "H15 net_event event=available iface=${interfaceName ?: "unknown"} " +
                    "connected=${DataStore.serviceState.connected}",
            )
            simpleModeDebugEvent(
                runId = "network-switch",
                hypothesisId = "NET-CALLBACK",
                location = "DefaultNetworkMonitor.checkDefaultInterfaceUpdate",
                message = "Default network available",
                data = mapOf(
                    "iface" to (interfaceName ?: "unknown"),
                    "connected" to DataStore.serviceState.connected.toString(),
                ),
            )
            // #endregion
            for (times in 0 until 10) {
                if (generation != interfaceCheckGeneration.get()) return
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(interfaceName).index
                } catch (_: Exception) {
                    delay(100)
                    continue
                }
                val ifaceChanged =
                    interfaceName != lastInterfaceName || interfaceIndex != lastInterfaceIndex
                val vpnSessionActive = DataStore.serviceState.connected ||
                    DataStore.serviceState == ServiceState.Connecting
                val handoffReason = UnderlyingNetworkHandoffPolicy.evaluate(
                    UnderlyingNetworkHandoffPolicy.Snapshot(
                        vpnSessionActive = vpnSessionActive,
                        interfaceName = interfaceName,
                        interfaceIndex = interfaceIndex,
                        lastInterfaceName = lastInterfaceName,
                        lastInterfaceIndex = lastInterfaceIndex,
                        previousInterfaceForHandoff = previousInterfaceForHandoff,
                        underlyingCarrierLostWhileConnected = underlyingCarrierLostWhileConnected,
                    ),
                )
                if (ifaceChanged) {
                    val prevHandoff = previousInterfaceForHandoff
                    // #region agent log
                    simpleModeLog(
                        "SimpleMode",
                        "H15 net_iface_changed old=${lastInterfaceName ?: "none"}:$lastInterfaceIndex " +
                            "new=${interfaceName ?: "unknown"}:$interfaceIndex connected=${DataStore.serviceState.connected} " +
                            "handoffFrom=${prevHandoff ?: "none"} handoff=${handoffReason != null} " +
                            "handoffReason=${handoffReason ?: "none"} carrierLost=$underlyingCarrierLostWhileConnected",
                    )
                    simpleModeDebugEvent(
                        runId = "network-switch",
                        hypothesisId = "NET-IFACE-CHANGE",
                        location = "DefaultNetworkMonitor.checkDefaultInterfaceUpdate",
                        message = "Interface changed",
                        data = mapOf(
                            "oldName" to (lastInterfaceName ?: "none"),
                            "oldIndex" to lastInterfaceIndex.toString(),
                            "newName" to (interfaceName ?: "unknown"),
                            "newIndex" to interfaceIndex.toString(),
                            "connected" to DataStore.serviceState.connected.toString(),
                            "handoffReason" to (handoffReason ?: "none"),
                        ),
                    )
                    // #endregion
                }
                if (handoffReason != null) {
                    dispatchUnderlyingHandoff(
                        interfaceName = interfaceName,
                        interfaceIndex = interfaceIndex,
                        handoffReason = handoffReason,
                        source = "callback",
                        ifaceChanged = ifaceChanged,
                    )
                } else if (underlyingCarrierLostWhileConnected && interfaceName != null) {
                    finishCarrierRestore(interfaceName, interfaceIndex)
                    if (!DataStore.serviceState.connected) {
                        SimpleModeCarrierReconnect.tryResumeIfDue("callback_restore")
                    }
                }
                if (interfaceName != null) {
                    previousInterfaceForHandoff = interfaceName
                }
                lastInterfaceName = interfaceName
                lastInterfaceIndex = interfaceIndex
                lastConnectedState = DataStore.serviceState.connected
                listener.updateDefaultInterface(interfaceName, interfaceIndex)
                break
            }
        } else {
            // #region agent log
            simpleModeLog(
                "SimpleMode",
                "H15 net_event event=lost connected=${DataStore.serviceState.connected} " +
                    "lastIface=${lastInterfaceName ?: "none"}:$lastInterfaceIndex",
            )
            simpleModeDebugEvent(
                runId = "network-switch",
                hypothesisId = "NET-CALLBACK",
                location = "DefaultNetworkMonitor.checkDefaultInterfaceUpdate",
                message = "Default network lost",
                data = mapOf(
                    "connected" to DataStore.serviceState.connected.toString(),
                    "lastIface" to (lastInterfaceName ?: "none"),
                    "lastIndex" to lastInterfaceIndex.toString(),
                ),
            )
            // #endregion
            if (DataStore.serviceState.connected) {
                underlyingCarrierLostWhileConnected = true
                underlyingCarrierLostAtMs = System.currentTimeMillis()
                UnderlyingCarrierState.onCarrierLost(vpnConnected = true)
                startCarrierRestoreWatchdog()
            } else {
                previousInterfaceForHandoff = null
                underlyingCarrierLostWhileConnected = false
                underlyingCarrierLostAtMs = 0L
                UnderlyingCarrierState.onCarrierLost(vpnConnected = false)
            }
            lastInterfaceName = null
            lastInterfaceIndex = -1
            lastConnectedState = DataStore.serviceState.connected
            listener.updateDefaultInterface("", -1)
        }
    }
}
