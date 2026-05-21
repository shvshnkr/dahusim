package fr.husi.database

import fr.husi.bg.GuardedProcessPool
import fr.husi.bg.initPlugins
import fr.husi.bg.launchPlugins
import fr.husi.fmt.buildConfig
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage
import fr.husi.libcore.Client
import fr.husi.libcore.Libcore
import fr.husi.plugin.PluginNotFoundException
import fr.husi.utils.closeQuietly
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File

/**
 * URL probe through a standalone sing-box instance for one profile (not the system VPN tunnel).
 * Used before connect and on open-network post-connect checks so BL-only reachability is not
 * mistaken for a working server on Google-OK uplink.
 */
internal object DirectProfileUrlProbe {

    suspend fun urlTestDelay(profile: ProxyEntity): Int? = coroutineScope {
        var client: Client? = null
        var processes: GuardedProcessPool? = null
        val cacheFiles = ArrayList<File>()
        var out: Int? = null
        try {
            client = Libcore.newClient(null)
            val config = buildConfig(profile, forTest = true)
            if (config.externalIndex.any { it.chain.isNotEmpty() }) {
                val pluginConfigs = initPlugins(config, false, cacheFiles)
                processes = GuardedProcessPool { Logs.w(it) }
                launchPlugins(config, pluginConfigs, processes, cacheFiles)
                delay(500L)
            }
            val ms = client.newInstanceURLTest(
                config.config,
                "",
                DataStore.connectionTestURL,
                DataStore.connectionTestTimeout,
            )
            if (ms > 0) {
                out = ms
            }
        } catch (e: PluginNotFoundException) {
            Logs.w("DirectProfileUrlProbe plugin: ${e.plugin}")
        } catch (e: Exception) {
            Logs.d("DirectProfileUrlProbe ${profile.displayName()}: ${e.readableMessage}")
        } finally {
            client?.closeQuietly()
            processes?.close(this@coroutineScope)
            cacheFiles.forEach { runCatching { it.delete() } }
        }
        out
    }
}
