package ai.androidclaw.data.model

import java.time.Instant

enum class TaskRunStatus {
    Pending,
    Running,
    Success,
    Failure,
    Skipped,
}

data class TaskRun(
    val id: String,
    val taskId: String,
    val status: TaskRunStatus,
    val scheduledAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val errorCode: String?,
    val errorMessage: String?,
    val resultSummary: String?,
    val outputMessageId: String?,
)
