package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.TaskDao
import ai.androidclaw.data.db.dao.TaskExecutionModeStatsRow
import ai.androidclaw.data.db.dao.TaskRunDao
import ai.androidclaw.data.db.dao.TaskRunStatusStatsRow
import ai.androidclaw.data.db.dao.TaskScheduleKindStatsRow
import ai.androidclaw.data.db.entity.TaskEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
import ai.androidclaw.data.model.ScheduleSerializer
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

internal const val TASK_NAME_MAX_CHARS = 160
internal const val TASK_PROMPT_MAX_CHARS = 40_000
internal const val TASK_TARGET_SESSION_ID_MAX_CHARS = 256
internal const val TASK_RUN_ERROR_MESSAGE_MAX_CHARS = 1_000
internal const val TASK_RUN_RESULT_SUMMARY_MAX_CHARS = 4_000
internal const val TASK_RUN_QUERY_MAX_LIMIT = 100
internal const val TASK_SEARCH_MAX_LIMIT = 200
internal const val TASK_DUE_QUERY_MAX_LIMIT = TASK_SEARCH_MAX_LIMIT
internal const val TASK_UPCOMING_QUERY_MAX_LIMIT = TASK_SEARCH_MAX_LIMIT

class TaskRepository(
    private val taskDao: TaskDao,
    private val taskRunDao: TaskRunDao,
) {
    data class TaskStats(
        val totalTaskCount: Long,
        val enabledTaskCount: Long,
        val disabledTaskCount: Long,
        val scheduledTaskCount: Long,
        val dueTaskCount: Long,
        val nextEnabledRunAt: Instant?,
        val newestTaskUpdatedAt: Instant?,
        val scheduleKindStats: List<ScheduleKindStats>,
        val executionModeStats: List<ExecutionModeStats>,
        val totalRunCount: Long,
        val oldestRunScheduledAt: Instant?,
        val newestRunScheduledAt: Instant?,
        val runStatusStats: List<RunStatusStats>,
    )

    data class ScheduleKindStats(
        val scheduleKind: String,
        val taskCount: Long,
    )

    data class ExecutionModeStats(
        val executionMode: TaskExecutionMode,
        val taskCount: Long,
    )

    data class RunStatusStats(
        val status: TaskRunStatus,
        val runCount: Long,
        val oldestScheduledAt: Instant,
        val newestScheduledAt: Instant,
    )

    suspend fun createTask(
        name: String,
        prompt: String,
        schedule: TaskSchedule,
        executionMode: TaskExecutionMode,
        targetSessionId: String?,
        precise: Boolean = false,
        maxRetries: Int = 3,
    ): Task {
        val now = Instant.now()
        val entity =
            TaskEntity(
                id = UUID.randomUUID().toString(),
                name = name.toBoundedTaskText(TASK_NAME_MAX_CHARS),
                prompt = prompt.toBoundedTaskText(TASK_PROMPT_MAX_CHARS),
                scheduleKind = ScheduleSerializer.kindOf(schedule),
                scheduleSpec = ScheduleSerializer.toJson(schedule),
                executionMode = executionMode.toStorage(),
                targetSessionId = targetSessionId?.toBoundedTaskText(TASK_TARGET_SESSION_ID_MAX_CHARS),
                enabled = true,
                precise = precise,
                nextRunAt = schedule.initialNextRun(now)?.toEpochMilli(),
                lastRunAt = null,
                failureCount = 0,
                maxRetries = maxRetries.toNonNegativeTaskCounter(),
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            )
        taskDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun updateTask(task: Task) {
        taskDao.update(task.toEntity())
    }

    suspend fun getTask(id: String): Task? = taskDao.getById(id)?.toDomainOrNull()

    fun observeTasks(): Flow<List<Task>> =
        taskDao.getAllTasks().map { tasks ->
            tasks.mapNotNull(TaskEntity::toDomainOrNull)
        }

    suspend fun getEnabledTasksDueBefore(instant: Instant): List<Task> =
        taskDao
            .getEnabledTasksDueBefore(instant.toEpochMilli())
            .mapNotNull(TaskEntity::toDomainOrNull)

    suspend fun getEnabledTasksDueBefore(
        instant: Instant,
        limit: Int,
    ): List<Task> {
        val boundedLimit = limit.coerceIn(0, TASK_SEARCH_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        val entities =
            taskDao.getEnabledTasksDueBeforeLimited(
                instant = instant.toEpochMilli(),
                limit = TASK_DUE_QUERY_MAX_LIMIT,
            )
        return entities.mapNotNull(TaskEntity::toDomainOrNull).take(boundedLimit)
    }

    suspend fun getUpcomingEnabledTasks(limit: Int): List<Task> {
        val boundedLimit = limit.coerceIn(0, TASK_SEARCH_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        return taskDao
            .getUpcomingEnabledTasks(limit = TASK_UPCOMING_QUERY_MAX_LIMIT)
            .mapNotNull(TaskEntity::toDomainOrNull)
            .take(boundedLimit)
    }

    suspend fun searchTasks(
        query: String,
        limit: Int,
    ): List<Task> {
        val queryPattern = query.toSqlLikeContainsPatternOrNull()
        val boundedLimit = limit.coerceIn(0, TASK_SEARCH_MAX_LIMIT)
        if (queryPattern == null || boundedLimit == 0) {
            return emptyList()
        }
        return taskDao.searchByText(queryPattern, boundedLimit).mapNotNull(TaskEntity::toDomainOrNull)
    }

    suspend fun deleteTask(id: String) {
        taskDao.delete(id)
    }

    suspend fun recordRun(
        taskId: String,
        scheduledAt: Instant = Instant.now(),
    ): TaskRun {
        val entity =
            TaskRunEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                status = TaskRunStatus.Pending.toStorage(),
                scheduledAt = scheduledAt.toEpochMilli(),
                startedAt = null,
                finishedAt = null,
                errorCode = null,
                errorMessage = null,
                resultSummary = null,
                outputMessageId = null,
            )
        taskRunDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun updateRun(run: TaskRun) {
        taskRunDao.update(run.toEntity())
    }

    fun observeRuns(taskId: String): Flow<List<TaskRun>> =
        taskRunDao.getByTaskId(taskId).map { runs ->
            runs.map(TaskRunEntity::toDomain)
        }

    suspend fun getLatestRun(taskId: String): TaskRun? = taskRunDao.getLatestByTaskId(taskId)?.toDomain()

    suspend fun getRun(id: String): TaskRun? = taskRunDao.getById(id)?.toDomain()

    suspend fun getRecentRuns(
        taskId: String,
        limit: Int,
    ): List<TaskRun> {
        val boundedLimit = limit.coerceIn(0, TASK_RUN_QUERY_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        return taskRunDao.getRecentByTaskId(taskId, boundedLimit).map(TaskRunEntity::toDomain)
    }

    suspend fun getRecentRunsByStatus(
        status: TaskRunStatus,
        limit: Int,
    ): List<TaskRun> {
        val boundedLimit = limit.coerceIn(0, TASK_RUN_QUERY_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        return taskRunDao.getRecentByStatus(status = status.toStorage(), limit = boundedLimit).map(TaskRunEntity::toDomain)
    }

    suspend fun getRecentRuns(limit: Int): List<TaskRun> {
        val boundedLimit = limit.coerceIn(0, TASK_RUN_QUERY_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        return taskRunDao.getRecent(boundedLimit).map(TaskRunEntity::toDomain)
    }

    suspend fun getTaskStats(now: Instant): TaskStats {
        val taskStats = taskDao.getStats(now.toEpochMilli())
        val runStats = taskRunDao.getStatusStats().map(TaskRunStatusStatsRow::toRunStatusStats)
        return TaskStats(
            totalTaskCount = taskStats.totalTaskCount,
            enabledTaskCount = taskStats.enabledTaskCount,
            disabledTaskCount = taskStats.disabledTaskCount,
            scheduledTaskCount = taskStats.scheduledTaskCount,
            dueTaskCount = taskStats.dueTaskCount,
            nextEnabledRunAt = taskStats.nextEnabledRunAt?.let(Instant::ofEpochMilli),
            newestTaskUpdatedAt = taskStats.newestTaskUpdatedAt?.let(Instant::ofEpochMilli),
            scheduleKindStats = taskDao.getScheduleKindStats().map(TaskScheduleKindStatsRow::toScheduleKindStats),
            executionModeStats = taskDao.getExecutionModeStats().map(TaskExecutionModeStatsRow::toExecutionModeStats),
            totalRunCount = runStats.sumOf { stats -> stats.runCount },
            oldestRunScheduledAt = runStats.minOfOrNull { stats -> stats.oldestScheduledAt },
            newestRunScheduledAt = runStats.maxOfOrNull { stats -> stats.newestScheduledAt },
            runStatusStats = runStats,
        )
    }

    suspend fun deleteRun(id: String): Int = taskRunDao.deleteById(id)

    suspend fun clearRunsForTask(taskId: String): Int = taskRunDao.deleteByTaskId(taskId)

    suspend fun clearRunsByStatus(status: TaskRunStatus): Int = taskRunDao.deleteByStatus(status.toStorage())

    suspend fun trimRunsOlderThan(instant: Instant): Int = taskRunDao.deleteOlderThan(instant.toEpochMilli())
}

private fun TaskEntity.toDomain(): Task = toDomainOrNull() ?: throw IllegalArgumentException("Task $id has an invalid schedule.")

private fun TaskEntity.toDomainOrNull(): Task? {
    val schedule = ScheduleSerializer.fromJsonOrNull(scheduleSpec) ?: return null
    return toDomain(schedule)
}

private fun TaskEntity.toDomain(schedule: TaskSchedule): Task =
    Task(
        id = id,
        name = name.toBoundedTaskText(TASK_NAME_MAX_CHARS),
        prompt = prompt.toBoundedTaskText(TASK_PROMPT_MAX_CHARS),
        schedule = schedule,
        executionMode = executionMode.toTaskExecutionMode(),
        targetSessionId = targetSessionId?.toBoundedTaskText(TASK_TARGET_SESSION_ID_MAX_CHARS),
        enabled = enabled,
        precise = precise,
        nextRunAt = nextRunAt?.let(Instant::ofEpochMilli),
        lastRunAt = lastRunAt?.let(Instant::ofEpochMilli),
        failureCount = failureCount.toNonNegativeTaskCounter(),
        maxRetries = maxRetries.toNonNegativeTaskCounter(),
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

private fun Task.toEntity(): TaskEntity =
    TaskEntity(
        id = id,
        name = name.toBoundedTaskText(TASK_NAME_MAX_CHARS),
        prompt = prompt.toBoundedTaskText(TASK_PROMPT_MAX_CHARS),
        scheduleKind = ScheduleSerializer.kindOf(schedule),
        scheduleSpec = ScheduleSerializer.toJson(schedule),
        executionMode = executionMode.toStorage(),
        targetSessionId = targetSessionId?.toBoundedTaskText(TASK_TARGET_SESSION_ID_MAX_CHARS),
        enabled = enabled,
        precise = precise,
        nextRunAt = nextRunAt?.toEpochMilli(),
        lastRunAt = lastRunAt?.toEpochMilli(),
        failureCount = failureCount.toNonNegativeTaskCounter(),
        maxRetries = maxRetries.toNonNegativeTaskCounter(),
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

private fun Int.toNonNegativeTaskCounter(): Int = coerceAtLeast(0)

private fun String.toBoundedTaskText(maxChars: Int): String =
    if (length <= maxChars) {
        this
    } else {
        take(maxChars)
    }

private fun TaskRunEntity.toDomain(): TaskRun =
    TaskRun(
        id = id,
        taskId = taskId,
        status = status.toTaskRunStatus(),
        scheduledAt = Instant.ofEpochMilli(scheduledAt),
        startedAt = startedAt?.let(Instant::ofEpochMilli),
        finishedAt = finishedAt?.let(Instant::ofEpochMilli),
        errorCode = errorCode,
        errorMessage = errorMessage?.toBoundedTaskRunText(TASK_RUN_ERROR_MESSAGE_MAX_CHARS),
        resultSummary = resultSummary?.toBoundedTaskRunText(TASK_RUN_RESULT_SUMMARY_MAX_CHARS),
        outputMessageId = outputMessageId,
    )

private fun TaskRun.toEntity(): TaskRunEntity =
    TaskRunEntity(
        id = id,
        taskId = taskId,
        status = status.toStorage(),
        scheduledAt = scheduledAt.toEpochMilli(),
        startedAt = startedAt?.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(),
        errorCode = errorCode,
        errorMessage = errorMessage?.toBoundedTaskRunText(TASK_RUN_ERROR_MESSAGE_MAX_CHARS),
        resultSummary = resultSummary?.toBoundedTaskRunText(TASK_RUN_RESULT_SUMMARY_MAX_CHARS),
        outputMessageId = outputMessageId,
    )

private fun String.toBoundedTaskRunText(maxChars: Int): String =
    if (length <= maxChars) {
        this
    } else {
        take(maxChars)
    }

private fun TaskExecutionMode.toStorage(): String =
    when (this) {
        TaskExecutionMode.MainSession -> "MAIN_SESSION"
        TaskExecutionMode.IsolatedSession -> "ISOLATED_SESSION"
    }

private fun String.toTaskExecutionMode(): TaskExecutionMode =
    when (this) {
        "MAIN_SESSION" -> TaskExecutionMode.MainSession
        "ISOLATED_SESSION" -> TaskExecutionMode.IsolatedSession
        else -> TaskExecutionMode.MainSession
    }

private fun TaskRunStatus.toStorage(): String =
    when (this) {
        TaskRunStatus.Pending -> "PENDING"
        TaskRunStatus.Running -> "RUNNING"
        TaskRunStatus.Success -> "SUCCESS"
        TaskRunStatus.Failure -> "FAILURE"
        TaskRunStatus.Skipped -> "SKIPPED"
    }

private fun String.toTaskRunStatus(): TaskRunStatus =
    when (this) {
        "PENDING" -> TaskRunStatus.Pending
        "RUNNING" -> TaskRunStatus.Running
        "SUCCESS" -> TaskRunStatus.Success
        "FAILURE" -> TaskRunStatus.Failure
        "SKIPPED" -> TaskRunStatus.Skipped
        else -> TaskRunStatus.Failure
    }

private fun TaskScheduleKindStatsRow.toScheduleKindStats(): TaskRepository.ScheduleKindStats =
    TaskRepository.ScheduleKindStats(
        scheduleKind = scheduleKind,
        taskCount = taskCount,
    )

private fun TaskExecutionModeStatsRow.toExecutionModeStats(): TaskRepository.ExecutionModeStats =
    TaskRepository.ExecutionModeStats(
        executionMode = executionMode.toTaskExecutionMode(),
        taskCount = taskCount,
    )

private fun TaskRunStatusStatsRow.toRunStatusStats(): TaskRepository.RunStatusStats =
    TaskRepository.RunStatusStats(
        status = status.toTaskRunStatus(),
        runCount = runCount,
        oldestScheduledAt = Instant.ofEpochMilli(oldestScheduledAt),
        newestScheduledAt = Instant.ofEpochMilli(newestScheduledAt),
    )

private fun TaskSchedule.initialNextRun(now: Instant): Instant? =
    when (this) {
        is TaskSchedule.Once -> at
        is TaskSchedule.Interval -> if (anchorAt.isAfter(now)) anchorAt else NextRunCalculator.computeNextRun(this, now)
        is TaskSchedule.Cron -> NextRunCalculator.computeNextRun(this, now)
    }
