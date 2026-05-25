package fr.husi.fmt

import fr.husi.database.ProxyEntity
import fr.husi.fmt.ConfigBuildResult.IndexEntity
import fr.husi.fmt.SingBoxOptions.DomainResolveOptions
import fr.husi.fmt.SingBoxOptions.LogOptions
import fr.husi.fmt.SingBoxOptions.MyDNSOptions
import fr.husi.fmt.SingBoxOptions.MyOptions
import fr.husi.fmt.SingBoxOptions.MyRouteOptions
import fr.husi.fmt.SingBoxOptions.NewDNSServerOptions_LocalDNSServerOptions
import fr.husi.fmt.SingBoxOptions.Outbound_DirectOptions
import fr.husi.fmt.SingBoxOptions.Outbound_URLTestOptions
import fr.husi.fmt.internal.buildSingBoxOutboundProxySetBean
import fr.husi.fmt.internal.ProxySetBean
import fr.husi.ktx.JSONMap
import fr.husi.ktx.asKxsMap
import fr.husi.ktx.kxs
import fr.husi.ktx.toJsonElementKxs
import fr.husi.ktx.toJsonMapKxs
import fr.husi.logLevelString
import fr.husi.database.DataStore

/** Minimal sing-box config: N profile outbounds + one urltest group for batch prepare probes. */
internal object PrepareTestConfigBuilder {

    const val TAG_PREPARE_GROUP = "prepare-urltest"

    data class BatchPartition(
        val batchable: List<ProxyEntity>,
        val pluginRequired: List<ProxyEntity>,
    )

    fun probeTag(profileId: Long): String = "p$profileId"

    fun partitionForBatch(profiles: List<ProxyEntity>): BatchPartition {
        val batchable = ArrayList<ProxyEntity>()
        val pluginRequired = ArrayList<ProxyEntity>()
        for (profile in profiles) {
            if (needsPluginForBatch(profile)) {
                pluginRequired.add(profile)
            } else {
                batchable.add(profile)
            }
        }
        return BatchPartition(batchable, pluginRequired)
    }

    fun needsPluginForBatch(profile: ProxyEntity): Boolean {
        if (profile.needExternal()) return true
        if (profile.type != ProxyEntity.TYPE_CHAIN && profile.type != ProxyEntity.TYPE_CONFIG) {
            return false
        }
        val built = buildConfig(profile, forTest = true)
        return built.externalIndex.any { it.chain.isNotEmpty() }
    }

    fun build(
        profiles: List<ProxyEntity>,
        testUrl: String,
    ): ConfigBuildResult? {
        val batchable = partitionForBatch(profiles).batchable
        if (batchable.isEmpty()) return null
        return buildBatchable(batchable, testUrl)
    }

    private fun buildBatchable(
        profiles: List<ProxyEntity>,
        testUrl: String,
    ): ConfigBuildResult? {
        if (profiles.isEmpty()) return null
        val mergedOutbounds = ArrayList<JSONMap>()
        val externalIndex = ArrayList<IndexEntity>()
        val tagToId = LinkedHashMap<String, Long>()
        val memberTags = ArrayList<String>()

        for (profile in profiles) {
            val built = buildConfig(profile, forTest = true)
            if (built.externalIndex.any { it.chain.isNotEmpty() }) return null
            val root = built.config.toJsonMapKxs()
            val outbounds = root["outbounds"] as? List<*> ?: return null
            val proxyOutbounds = outbounds.mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                val map = item as? JSONMap ?: return@mapNotNull null
                val tag = map["tag"]?.toString() ?: return@mapNotNull null
                when (tag) {
                    TAG_DIRECT, TAG_BLOCK -> null
                    else -> map
                }
            }
            if (proxyOutbounds.size != 1) return null
            val memberTag = probeTag(profile.id)
            val renamed = proxyOutbounds.single().toMutableMap()
            renamed["tag"] = memberTag
            mergedOutbounds.add(renamed)
            memberTags.add(memberTag)
            tagToId[memberTag] = profile.id
            externalIndex.addAll(built.externalIndex)
        }
        if (memberTags.isEmpty()) return null

        val urlTestBean = ProxySetBean().apply {
            management = ProxySetBean.MANAGEMENT_URLTEST
            testURL = testUrl
            testInterval = "3m"
            testIdleTimeout = "10m"
            testTolerance = 50
            interruptExistConnections = true
        }
        val groupOutbound = buildSingBoxOutboundProxySetBean(urlTestBean, memberTags).apply {
            tag = TAG_PREPARE_GROUP
        }

        val options = MyOptions().apply {
            log = LogOptions().apply {
                level = logLevelString(DataStore.logLevel)
            }
            dns = MyDNSOptions().apply {
                servers = mutableListOf(
                    NewDNSServerOptions_LocalDNSServerOptions().apply {
                        tag = TAG_DNS_LOCAL
                        type = SingBoxOptions.DNS_TYPE_LOCAL
                    },
                )
                rules = mutableListOf()
            }
            route = MyRouteOptions().apply {
                rules = mutableListOf()
                final_ = TAG_PREPARE_GROUP
            }
            outbounds = mutableListOf(
                Outbound_DirectOptions().apply {
                    tag = TAG_DIRECT
                    type = SingBoxOptions.TYPE_DIRECT
                    domain_resolver = DomainResolveOptions().apply {
                        server = TAG_DNS_LOCAL
                    }
                }.asKxsMap(),
                SingBoxOptions.Outbound().apply {
                    tag = TAG_BLOCK
                    type = SingBoxOptions.TYPE_BLOCK
                }.asKxsMap(),
            )
            outbounds!!.addAll(mergedOutbounds)
            outbounds!!.add(groupOutbound.asKxsMap())
        }

        val config = kxs.encodeToString(options.toKxs().asKxsMap().toJsonElementKxs())
        return ConfigBuildResult(
            mainTag = TAG_PREPARE_GROUP,
            config = config,
            externalIndex = externalIndex,
            trafficMap = emptyMap(),
            tagToID = tagToId,
        )
    }
}
