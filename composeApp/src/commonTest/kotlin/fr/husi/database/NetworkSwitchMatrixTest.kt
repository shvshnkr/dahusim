package fr.husi.database

import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSwitchMatrixTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis() - 30_000L
        DataStore.autoSelectLastProbeWhitelistOnly = false
        DataStore.autoSelectProxyIdSetHash = 1L
        DataStore.autoSelectLastHandoffPreserveOkAt = 0L
    }

    @Test
    fun handoffTriggersAlwaysDisableCompactReprobe() {
        val oldProxies = listOf(proxy(1L), proxy(2L))
        val newProxies = listOf(proxy(1L), proxy(3L))
        DataStore.autoSelectProxyIdSetHash = AutoServerSelectorProbePolicy.computeProxyIdSetHash(oldProxies)

        val matrix = listOf(
            "cross_interface" to true,
            "carrier_restore" to true,
            "link_rebound" to true,
            "noop_manual_refresh" to false,
        )

        for ((_, networkHandoff) in matrix) {
            val compact = AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
                proxies = newProxies,
                whitelistBuiltinOnly = false,
                networkHandoff = networkHandoff,
            )
            if (networkHandoff) {
                assertFalse(compact)
            } else {
                assertTrue(compact)
            }
        }
    }

    @Test
    fun handoffPreserveFreshnessMatrix() {
        val now = System.currentTimeMillis()
        val matrix = listOf(
            0L to true,
            5_000L to true,
            19_999L to true,
            20_001L to false,
            45_000L to false,
        )

        for ((ageMs, expectedFresh) in matrix) {
            DataStore.autoSelectLastHandoffPreserveOkAt = now - ageMs
            assertTrue(
                AutoServerSelectorProbePolicy.isHandoffPreserveFresh(now) == expectedFresh,
            )
        }
    }

    private fun proxy(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply {
            name = "p$id"
            serverAddress = "example.com"
            serverPort = 443
        }.applyDefaultValues()
    }
}
