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
    fun effectiveStatusForRankingDowngradesStaleUnavailable() {
        val proxy = proxy(6209L, ProxyEntity.STATUS_UNAVAILABLE)
        assertEquals(
            ProxyEntity.STATUS_INITIAL,
            PrepareConnectSelection.effectiveStatusForRanking(proxy, mapOf(6209L to 300)),
        )
    }

    private fun proxy(id: Long, status: Int) = ProxyEntity().apply {
        this.id = id
        this.status = status
        groupId = 1L
    }
}
