package fr.husi.database

import fr.husi.bg.GuardedProcessPool
import fr.husi.bg.initPlugins
import fr.husi.bg.launchPlugins
import fr.husi.fmt.PrepareTestConfigBuilder
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage
import fr.husi.libcore.Client
import fr.husi.libcore.Libcore
import fr.husi.libcore.newInstanceGroupURLTestCompat
import fr.husi.plugin.PluginNotFoundException
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.SimpleModeMessengerProbe
import fr.husi.utils.closeQuietly
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File

/** Batch prepare URL probe via ephemeral sing-box urltest group (one instance for many profiles). */
internal object PrepareGroupUrlProbe {

    suspend fun urlTestDelays(
        profiles: List<ProxyEntity>,
        whitelistOnly: Boolean,
        tier: SimpleModeHealthRoute.ProbeTier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
    ): Map<Long, Int>? = coroutineScope {
        if (profiles.isEmpty()) return@coroutineScope null
        val testUrl = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = whitelistOnly,
            tier = tier,
        ).firstOrNull() ?: return@coroutineScope null
        val built = PrepareTestConfigBuilder.build(profiles, testUrl) ?: return@coroutineScope null
        var client: Client? = null
        var processes: GuardedProcessPool? = null
        val cacheFiles = ArrayList<File>()
        try {
            client = Libcore.newClient(null)
            if (built.externalIndex.any { it.chain.isNotEmpty() }) {
                val pluginConfigs = initPlugins(built, false, cacheFiles)
                processes = GuardedProcessPool { Logs.w(it) }
                launchPlugins(built, pluginConfigs, processes, cacheFiles)
                delay(500L)
            }
            val timeoutMs = if (whitelistOnly) {
                (DataStore.connectionTestTimeout * 2).coerceIn(5_000, 8_000)
            } else {
                DataStore.connectionTestTimeout
            }
            val tagDelays = client.newInstanceGroupURLTestCompat(
                built.config,
                PrepareTestConfigBuilder.TAG_PREPARE_GROUP,
                testUrl,
                timeoutMs,
            ) ?: run {
                simpleModeLog(
                    "SimpleMode",
                    "H25 batch_urltest_unavailable profiles=${profiles.size}",
                )
                return@coroutineScope null
            }
            val webPass = HashMap<Long, Int>(tagDelays.size)
            for ((tag, ms) in tagDelays) {
                val profileId = built.tagToID[tag] ?: continue
                if (ms > 0) webPass[profileId] = ms
            }
            if (webPass.isEmpty()) return@coroutineScope null
            if (!SimpleModeMessengerProbe.compositeRequired(whitelistOnly) ||
                tier != SimpleModeHealthRoute.ProbeTier.PRIMARY
            ) {
                return@coroutineScope webPass
            }
            val profilesById = profiles.associateBy { it.id }
            val out = HashMap<Long, Int>(webPass.size)
            for ((profileId, webMs) in webPass) {
                val profile = profilesById[profileId] ?: continue
                val composite = DirectProfileUrlProbe.messengerCompositeDelay(
                    profile,
                    whitelistOnly,
                    tier,
                    knownWebDelayMs = webMs,
                )
                composite?.compositeDelayMs?.takeIf { it > 0 }?.let { out[profileId] = it }
            }
            if (out.isEmpty()) null else out
        } catch (e: PluginNotFoundException) {
            Logs.w("PrepareGroupUrlProbe plugin: ${e.plugin}")
            null
        } catch (e: Exception) {
            Logs.d("PrepareGroupUrlProbe batch: ${e.readableMessage}")
            null
        } finally {
            client?.closeQuietly()
            processes?.close(this@coroutineScope)
            cacheFiles.forEach { runCatching { it.delete() } }
        }
    }
}
