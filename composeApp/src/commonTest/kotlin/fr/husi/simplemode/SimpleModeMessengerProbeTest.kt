package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeMessengerProbeTest {

    @Test
    fun messengerReadyRequiresWebAndDcRequired() {
        assertFalse(SimpleModeMessengerProbe.messengerReady(webOk = false, dcRequiredOk = false))
        assertFalse(SimpleModeMessengerProbe.messengerReady(webOk = true, dcRequiredOk = false))
        assertFalse(SimpleModeMessengerProbe.messengerReady(webOk = false, dcRequiredOk = true))
        assertTrue(SimpleModeMessengerProbe.messengerReady(webOk = true, dcRequiredOk = true))
    }

    @Test
    fun prepareResultCompositeDelayUsesWorstStep() {
        val ready = SimpleModeMessengerProbe.PrepareResult(
            webDelayMs = 120,
            dcRequiredDelayMs = 340,
            dcSecondaryDelayMs = 90,
        )
        assertTrue(ready.ready)
        assertEquals(340, ready.compositeDelayMs)
        assertTrue(ready.secondaryOk)
    }

    @Test
    fun prepareResultRejectedWhenDcRequiredMissing() {
        val partial = SimpleModeMessengerProbe.PrepareResult(
            webDelayMs = 120,
            dcRequiredDelayMs = null,
            dcSecondaryDelayMs = null,
        )
        assertFalse(partial.ready)
        assertEquals(0, partial.compositeDelayMs)
    }

    @Test
    fun evaluateTunnelWaveFailsWhenWebMissing() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 0,
            webError = "timeout",
            dcRequiredLatencyMs = 100,
            dcRequiredError = null,
        )
        assertFalse(evaluation.ok)
        assertEquals(SimpleModeMessengerProbe.WEB_URL, evaluation.lastProbeUrl)
        assertFalse(evaluation.dcOkOnWebFail)
    }

    @Test
    fun evaluateTunnelWaveFailsWhenDcRequiredMissing() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 100,
            webError = null,
            dcRequiredLatencyMs = 0,
            dcRequiredError = "timeout",
        )
        assertFalse(evaluation.ok)
        assertEquals(SimpleModeMessengerProbe.DC_REQUIRED_URL, evaluation.lastProbeUrl)
    }

    @Test
    fun evaluateTunnelWaveOkWhenBothStepsPass() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 180,
            webError = null,
            dcRequiredLatencyMs = 240,
            dcRequiredError = null,
            dcSecondaryLatencyMs = 300,
        )
        assertTrue(evaluation.ok)
        assertEquals(240, evaluation.latencyMs)
        assertTrue(evaluation.dcSecondaryOk)
        assertFalse(evaluation.wasSynthetic)
    }

    @Test
    fun evaluateTunnelWaveAllSyntheticLatenciesAreNotRealSuccess() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 1,
            webError = "dial ccmni1: i/o timeout",
            dcRequiredLatencyMs = 1,
            dcRequiredError = "dial ccmni1: i/o timeout",
            dcSecondaryLatencyMs = 1,
            webSynthetic = true,
            dcRequiredSynthetic = true,
        )
        assertTrue(evaluation.ok)
        assertEquals(1, evaluation.latencyMs)
        assertTrue(evaluation.wasSynthetic)
    }

    @Test
    fun evaluateTunnelWaveSyntheticWebDowngradesWholeWave() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 1,
            webError = "dial ccmni1: i/o timeout",
            dcRequiredLatencyMs = 300,
            dcRequiredError = null,
            webSynthetic = true,
            dcRequiredSynthetic = false,
        )
        assertTrue(evaluation.ok)
        assertTrue(evaluation.wasSynthetic)
    }

    @Test
    fun evaluateTunnelWaveSyntheticDcRequiredDowngradesWholeWave() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 200,
            webError = null,
            dcRequiredLatencyMs = 1,
            dcRequiredError = "dial ccmni1: i/o timeout",
            webSynthetic = false,
            dcRequiredSynthetic = true,
        )
        assertTrue(evaluation.ok)
        assertTrue(evaluation.wasSynthetic)
    }

    @Test
    fun evaluateTunnelWaveSecondarySyntheticDoesNotDowngradeGate() {
        val evaluation = SimpleModeMessengerProbe.evaluateTunnelWave(
            webLatencyMs = 200,
            webError = null,
            dcRequiredLatencyMs = 300,
            dcRequiredError = null,
            dcSecondaryLatencyMs = 1,
            webSynthetic = false,
            dcRequiredSynthetic = false,
        )
        assertTrue(evaluation.ok)
        assertFalse(evaluation.wasSynthetic)
    }

    @Test
    fun evaluateWebFailWithDcRescueMarksDcOkWhenDcLatencyPositive() {
        val evaluation = SimpleModeMessengerProbe.evaluateWebFailWithDcRescue(
            webError = "unexpected HTTP response status: 503",
            dcLatencyMs = 100,
            dcError = null,
        )
        assertFalse(evaluation.ok)
        assertEquals(0, evaluation.latencyMs)
        assertEquals("unexpected HTTP response status: 503", evaluation.lastError)
        assertEquals(SimpleModeMessengerProbe.WEB_URL, evaluation.lastProbeUrl)
        assertTrue(evaluation.dcOkOnWebFail)
        assertFalse(evaluation.dcSecondaryOk)
    }

    @Test
    fun evaluateWebFailWithDcRescueDcFailKeepsDefaultError() {
        val evaluation = SimpleModeMessengerProbe.evaluateWebFailWithDcRescue(
            webError = null,
            dcLatencyMs = 0,
            dcError = "dial tcp 91.105.192.100: i/o timeout",
        )
        assertFalse(evaluation.ok)
        assertEquals("messenger_web_failed", evaluation.lastError)
        assertFalse(evaluation.dcOkOnWebFail)
    }

    @Test
    fun isMessengerProbeUrlCoversWebAndDcTargets() {
        assertTrue(SimpleModeMessengerProbe.isMessengerProbeUrl(SimpleModeMessengerProbe.WEB_URL))
        assertTrue(SimpleModeMessengerProbe.isMessengerProbeUrl(SimpleModeMessengerProbe.DC_REQUIRED_URL))
        assertTrue(SimpleModeMessengerProbe.isMessengerProbeUrl(SimpleModeMessengerProbe.DC_SECONDARY_URL))
        assertFalse(SimpleModeMessengerProbe.isMessengerProbeUrl("https://www.gstatic.com/generate_204"))
    }
}
