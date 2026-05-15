package fr.husi.bg

import android.net.Network
import fr.husi.database.DataStore
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.libcore.InterfaceUpdateListener
import fr.husi.repository.resolveAndroidRepository
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.NetworkInterface

object DefaultNetworkMonitor {
    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null
    private var lastInterfaceName: String? = null
    private var lastInterfaceIndex: Int = -2
    private var lastConnectedState: Boolean = false
    private val access = Mutex()
    private var refCount = 0

    suspend fun start() {
        access.withLock {
            if (refCount++ > 0) return
            AndroidDefaultNetworkListener.start(this) {
                defaultNetwork = it
                checkDefaultInterfaceUpdate(it)
            }
            defaultNetwork = resolveAndroidRepository().connectivity.activeNetwork
        }
    }

    suspend fun stop() {
        access.withLock {
            if (refCount == 0) return
            if (--refCount > 0) return
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
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
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
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(interfaceName).index
                } catch (_: Exception) {
                    Thread.sleep(100)
                    continue
                }
                val ifaceChanged =
                    interfaceName != lastInterfaceName || interfaceIndex != lastInterfaceIndex
                if (ifaceChanged) {
                    val handoffWhileConnected =
                        DataStore.serviceState.connected &&
                            lastInterfaceName != null &&
                            lastInterfaceIndex >= 0
                    // #region agent log
                    simpleModeLog(
                        "SimpleMode",
                        "H15 net_iface_changed old=${lastInterfaceName ?: "none"}:$lastInterfaceIndex " +
                            "new=${interfaceName ?: "unknown"}:$interfaceIndex connected=${DataStore.serviceState.connected}",
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
                        ),
                    )
                    // #endregion
                    if (handoffWhileConnected) {
                        WhitelistNetworkRoutingState.onUnderlyingInterfaceHandoff(interfaceName)
                    }
                }
                lastInterfaceName = interfaceName
                lastInterfaceIndex = interfaceIndex
                lastConnectedState = DataStore.serviceState.connected
                listener.updateDefaultInterface(interfaceName, interfaceIndex)
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
            lastInterfaceName = null
            lastInterfaceIndex = -1
            lastConnectedState = DataStore.serviceState.connected
            listener.updateDefaultInterface("", -1)
        }
    }
}
