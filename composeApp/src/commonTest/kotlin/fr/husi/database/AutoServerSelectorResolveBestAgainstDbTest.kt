package fr.husi.database

import fr.husi.GroupType
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The concurrent subscription refresh (connect refresh) can delete a profile from the DB after
 * prepareForConnect picked it as best. resolveBestAgainstDb must re-resolve against the current
 * DB so the connect step never sees a vanished profile id.
 */
class AutoServerSelectorResolveBestAgainstDbTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SagerDatabase.proxyDao.reset()
    }

    private suspend fun newGroup(): Long =
        GroupManager.createGroup(
            ProxyGroup(name = "resolve-test", type = GroupType.SUBSCRIPTION),
            notifySubscriptionScheduler = false,
        ).id

    private fun proxy(groupId: Long, order: Long): ProxyEntity =
        ProxyEntity(groupId = groupId, type = ProxyEntity.TYPE_TROJAN).apply {
            userOrder = order
            trojanBean = TrojanBean()
        }

    @Test
    fun keepsBestWhenProfileStillExists() = runTest {
        val groupId = newGroup()
        val best = proxy(groupId, 1L)
        best.id = SagerDatabase.proxyDao.addProxy(best)
        val resolved = AutoServerSelector.resolveBestAgainstDb(best.id, listOf(best.id, 42L))
        assertEquals(best.id, resolved)
    }

    @Test
    fun fallsBackToNextExistingCandidateWhenBestDeleted() = runTest {
        val groupId = newGroup()
        val best = proxy(groupId, 1L)
        best.id = SagerDatabase.proxyDao.addProxy(best)
        val next = proxy(groupId, 2L)
        next.id = SagerDatabase.proxyDao.addProxy(next)
        SagerDatabase.proxyDao.deleteById(best.id)
        val resolved = AutoServerSelector.resolveBestAgainstDb(best.id, listOf(best.id, next.id, 999L))
        assertEquals(next.id, resolved)
    }

    @Test
    fun fallsBackToFirstExistingEvenWhenNotAdjacentInRanking() = runTest {
        val groupId = newGroup()
        val best = proxy(groupId, 1L)
        best.id = SagerDatabase.proxyDao.addProxy(best)
        val next = proxy(groupId, 2L)
        next.id = SagerDatabase.proxyDao.addProxy(next)
        SagerDatabase.proxyDao.deleteById(best.id)
        val resolved = AutoServerSelector.resolveBestAgainstDb(best.id, listOf(best.id, 999L, next.id))
        assertEquals(next.id, resolved)
    }

    @Test
    fun returnsBestWhenNoCandidateSurvived() = runTest {
        val groupId = newGroup()
        val best = proxy(groupId, 1L)
        best.id = SagerDatabase.proxyDao.addProxy(best)
        SagerDatabase.proxyDao.deleteById(best.id)
        val resolved = AutoServerSelector.resolveBestAgainstDb(best.id, listOf(best.id, 999L))
        assertEquals(best.id, resolved)
    }
}