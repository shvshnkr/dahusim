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
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.SimpleModeMessengerProbe
import fr.husi.utils.closeQuietly
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * URL probe through a standalone sing-box instance for one profile (not the system VPN tunnel).
 * Used before connect (per-profile sing-box). On WL uplink probes BS targets through the profile;
 * ya/dzen are uplink-only and must not be used here (see docs/BS_CS_NETWORK.md).
 */
internal object DirectProfileUrlProbe {

    private val messengerSecondaryDelays = ConcurrentHashMap<Long, Int>()

    fun clearMessengerSecondaryDelays() {
        messengerSecondaryDelays.clear()
    }

    fun messengerSecondaryDelay(profileId: Long): Int? = messengerSecondaryDelays[profileId]

    suspend fun urlTestDelay(profile: ProxyEntity, whitelistOnly: Boolean = false): Int? =
        urlTestDelay(profile, whitelistOnly, tier = SimpleModeHealthRoute.ProbeTier.PRIMARY)

    suspend fun urlTestDelay(
        profile: ProxyEntity,
        whitelistOnly: Boolean,
        tier: SimpleModeHealthRoute.ProbeTier,
    ): Int? = coroutineScope {
        val phase = "prepare"
        val compositeRequired = SimpleModeMessengerProbe.compositeRequired(whitelistOnly)
        if (tier == SimpleModeHealthRoute.ProbeTier.CONFIRM) {
            for (url in SimpleModeHealthRoute.probeUrlPlan(phase, whitelistOnly, tier)) {
                urlTestDelay(profile, url, whitelistOnly, tier)?.let { return@coroutineScope it }
            }
            return@coroutineScope null
        }
        if (compositeRequired) {
            return@coroutineScope messengerCompositeDelay(profile, whitelistOnly, tier)?.compositeDelayMs
        }
        val primaryUrls = SimpleModeHealthRoute.probeUrlPlan(
            phase,
            whitelistOnly,
            SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        var lastError: String? = null
        for (url in primaryUrls) {
            urlTestDelay(profile, url, whitelistOnly)?.let { return@coroutineScope it }
            lastError = "primary url test failed"
        }
        if (
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = phase,
                    whitelistOnly = whitelistOnly,
                    lastProbeError = lastError,
                    primaryProbeFailed = true,
                ),
            )
        ) {
            val confirmUrls = SimpleModeHealthRoute.probeUrlPlan(
                phase,
                whitelistOnly,
                SimpleModeHealthRoute.ProbeTier.CONFIRM,
            )
            for (url in confirmUrls) {
                if (url in primaryUrls) continue
                urlTestDelay(profile, url, whitelistOnly, SimpleModeHealthRoute.ProbeTier.CONFIRM)
                    ?.let { return@coroutineScope it }
            }
        }
        null
    }

    suspend fun messengerCompositeDelay(
        profile: ProxyEntity,
        whitelistOnly: Boolean,
        tier: SimpleModeHealthRoute.ProbeTier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        knownWebDelayMs: Int? = null,
    ): SimpleModeMessengerProbe.PrepareResult? {
        val webMs = knownWebDelayMs?.takeIf { it > 0 }
            ?: urlTestDelay(profile, SimpleModeMessengerProbe.WEB_URL, whitelistOnly, tier)
        val webOk = webMs != null && webMs > 0
        if (!webOk) {
            SimpleModeMessengerProbe.logPrepareProbe(
                profileId = profile.id,
                webOk = false,
                dcRequiredOk = false,
                dcSecondaryOk = null,
            )
            messengerSecondaryDelays.remove(profile.id)
            return null
        }
        val dcMs = urlTestDelay(profile, SimpleModeMessengerProbe.DC_REQUIRED_URL, whitelistOnly, tier)
        val dcOk = dcMs != null && dcMs > 0
        val secondaryMs = if (dcOk) {
            urlTestDelay(profile, SimpleModeMessengerProbe.DC_SECONDARY_URL, whitelistOnly, tier)
        } else {
            null
        }
        if (secondaryMs != null && secondaryMs > 0) {
            messengerSecondaryDelays[profile.id] = secondaryMs
        } else {
            messengerSecondaryDelays.remove(profile.id)
        }
        SimpleModeMessengerProbe.logPrepareProbe(
            profileId = profile.id,
            webOk = true,
            dcRequiredOk = dcOk,
            dcSecondaryOk = secondaryMs?.let { it > 0 },
        )
        val result = SimpleModeMessengerProbe.PrepareResult(
            webDelayMs = webMs!!,
            dcRequiredDelayMs = dcMs,
            dcSecondaryDelayMs = secondaryMs,
        )
        return result.takeIf { it.ready }
    }

    suspend fun urlTestDelay(profile: ProxyEntity, testUrl: String, whitelistOnly: Boolean = false): Int? =
        urlTestDelay(profile, testUrl, whitelistOnly, SimpleModeHealthRoute.ProbeTier.PRIMARY)

    suspend fun urlTestDelay(
        profile: ProxyEntity,
        testUrl: String,
        whitelistOnly: Boolean,
        tier: SimpleModeHealthRoute.ProbeTier,
    ): Int? = coroutineScope {
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
            val timeoutMs = if (whitelistOnly) {
                (DataStore.connectionTestTimeout * 2).coerceIn(5_000, 8_000)
            } else {
                DataStore.connectionTestTimeout
            }
            val ms = client.newInstanceURLTest(
                config.config,
                "",
                testUrl,
                timeoutMs,
            )
            if (ms > 0) {
                out = ms
            }
        } catch (e: PluginNotFoundException) {
            Logs.w("DirectProfileUrlProbe plugin: ${e.plugin}")
        } catch (e: Exception) {
            val msg = e.readableMessage
            Logs.d("DirectProfileUrlProbe ${profile.displayName()} $testUrl: $msg")
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk(
                error = msg,
                whitelistOnly = whitelistOnly,
                probeUrl = testUrl,
            )?.let { out = it }
        } finally {
            client?.closeQuietly()
            processes?.close(this@coroutineScope)
            cacheFiles.forEach { runCatching { it.delete() } }
        }
        out
    }
}
