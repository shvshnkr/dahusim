package fr.husi.fmt

import fr.husi.ProtocolProvider
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.fmt.hysteria.HysteriaBean
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrepareTestConfigBuilderTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun partitionForBatch_putsTrojanProfilesInBatchable() {
        val profiles = listOf(
            trojanProxy(1L),
            trojanProxy(2L),
        )
        val part = PrepareTestConfigBuilder.partitionForBatch(profiles)
        assertEquals(2, part.batchable.size)
        assertTrue(part.pluginRequired.isEmpty())
    }

    @Test
    fun partitionForBatch_splitsPluginProfilesViaNeedExternal() {
        DataStore.providerHysteria2 = ProtocolProvider.PLUGIN
        val profiles = listOf(
            trojanProxy(1L),
            hysteriaProxy(2L),
        )
        val part = PrepareTestConfigBuilder.partitionForBatch(profiles)
        assertEquals(listOf(1L), part.batchable.map { it.id })
        assertEquals(listOf(2L), part.pluginRequired.map { it.id })
    }

    private fun trojanProxy(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply {
            name = "t$id"
            serverAddress = "example.com"
            serverPort = 443
        }.applyDefaultValues()
    }

    private fun hysteriaProxy(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_HYSTERIA
        hysteriaBean = HysteriaBean().apply {
            name = "h$id"
            serverAddress = "example.com"
            serverPort = 443
            protocolVersion = HysteriaBean.PROTOCOL_VERSION_2
        }.applyDefaultValues()
    }
}
