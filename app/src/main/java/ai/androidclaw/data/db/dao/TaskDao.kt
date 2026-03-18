package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.TaskEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Query(
        """
        SELECT * FROM tasks
        ORDER BY nextRunAt IS NULL, nextRunAt ASC
        """,
    )
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE enabled = 1 AND nextRunAt IS NOT NULL AND nextRunAt <= :instant
        ORDER BY nextRunAt ASC
        """,
    )
    suspend fun getEnabledTasksDueBefore(instant: Long): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}
