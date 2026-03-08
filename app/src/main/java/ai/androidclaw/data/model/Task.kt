package ai.androidclaw.data.model

import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import java.time.Instant

data class Task(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: TaskSchedule,
    val executionMode: TaskExecutionMode,
    val targetSessionId: String?,
    val enabled: Boolean,
    val precise: Boolean,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val failureCount: Int,
    val maxRetries: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
