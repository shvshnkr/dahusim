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
    }

    @Test
    fun isMessengerProbeUrlCoversWebAndDcTargets() {
        assertTrue(SimpleModeMessengerProbe.isMessengerProbeUrl(SimpleModeMessengerProbe.WEB_URL))
        assertTrue(SimpleModeMessengerProbe.isMessengerProbeUrl(SimpleModeMessengerProbe.DC_REQUIRED_URL))
        assertTrue(SimpleModeMessengerProbe.isMessengerProbeUrl(SimpleModeMessengerProbe.DC_SECONDARY_URL))
        assertFalse(SimpleModeMessengerProbe.isMessengerProbeUrl("https://www.gstatic.com/generate_204"))
    }
}
