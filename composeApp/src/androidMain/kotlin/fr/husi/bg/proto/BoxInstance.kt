package fr.husi.bg.proto

import fr.husi.bg.AbstractInstance
import fr.husi.bg.GuardedProcessPool
import fr.husi.bg.initPlugins
import fr.husi.bg.launchPlugins
import fr.husi.RuleProvider
import fr.husi.database.ProxyEntity
import fr.husi.fmt.ConfigBuildResult
import fr.husi.fmt.buildConfig
import fr.husi.database.DataStore
import fr.husi.ktx.Logs
import fr.husi.ktx.ensureMixedPortAvailable
import fr.husi.ktx.readableMessage
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import java.io.File
import kotlin.system.exitProcess

abstract class BoxInstance(
    val profile: ProxyEntity,
) : AbstractInstance {

    lateinit var config: ConfigBuildResult

    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    private val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    fun isInitialized(): Boolean {
        return ::config.isInitialized && resolveRepository().boxService?.hasInstance() == true
    }

    protected open fun buildConfig(preferLocalRuleSet: Boolean = false) {
        config = buildConfig(profile, preferLocalRuleSet = preferLocalRuleSet)
        emitRouteBuildDebug(profile, config)
    }

    protected open suspend fun loadConfig() {
        resolveRepository().boxService!!.newInstance(config.config)
    }

    open suspend fun init(isVPN: Boolean) {
        ensureMixedPortAvailable()
        var localRuleSetRetry = false
        while (true) {
            try {
                simpleModeLog(
                    "SimpleMode",
                    "H36 android_ruleset_bootstrap profileId=${profile.id} rulesProvider=${DataStore.rulesProvider} " +
                        "preferLocal=$localRuleSetRetry localGeo=${hasLocalRuleSetFiles()} " +
                        "route=singbox_remote_http provider=${rulesProviderLabel(DataStore.rulesProvider)}",
                )
                buildConfig(preferLocalRuleSet = localRuleSetRetry)
                pluginConfigs.clear()
                pluginConfigs.putAll(initPlugins(config, isVPN, cacheFiles))
                loadConfig()
                return
            } catch (error: Throwable) {
                if (isRuleSetBootstrapFailure(error)) {
                    simpleModeLog(
                        "SimpleMode",
                        "H36 android_ruleset_bootstrap_failed preferLocal=$localRuleSetRetry " +
                            "localGeo=${hasLocalRuleSetFiles()} err=${error.readableMessage}",
                    )
                }
                if (!localRuleSetRetry && isRuleSetBootstrapFailure(error) && hasLocalRuleSetFiles()) {
                    localRuleSetRetry = true
                    simpleModeLog(
                        "SimpleMode",
                        "H36 android_ruleset_retry mode=local reason=remote_ruleset_bootstrap_failed " +
                            "note=github_raw_unstable_on_mobile",
                    )
                    cleanupRetryArtifacts()
                    continue
                }
                throw error
            }
        }
    }

    private fun isRuleSetBootstrapFailure(error: Throwable): Boolean {
        return error.readableMessage.contains("initialize rule-set", ignoreCase = true)
    }

    private fun hasLocalRuleSetFiles(): Boolean {
        val geoDir = resolveRepository().externalAssetsDir.resolve("geo")
        return geoDir.exists() &&
            geoDir.isDirectory &&
            geoDir.listFiles()?.any { it.extension.equals("srs", ignoreCase = true) } == true
    }

    private fun cleanupRetryArtifacts() {
        pluginConfigs.clear()
        cacheFiles.forEach { file ->
            runCatching { file.delete() }
        }
        cacheFiles.clear()
    }

    private fun rulesProviderLabel(provider: Int): String = when (provider) {
        RuleProvider.OFFICIAL -> "official"
        RuleProvider.LOYALSOLDIER -> "loyalsoldier"
        RuleProvider.CHOCOLATE4U -> "chocolate4u"
        RuleProvider.CUSTOM -> "custom"
        else -> "fallback_sagernet"
    }

    override fun launch() {
        for ((chain) in config.externalIndex) {
            chain.entries.forEach { (port, _) ->
                if (externalInstances.containsKey(port)) {
                    externalInstances[port]!!.launch()
                }
            }
        }
        launchPlugins(config, pluginConfigs, processes, cacheFiles)
        resolveRepository().boxService!!.startInstance()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (resolveRepository().boxService?.hasInstance() == true) {
            try {
                resolveRepository().boxService!!.stopInstance()
            } catch (e: Exception) {
                Logs.w(e)
                // Kill the process if it is not closed properly to clean exist inbound listeners.
                // Do not kill in main process, whose test not starts any listener.
                if (!resolveRepository().isMainProcess && e.readableMessage.contains("sing-box did not close in time")) runOnDefaultDispatcher {
                    delay(500) // Wait for error handling
                    exitProcess(0)
                }
            }
        }
    }

}
