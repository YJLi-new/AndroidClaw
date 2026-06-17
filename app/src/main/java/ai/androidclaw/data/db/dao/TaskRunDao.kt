package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.TaskRunEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskRunDao {
    @Insert
    suspend fun insert(run: TaskRunEntity)

    @Update
    suspend fun update(run: TaskRunEntity)

    @Query(
        """
        SELECT * FROM task_runs
        WHERE taskId = :taskId
        ORDER BY scheduledAt DESC
        """,
    )
    fun getByTaskId(taskId: String): Flow<List<TaskRunEntity>>

    @Query(
        """
        SELECT * FROM task_runs
        WHERE taskId = :taskId
        ORDER BY scheduledAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentByTaskId(
        taskId: String,
        limit: Int,
    ): List<TaskRunEntity>

    @Query(
        """
        SELECT * FROM task_runs
        WHERE status = :status
        ORDER BY scheduledAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentByStatus(
        status: String,
        limit: Int,
    ): List<TaskRunEntity>

    @Query(
        """
        SELECT * FROM task_runs
        ORDER BY scheduledAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecent(limit: Int): List<TaskRunEntity>

    @Query("SELECT * FROM task_runs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskRunEntity?

    @Query(
        """
        SELECT * FROM task_runs
        WHERE taskId = :taskId
        ORDER BY scheduledAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestByTaskId(taskId: String): TaskRunEntity?

    @Query(
        """
        SELECT
            status AS status,
            COUNT(*) AS runCount,
            MIN(scheduledAt) AS oldestScheduledAt,
            MAX(scheduledAt) AS newestScheduledAt
        FROM task_runs
        GROUP BY status
        ORDER BY status ASC
        """,
    )
    suspend fun getStatusStats(): List<TaskRunStatusStatsRow>

    @Query("DELETE FROM task_runs WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM task_runs WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: String): Int

    @Query("DELETE FROM task_runs WHERE status = :status")
    suspend fun deleteByStatus(status: String): Int

    @Query("DELETE FROM task_runs WHERE scheduledAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int
}

data class TaskRunStatusStatsRow(
    val status: String,
    val runCount: Long,
    val oldestScheduledAt: Long,
    val newestScheduledAt: Long,
)
