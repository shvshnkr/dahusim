package fr.husi.database

import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InScanNetworkFlapMatrixTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis() - 45_000L
        DataStore.autoSelectLastProbeWhitelistOnly = false
    }

    @Test
    fun multiHandoffWithinSingleScanSuppressesStaleReprobeHints() {
        val oldSet = listOf(proxy(1L), proxy(2L))
        val newSet = listOf(proxy(1L), proxy(3L))
        DataStore.autoSelectProxyIdSetHash = AutoServerSelectorProbePolicy.computeProxyIdSetHash(oldSet)

        val flapSequence = listOf(
            "wifi_to_lte",
            "lte_to_wifi",
            "wifi_to_lte",
            "carrier_restore",
        )

        for (event in flapSequence) {
            val compact = AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
                proxies = newSet,
                whitelistBuiltinOnly = true,
                networkHandoff = true,
            )
            assertFalse(compact, "event=$event")

            val reason = AutoServerSelectorProbePolicy.forceFullProbeReason(
                proxies = newSet,
                whitelistBuiltinOnly = true,
                networkHandoff = true,
            )
            assertTrue(reason == null || !reason.contains("proxy_set_changed"), "event=$event reason=$reason")
        }
    }

    @Test
    fun finalStablePassAfterFlapsAllowsProxySetChangedSignal() {
        val oldSet = listOf(proxy(10L), proxy(11L))
        val newSet = listOf(proxy(10L), proxy(12L))
        DataStore.autoSelectProxyIdSetHash = AutoServerSelectorProbePolicy.computeProxyIdSetHash(oldSet)
        DataStore.autoSelectLastProbeWhitelistOnly = true
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis() - (4 * 60_000L)

        val reason = AutoServerSelectorProbePolicy.forceFullProbeReason(
            proxies = newSet,
            whitelistBuiltinOnly = true,
            networkHandoff = false,
        )
        assertTrue(reason?.contains("proxy_set_changed") == true)
    }

    private fun proxy(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply {
            name = "matrix$id"
            serverAddress = "example.com"
            serverPort = 443
        }.applyDefaultValues()
    }
}
