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
        LIMIT 1
        """,
    )
    suspend fun getLatestByTaskId(taskId: String): TaskRunEntity?

    @Query("DELETE FROM task_runs WHERE scheduledAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int
}
