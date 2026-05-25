package fr.husi.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "subscription_update_states",
    indices = [
        androidx.room.Index("state"),
        androidx.room.Index("nextAttemptAtMs"),
    ],
)
data class SubscriptionUpdateState(
    @PrimaryKey val groupId: Long,
    val state: Int = SubUpdateState.OK,
    val failCountConsecutive: Int = 0,
    val lastAttemptAtMs: Long = 0L,
    val nextAttemptAtMs: Long = 0L,
    val lastErrorClass: String = "",
)

object SubUpdateState {
    const val OK = 0
    const val SUSPECT = 1
    const val JAIL = 2
}

object SubscriptionUpdateErrorClass {
    const val HTTP_PERMANENT = "http_permanent"
    const val HTTP_TRANSIENT = "http_transient"
    const val TRANSPORT = "transport"
    const val OTHER = "other"
}

@Dao
interface SubscriptionUpdateStateDao {

    @Query("SELECT * FROM subscription_update_states WHERE groupId IN (:ids)")
    suspend fun getByGroupIds(ids: List<Long>): List<SubscriptionUpdateState>

    @Query("SELECT * FROM subscription_update_states WHERE groupId = :id LIMIT 1")
    suspend fun getByGroupId(id: Long): SubscriptionUpdateState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<SubscriptionUpdateState>)
}
