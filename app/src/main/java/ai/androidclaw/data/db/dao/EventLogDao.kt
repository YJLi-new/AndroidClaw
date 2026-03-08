package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.EventLogEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(event: EventLogEntity)

    @Query(
        """
        SELECT * FROM event_logs
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun getRecent(limit: Int): Flow<List<EventLogEntity>>

    @Query("DELETE FROM event_logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM event_logs")
    suspend fun count(): Int
}

