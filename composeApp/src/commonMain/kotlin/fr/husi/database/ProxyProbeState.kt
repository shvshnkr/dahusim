package fr.husi.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "proxy_probe_states",
    indices = [
        androidx.room.Index("state"),
        androidx.room.Index("nextProbeAt"),
    ],
)
data class ProxyProbeState(
    @PrimaryKey val profileId: Long,
    val state: Int = ProbeState.UNKNOWN,
    val lastCheckedAt: Long = 0L,
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    val failCountConsecutive: Int = 0,
    val successCountWindow: Int = 0,
    val ewmaDelayMs: Int = 0,
    val lastErrorClass: String = "",
    val nextProbeAt: Long = 0L,
    val sourcePriority: Int = ProbeSourcePriority.SUBSCRIPTION,
    val lastTcpMs: Int = -1,
    val lastUrlMs: Int = -1,
)

object ProbeState {
    const val UNKNOWN = 0
    const val CANDIDATE = 1
    const val ALIVE = 2
    const val SUSPECT = 3
    const val DEAD = 4
    const val CEMETERY = 5
}

object ProbeSourcePriority {
    const val SUBSCRIPTION = 0
    const val PINNED = 1
    const val BUILTIN = 2
}

@Dao
interface ProxyProbeStateDao {

    @Query("SELECT * FROM proxy_probe_states WHERE profileId IN (:ids)")
    suspend fun getByProfileIds(ids: List<Long>): List<ProxyProbeState>

    @Query("SELECT * FROM proxy_probe_states WHERE profileId = :id LIMIT 1")
    suspend fun getByProfileId(id: Long): ProxyProbeState?

    @Query("SELECT * FROM proxy_probe_states WHERE nextProbeAt > 0 AND nextProbeAt <= :nowMs ORDER BY nextProbeAt ASC LIMIT :limit")
    suspend fun dueForProbe(nowMs: Long, limit: Int): List<ProxyProbeState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<ProxyProbeState>)

    @Query("DELETE FROM proxy_probe_states WHERE profileId IN (:ids)")
    suspend fun deleteByProfileIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM proxy_probe_states WHERE state = :state")
    suspend fun countByState(state: Int): Int
}
