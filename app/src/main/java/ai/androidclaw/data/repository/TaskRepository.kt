package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.TaskDao
import ai.androidclaw.data.db.dao.TaskRunDao
import ai.androidclaw.data.db.entity.TaskEntity
import ai.androidclaw.data.db.entity.TaskRunEntity
import ai.androidclaw.data.model.ScheduleSerializer
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepository(
    private val taskDao: TaskDao,
    private val taskRunDao: TaskRunDao,
) {
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
        val entity = TaskEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
            scheduleKind = ScheduleSerializer.kindOf(schedule),
            scheduleSpec = ScheduleSerializer.toJson(schedule),
            executionMode = executionMode.toStorage(),
            targetSessionId = targetSessionId,
            enabled = true,
            precise = precise,
            nextRunAt = schedule.initialNextRun(now)?.toEpochMilli(),
            lastRunAt = null,
            failureCount = 0,
            maxRetries = maxRetries,
            createdAt = now.toEpochMilli(),
            updatedAt = now.toEpochMilli(),
        )
        taskDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun updateTask(task: Task) {
        taskDao.update(task.toEntity())
    }

    suspend fun getTask(id: String): Task? = taskDao.getById(id)?.toDomain()

    fun observeTasks(): Flow<List<Task>> = taskDao.getAllTasks().map { tasks ->
        tasks.map(TaskEntity::toDomain)
    }

    suspend fun getEnabledTasksDueBefore(instant: Instant): List<Task> {
        return taskDao.getEnabledTasksDueBefore(instant.toEpochMilli()).map(TaskEntity::toDomain)
    }

    suspend fun deleteTask(id: String) {
        taskDao.delete(id)
    }

    suspend fun recordRun(taskId: String, scheduledAt: Instant = Instant.now()): TaskRun {
        val entity = TaskRunEntity(
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

    fun observeRuns(taskId: String): Flow<List<TaskRun>> = taskRunDao.getByTaskId(taskId).map { runs ->
        runs.map(TaskRunEntity::toDomain)
    }

    suspend fun getLatestRun(taskId: String): TaskRun? = taskRunDao.getLatestByTaskId(taskId)?.toDomain()

    suspend fun trimRunsOlderThan(instant: Instant): Int {
        return taskRunDao.deleteOlderThan(instant.toEpochMilli())
    }
}

private fun TaskEntity.toDomain(): Task {
    return Task(
        id = id,
        name = name,
        prompt = prompt,
        schedule = ScheduleSerializer.fromJson(scheduleSpec),
        executionMode = executionMode.toTaskExecutionMode(),
        targetSessionId = targetSessionId,
        enabled = enabled,
        precise = precise,
        nextRunAt = nextRunAt?.let(Instant::ofEpochMilli),
        lastRunAt = lastRunAt?.let(Instant::ofEpochMilli),
        failureCount = failureCount,
        maxRetries = maxRetries,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

private fun Task.toEntity(): TaskEntity {
    return TaskEntity(
        id = id,
        name = name,
        prompt = prompt,
        scheduleKind = ScheduleSerializer.kindOf(schedule),
        scheduleSpec = ScheduleSerializer.toJson(schedule),
        executionMode = executionMode.toStorage(),
        targetSessionId = targetSessionId,
        enabled = enabled,
        precise = precise,
        nextRunAt = nextRunAt?.toEpochMilli(),
        lastRunAt = lastRunAt?.toEpochMilli(),
        failureCount = failureCount,
        maxRetries = maxRetries,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )
}

private fun TaskRunEntity.toDomain(): TaskRun {
    return TaskRun(
        id = id,
        taskId = taskId,
        status = status.toTaskRunStatus(),
        scheduledAt = Instant.ofEpochMilli(scheduledAt),
        startedAt = startedAt?.let(Instant::ofEpochMilli),
        finishedAt = finishedAt?.let(Instant::ofEpochMilli),
        errorCode = errorCode,
        errorMessage = errorMessage,
        resultSummary = resultSummary,
        outputMessageId = outputMessageId,
    )
}

private fun TaskRun.toEntity(): TaskRunEntity {
    return TaskRunEntity(
        id = id,
        taskId = taskId,
        status = status.toStorage(),
        scheduledAt = scheduledAt.toEpochMilli(),
        startedAt = startedAt?.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(),
        errorCode = errorCode,
        errorMessage = errorMessage,
        resultSummary = resultSummary,
        outputMessageId = outputMessageId,
    )
}

private fun TaskExecutionMode.toStorage(): String {
    return when (this) {
        TaskExecutionMode.MainSession -> "MAIN_SESSION"
        TaskExecutionMode.IsolatedSession -> "ISOLATED_SESSION"
    }
}

private fun String.toTaskExecutionMode(): TaskExecutionMode {
    return when (this) {
        "MAIN_SESSION" -> TaskExecutionMode.MainSession
        "ISOLATED_SESSION" -> TaskExecutionMode.IsolatedSession
        else -> TaskExecutionMode.MainSession
    }
}

private fun TaskRunStatus.toStorage(): String {
    return when (this) {
        TaskRunStatus.Pending -> "PENDING"
        TaskRunStatus.Running -> "RUNNING"
        TaskRunStatus.Success -> "SUCCESS"
        TaskRunStatus.Failure -> "FAILURE"
        TaskRunStatus.Skipped -> "SKIPPED"
    }
}

private fun String.toTaskRunStatus(): TaskRunStatus {
    return when (this) {
        "PENDING" -> TaskRunStatus.Pending
        "RUNNING" -> TaskRunStatus.Running
        "SUCCESS" -> TaskRunStatus.Success
        "FAILURE" -> TaskRunStatus.Failure
        "SKIPPED" -> TaskRunStatus.Skipped
        else -> TaskRunStatus.Failure
    }
}

private fun TaskSchedule.initialNextRun(now: Instant): Instant? {
    return when (this) {
        is TaskSchedule.Once -> at
        is TaskSchedule.Interval -> if (anchorAt.isAfter(now)) anchorAt else NextRunCalculator.computeNextRun(this, now)
        is TaskSchedule.Cron -> NextRunCalculator.computeNextRun(this, now)
    }
}
