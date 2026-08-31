package fr.husi.bg.proto

import fr.husi.BuildConfig
import fr.husi.aidl.SpeedDisplayData
import fr.husi.bg.BaseService
import fr.husi.bg.SpeedStats
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.ktx.Logs
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var displayProfileName = profile.displayNameForService()

    var trafficLooper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        Logs.d(config.config)
        if (DataStore.isExpert) Logs.d("trafficMap: " + config.trafficMap.toString())
        val configJson = config.config
        val strictRouteEnabled = "\"strict_route\"\\s*:\\s*true".toRegex().containsMatchIn(configJson)
        val autoRouteEnabled = "\"auto_route\"\\s*:\\s*true".toRegex().containsMatchIn(configJson)
        val autoRedirectEnabled = "\"auto_redirect\"\\s*:\\s*true".toRegex().containsMatchIn(configJson)
        val gvisorStack = "\"stack\"\\s*:\\s*\"gvisor\"".toRegex().containsMatchIn(configJson)
        val tunIncludePackage = "\"include_package\"\\s*:".toRegex().containsMatchIn(configJson)
        val tunExcludePackage = "\"exclude_package\"\\s*:".toRegex().containsMatchIn(configJson)
        val packageRuleCount = "\"package_name\"\\s*:".toRegex().findAll(configJson).count()
        val routeFinalBlock = "\"final\"\\s*:\\s*\"block\"".toRegex().containsMatchIn(configJson)
        // #region agent log
        simpleModeLog(
            "SimpleMode",
            "H13 cfg_summary strictRoute=$strictRouteEnabled autoRoute=$autoRouteEnabled " +
                "autoRedirect=$autoRedirectEnabled gvisor=$gvisorStack pkgRules=$packageRuleCount " +
                "finalBlock=$routeFinalBlock " +
                "tunInclude=$tunIncludePackage tunExclude=$tunExcludePackage",
        )
        // #endregion
        // #region agent log
        simpleModeLog(
            "SimpleMode",
            "H13 cfg_flags tunStrictRoute=${DataStore.tunStrictRoute} tunAutoRedirect=${DataStore.tunAutoRedirect} " +
                "tunImplementation=${DataStore.tunImplementation} " +
                "proxyApps=${DataStore.proxyApps} bypassMode=${DataStore.bypassMode} " +
                "allowAppsBypassVpn=${DataStore.allowAppsBypassVpn} selectedPackages=${DataStore.packages.size}",
        )
        // #endregion
    }

    override suspend fun init(isVPN: Boolean) {
        super.init(isVPN)
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override fun launch() {
        super.launch() // start box
        runOnDefaultDispatcher {
            val data = service?.data ?: return@runOnDefaultDispatcher
            trafficLooper = TrafficLooper(
                box = resolveRepository().boxService!!,
                config = config,
                scope = this,
                onSpeedUpdate = { stats ->
                    val speed = stats.toSpeedDisplayData()
                    data.binder.notifySpeed(speed)
                    data.notification.apply {
                        if (canPostSpeed()) onSpeed(speed)
                    }
                },
            )
            trafficLooper?.start()
        }
    }

    override fun close() {
        super.close()
        runBlocking {
            trafficLooper?.stop()
            trafficLooper = null
        }
    }
}

private fun SpeedStats.toSpeedDisplayData() = SpeedDisplayData(
    txRateProxy = txRateProxy,
    rxRateProxy = rxRateProxy,
    txRateDirect = txRateDirect,
    rxRateDirect = rxRateDirect,
    txTotal = txTotal,
    rxTotal = rxTotal,
)
