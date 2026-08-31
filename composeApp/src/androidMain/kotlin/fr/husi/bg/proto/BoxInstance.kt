package fr.husi.bg.proto

import fr.husi.bg.AbstractInstance
import fr.husi.bg.GuardedProcessPool
import fr.husi.bg.RuleSetBootstrapCallbacks
import fr.husi.bg.connectWithRuleSetBootstrap
import fr.husi.bg.initPlugins
import fr.husi.bg.launchPlugins
import fr.husi.bg.ruleSetBootstrapForcePreferLocal
import fr.husi.RuleProvider
import fr.husi.database.ProxyEntity
import fr.husi.fmt.ConfigBuildResult
import fr.husi.fmt.buildConfig
import fr.husi.database.DataStore
import fr.husi.ktx.Logs
import fr.husi.ktx.ensureMixedPortAvailable
import fr.husi.ktx.readableMessage
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.libcore.Libcore
import fr.husi.repository.resolveRepository
import fr.husi.utils.copyBundledRuleSetAssetsIfNeeded
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

    protected open fun buildConfig() {
        config = buildConfig(profile)
        emitRouteBuildDebug(profile, config)
    }

    protected open suspend fun loadConfig() {
        resolveRepository().boxService!!.newInstance(config.config)
    }

    open suspend fun init(isVPN: Boolean) {
        ensureMixedPortAvailable()
        ensureLocalRuleSetsSeeded()
        connectWithRuleSetBootstrap(
            callbacks = ruleSetBootstrapCallbacks(platform = "android"),
            onBeforeRetry = { cleanupRetryArtifacts() },
            initialPreferLocal = ruleSetBootstrapForcePreferLocal ||
                DataStore.activeWhitelistRestrictedNetwork,
        ) { _ ->
            buildConfig()
            pluginConfigs.clear()
            pluginConfigs.putAll(initPlugins(config, isVPN, cacheFiles))
            loadConfig()
        }
    }

    /**
     * Belt-and-braces seed before the first config build: the bg process copies bundled archives
     * on startup and Go extracts them async — a connect can race that. This step is idempotent
     * (version-compared, cheap) and keeps connect free of any github dependency: with local
     * geo/<tag>.srs present, [fr.husi.fmt.buildConfig] never emits remote rule-set URLs.
     */
    private suspend fun ensureLocalRuleSetsSeeded() {
        val provider = DataStore.rulesProvider
        if (provider != RuleProvider.OFFICIAL) {
            // Bundled rule-sets are OFFICIAL content; other providers populate geo/ via the
            // asset update flow (manual or background through a live tunnel).
            simpleModeLog(
                "SimpleMode",
                "H36 android_ruleset_local source=none provider=${rulesProviderLabel(provider)} " +
                    "note=non_official_provider",
            )
            return
        }
        if (hasLocalRuleSetFiles()) {
            simpleModeLog(
                "SimpleMode",
                "H36 android_ruleset_local source=${ruleSetLocalSource()} " +
                    "provider=${rulesProviderLabel(provider)}",
            )
            return
        }
        simpleModeLog(
            "SimpleMode",
            "H36 android_ruleset_seed start source=none provider=${rulesProviderLabel(provider)}",
        )
        copyBundledRuleSetAssetsIfNeeded()
        Libcore.extractAssets()
        simpleModeLog(
            "SimpleMode",
            "H36 android_ruleset_seed done source=${ruleSetLocalSource()} files=${geoRuleSetFileCount()}",
        )
    }

    private fun ruleSetLocalSource(): String {
        val repository = resolveRepository()
        val bundledVersions = listOf(
            repository.filesDir.resolve("sing-box/geoip.version.txt"),
            repository.filesDir.resolve("sing-box/geosite.version.txt"),
        ).filter { it.isFile }
        val externalVersions = listOf(
            repository.externalAssetsDir.resolve("geoip.version.txt"),
            repository.externalAssetsDir.resolve("geosite.version.txt"),
        ).filter { it.isFile }
        if (bundledVersions.isNotEmpty() && externalVersions.isNotEmpty()) {
            val matchesBundled = bundledVersions.any { bundled ->
                externalVersions.firstOrNull { it.name == bundled.name }?.let { external ->
                    external.readBytes().contentEquals(bundled.readBytes())
                } == true
            }
            if (matchesBundled) return "bundled"
        }
        return if (externalVersions.isNotEmpty()) "existing" else "legacy"
    }

    private fun geoRuleSetFileCount(): Int {
        val geoDir = resolveRepository().externalAssetsDir.resolve("geo")
        return geoDir.listFiles()?.count { it.extension.equals("srs", ignoreCase = true) } ?: 0
    }

    private fun ruleSetBootstrapCallbacks(platform: String) = RuleSetBootstrapCallbacks(
        hasLocalRuleSetFiles = { hasLocalRuleSetFiles() },
        onAttempt = { preferLocal ->
            simpleModeLog(
                "SimpleMode",
                "H36 ${platform}_ruleset_bootstrap profileId=${profile.id} rulesProvider=${DataStore.rulesProvider} " +
                    "preferLocal=$preferLocal localGeo=${hasLocalRuleSetFiles()} " +
                    "forcedByWl=${DataStore.activeWhitelistRestrictedNetwork} " +
                    "route=singbox_remote_http provider=${rulesProviderLabel(DataStore.rulesProvider)}",
            )
        },
        onBootstrapFailure = { preferLocal, error ->
            simpleModeLog(
                "SimpleMode",
                "H36 ${platform}_ruleset_bootstrap_failed preferLocal=$preferLocal " +
                    "localGeo=${hasLocalRuleSetFiles()} err=${error.readableMessage}",
            )
        },
        onRetryWithLocal = {
            simpleModeLog(
                "SimpleMode",
                "H36 ${platform}_ruleset_retry mode=local reason=remote_ruleset_bootstrap_failed " +
                    "note=github_raw_unstable_on_mobile",
            )
        },
    )

    internal fun hasLocalRuleSetFiles(): Boolean {
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
