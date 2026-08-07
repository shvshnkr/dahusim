package fr.husi.database

import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoServerSelectorFailureCooldownSnapshotTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.probe2kPersistenceEnabled = false
    }

    @Test
    fun snapshotIsDeterministicAndSortDoesNotThrowWithTwoThousandNodes() {
        val poolSize = 2000
        val ids = (1L..poolSize.toLong()).toList()
        val cooldownIds = ids.filter { it % 5 == 0L }.toSet()
        cooldownIds.forEach { AutoServerSelector.recordProbeFailure(it) }

        val first = AutoServerSelector.failureCooldownSnapshotForTest(ids)
        val second = AutoServerSelector.failureCooldownSnapshotForTest(ids)

        assertEquals(cooldownIds, first)
        assertEquals(first, second)

        val pool = ids.map { proxy(it) }
        val ranked = pool.sortedWith(
            compareBy<ProxyEntity> { if (it.id in first) 1 else 0 }
                .thenBy { it.status }
                .thenBy { it.id },
        )
        assertEquals(poolSize, ranked.size)
        assertEquals(cooldownIds, ranked.takeLast(cooldownIds.size).map { it.id }.toSet())
    }

    private fun proxy(id: Long) = ProxyEntity().apply {
        this.id = id
        status = ProxyEntity.STATUS_AVAILABLE
        groupId = 1L
    }
}
