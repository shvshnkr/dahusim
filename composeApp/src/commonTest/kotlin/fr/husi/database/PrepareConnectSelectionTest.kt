package fr.husi.database

import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareConnectSelectionTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.simpleModeTelegramProbe = true
    }

    @Test
    fun openSelectPrefersTcpUrlOverUrlOnlyDespiteStaleUnavailable() {
        val profiles = mapOf(
            6209L to proxy(6209L, ProxyEntity.STATUS_UNAVAILABLE),
            4836L to proxy(4836L, ProxyEntity.STATUS_AVAILABLE),
        )
        val urlDelays = mapOf(6209L to 326, 4836L to 284)
        val tcpPings = mapOf(6209L to 5)
        val ranked = listOf(6209L, 4836L)
        val best = PrepareConnectSelection.selectBestOpenProfile(
            rankedFinal = ranked,
            profilesById = profiles,
            probeStates = emptyMap(),
            urlTestDelays = urlDelays,
            quickProbePings = tcpPings,
            isInFailureCooldown = { false },
        )
        assertEquals(6209L, best)
    }

    @Test
    fun demoteUrlOnlyBestWhenTcpUrlCandidateExists() {
        val ranked = listOf(4836L, 6209L, 75L)
        val urlDelays = mapOf(4836L to 284, 6209L to 326)
        val tcpPings = mapOf(6209L to 5, 75L to 5)
        val demoted = PrepareConnectSelection.demoteUrlOnlyBestIfNeeded(
            best = 4836L,
            rankedFinal = ranked,
            urlTestDelays = urlDelays,
            quickProbePings = tcpPings,
            probeStates = emptyMap(),
            isInFailureCooldown = { false },
        )
        assertEquals(6209L, demoted)
    }

    @Test
    fun freshUrlMakesUnavailableSelectable() {
        assertTrue(
            PrepareConnectSelection.isSelectableDespiteStaleStatus(
                6209L,
                ProxyEntity.STATUS_UNAVAILABLE,
                mapOf(6209L to 300),
            ),
        )
        assertFalse(
            PrepareConnectSelection.isSelectableDespiteStaleStatus(
                6209L,
                ProxyEntity.STATUS_UNAVAILABLE,
                emptyMap(),
            ),
        )
    }

    @Test
    fun freshUrlOverridesStaleFailureCooldownForOpenSelection() {
        val cooldownId = 31170L
        val staleId = 31751L
        val profiles = mapOf(
            cooldownId to proxy(cooldownId, ProxyEntity.STATUS_AVAILABLE),
            staleId to proxy(staleId, ProxyEntity.STATUS_AVAILABLE),
        )
        val urlDelays = mapOf(cooldownId to 1166)
        val tcpPings = mapOf(cooldownId to 97, staleId to 74)
        val ranked = listOf(staleId, cooldownId)

        val inCooldown = { id: Long -> id == cooldownId }
        val freshOverridesCooldown = { id: Long -> inCooldown(id) && id !in urlDelays }

        val withoutFix = PrepareConnectSelection.selectBestOpenProfile(
            rankedFinal = ranked,
            profilesById = profiles,
            probeStates = emptyMap(),
            urlTestDelays = urlDelays,
            quickProbePings = tcpPings,
            isInFailureCooldown = inCooldown,
        )
        val withFix = PrepareConnectSelection.selectBestOpenProfile(
            rankedFinal = ranked,
            profilesById = profiles,
            probeStates = emptyMap(),
            urlTestDelays = urlDelays,
            quickProbePings = tcpPings,
            isInFailureCooldown = freshOverridesCooldown,
        )

        assertEquals(staleId, withoutFix)
        assertEquals(cooldownId, withFix)
    }

    @Test
    fun effectiveStatusForRankingDowngradesStaleUnavailable() {
        val proxy = proxy(6209L, ProxyEntity.STATUS_UNAVAILABLE)
        assertEquals(
            ProxyEntity.STATUS_INITIAL,
            PrepareConnectSelection.effectiveStatusForRanking(proxy, mapOf(6209L to 300)),
        )
    }

    @Test
    fun wlSelectPrefersFreshUrlVerifiedOverStaleConfirmedPriority() {
        val verifiedId = 340L
        val staleConfirmedId = 99L
        val profiles = mapOf(
            verifiedId to proxy(verifiedId, ProxyEntity.STATUS_AVAILABLE),
            staleConfirmedId to proxy(staleConfirmedId, ProxyEntity.STATUS_AVAILABLE),
        )
        val urlDelays = mapOf(verifiedId to 737)
        val probeStates = mapOf(
            verifiedId to ProxyProbeState(
                profileId = verifiedId,
                state = ProbeState.DEAD,
            ),
            staleConfirmedId to ProxyProbeState(
                profileId = staleConfirmedId,
                state = ProbeState.ALIVE,
                lastUrlMs = 500,
                lastOkAt = System.currentTimeMillis(),
            ),
        )
        val ranked = listOf(staleConfirmedId, verifiedId)

        val best = PrepareConnectSelection.selectBestWlProfile(
            rankedFinal = ranked,
            profilesById = profiles,
            probeStates = probeStates,
            urlTestDelays = urlDelays,
            isInFailureCooldown = { false },
        )
        assertEquals(verifiedId, best)
    }

    @Test
    fun wlSelectFallsBackToStaleConfirmedWhenNoFreshVerified() {
        val staleConfirmedId = 99L
        val profiles = mapOf(
            staleConfirmedId to proxy(staleConfirmedId, ProxyEntity.STATUS_AVAILABLE),
            340L to proxy(340L, ProxyEntity.STATUS_AVAILABLE),
        )
        val probeStates = mapOf(
            staleConfirmedId to ProxyProbeState(
                profileId = staleConfirmedId,
                state = ProbeState.ALIVE,
                lastUrlMs = 500,
                lastOkAt = System.currentTimeMillis(),
            ),
        )
        val ranked = listOf(staleConfirmedId, 340L)

        val best = PrepareConnectSelection.selectBestWlProfile(
            rankedFinal = ranked,
            profilesById = profiles,
            probeStates = probeStates,
            urlTestDelays = emptyMap(),
            isInFailureCooldown = { false },
        )
        assertEquals(staleConfirmedId, best)
    }

    private fun proxy(id: Long, status: Int) = ProxyEntity().apply {
        this.id = id
        this.status = status
        groupId = 1L
    }
}
