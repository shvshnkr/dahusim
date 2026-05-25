package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoServerSelectorProbePolicyTest {

    @Test
    fun wlPrepareUrlConfirmationFromLiveUrlProbe() {
        assertTrue(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 42L,
                urlTestDelays = mapOf(42L to 180),
                probeStates = emptyMap(),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareUrlConfirmationFromFreshProbeState() {
        val now = System.currentTimeMillis()
        val state = ProxyProbeState(
            profileId = 7L,
            lastUrlMs = 220,
            lastOkAt = now - 1_000L,
        )
        assertTrue(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 7L,
                urlTestDelays = emptyMap(),
                probeStates = mapOf(7L to state),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareRejectsTcpOnlyWithoutUrl() {
        val state = ProxyProbeState(profileId = 9L, lastTcpMs = 50, lastUrlMs = -1)
        assertFalse(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 9L,
                urlTestDelays = emptyMap(),
                probeStates = mapOf(9L to state),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareRejectsStaleUrlProbeState() {
        val stale = ProxyProbeState(
            profileId = 11L,
            lastUrlMs = 300,
            lastOkAt = System.currentTimeMillis() - Probe2kDefaults.ALIVE_URL_FRESH_MS - 60_000L,
        )
        assertFalse(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 11L,
                urlTestDelays = emptyMap(),
                probeStates = mapOf(11L to stale),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareAcceptsLkgWhenProbeStateEmpty() {
        assertTrue(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 3L,
                urlTestDelays = emptyMap(),
                probeStates = emptyMap(),
                lkgUrlFresh = { it == 3L },
            ),
        )
    }
}
