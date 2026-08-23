package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class PoolDegradationMatrixTest {

    @Test
    fun openDecisionMatrixAcrossAliveRatios() {
        AutoServerSelectorProbePolicy.TelegramTargetCircuit.resetForTest()
        val total = 20
        val matrix = listOf(
            Triple(0, 0, AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD),
            Triple(2, 0, AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD),
            Triple(4, 0, AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD),
            Triple(7, 0, AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD),
            Triple(10, 1, AutoServerSelectorProbePolicy.OpenPrepareDecision.OK),
            Triple(14, 1, AutoServerSelectorProbePolicy.OpenPrepareDecision.OK),
            Triple(20, 2, AutoServerSelectorProbePolicy.OpenPrepareDecision.OK),
        )

        for ((tcpAlive, urlOk, expected) in matrix) {
            val outcome = AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                tcpAlive = tcpAlive,
                urlOk = urlOk,
                openMessengerProbe = true,
            )
            assertEquals(expected, outcome.decision, "tcpAlive=$tcpAlive/$total urlOk=$urlOk")
        }
    }

    @Test
    fun wlOrDisabledMessengerBypassesDegradationGate() {
        val bypassMatrix = listOf(
            Pair(true, true),   // WL pool
            Pair(false, false), // open pool, messenger gate disabled
        )
        for ((wlUrlProbes, openMessengerProbe) in bypassMatrix) {
            val outcome = AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = wlUrlProbes,
                shouldQuickProbe = true,
                tcpAlive = 0,
                urlOk = 0,
                openMessengerProbe = openMessengerProbe,
            )
            assertEquals(AutoServerSelectorProbePolicy.OpenPrepareDecision.OK, outcome.decision)
        }
    }
}
