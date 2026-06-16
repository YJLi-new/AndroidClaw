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

    @Query(
        """
        SELECT * FROM tasks
        WHERE enabled = 1 AND nextRunAt IS NOT NULL AND nextRunAt <= :instant
        ORDER BY nextRunAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getEnabledTasksDueBeforeLimited(
        instant: Long,
        limit: Int,
    ): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE enabled = 1 AND nextRunAt IS NOT NULL
        ORDER BY nextRunAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getUpcomingEnabledTasks(limit: Int): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE name LIKE :queryPattern ESCAPE '\'
           OR prompt LIKE :queryPattern ESCAPE '\'
        ORDER BY nextRunAt IS NULL, nextRunAt ASC
        LIMIT :limit
        """,
    )
    suspend fun searchByText(
        queryPattern: String,
        limit: Int,
    ): List<TaskEntity>

    @Query(
        """
        SELECT
            COUNT(*) AS totalTaskCount,
            COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END), 0) AS enabledTaskCount,
            COALESCE(SUM(CASE WHEN enabled = 0 THEN 1 ELSE 0 END), 0) AS disabledTaskCount,
            COALESCE(SUM(CASE WHEN nextRunAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS scheduledTaskCount,
            COALESCE(
                SUM(
                    CASE
                        WHEN enabled = 1 AND nextRunAt IS NOT NULL AND nextRunAt <= :nowMillis THEN 1
                        ELSE 0
                    END
                ),
                0
            ) AS dueTaskCount,
            MIN(CASE WHEN enabled = 1 THEN nextRunAt ELSE NULL END) AS nextEnabledRunAt,
            MAX(updatedAt) AS newestTaskUpdatedAt
        FROM tasks
        """,
    )
    suspend fun getStats(nowMillis: Long): TaskStatsRow

    @Query(
        """
        SELECT scheduleKind AS scheduleKind, COUNT(*) AS taskCount
        FROM tasks
        GROUP BY scheduleKind
        ORDER BY scheduleKind ASC
        """,
    )
    suspend fun getScheduleKindStats(): List<TaskScheduleKindStatsRow>

    @Query(
        """
        SELECT executionMode AS executionMode, COUNT(*) AS taskCount
        FROM tasks
        GROUP BY executionMode
        ORDER BY executionMode ASC
        """,
    )
    suspend fun getExecutionModeStats(): List<TaskExecutionModeStatsRow>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

data class TaskStatsRow(
    val totalTaskCount: Long,
    val enabledTaskCount: Long,
    val disabledTaskCount: Long,
    val scheduledTaskCount: Long,
    val dueTaskCount: Long,
    val nextEnabledRunAt: Long?,
    val newestTaskUpdatedAt: Long?,
)

data class TaskScheduleKindStatsRow(
    val scheduleKind: String,
    val taskCount: Long,
)

data class TaskExecutionModeStatsRow(
    val executionMode: String,
    val taskCount: Long,
)
