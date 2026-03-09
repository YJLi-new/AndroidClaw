package ai.androidclaw.runtime.scheduler

import android.content.Context
import android.os.Build
import ai.androidclaw.app.AppContainer
import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class TaskExecutionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val trigger = TaskTrigger.fromStorage(inputData.getString(KEY_TRIGGER))
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT_EPOCH_MS, System.currentTimeMillis())
            .let(Instant::ofEpochMilli)
        val container = (applicationContext as AndroidClawApplication).container
        val taskRepository = container.taskRepository
        val eventLogRepository = container.eventLogRepository
        val task = taskRepository.getTask(taskId)

        if (task == null) {
            eventLogRepository.log(
                category = EventCategory.Scheduler,
                level = EventLevel.Warn,
                message = "Skipped missing task $taskId.",
            )
            return Result.success()
        }

        val run = taskRepository.recordRun(
            taskId = task.id,
            scheduledAt = scheduledAt,
        )
        val startedAt = Instant.now()

        if (trigger == TaskTrigger.Scheduled && !task.enabled) {
            taskRepository.updateRun(
                run.copy(
                    status = TaskRunStatus.Skipped,
                    startedAt = startedAt,
                    finishedAt = startedAt,
                    errorCode = "TASK_DISABLED",
                    errorMessage = "Task was disabled before execution.",
                    resultSummary = "Skipped disabled task.",
                ),
            )
            return Result.success()
        }

        if (
            trigger == TaskTrigger.Scheduled &&
            task.nextRunAt != null &&
            task.nextRunAt.isAfter(startedAt)
        ) {
            taskRepository.updateRun(
                run.copy(
                    status = TaskRunStatus.Skipped,
                    startedAt = startedAt,
                    finishedAt = startedAt,
                    errorCode = "TASK_NOT_DUE",
                    errorMessage = "Task woke before its scheduled due time.",
                    resultSummary = "Skipped early wake-up.",
                ),
            )
            container.schedulerCoordinator.scheduleTask(task.id)
            return Result.success()
        }

        taskRepository.updateRun(
            run.copy(
                status = TaskRunStatus.Running,
                startedAt = startedAt,
            ),
        )
        val diagnostics = container.schedulerCoordinator.diagnostics()
        eventLogRepository.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Info,
            message = "Task ${task.name} started.",
            details = buildString {
                append("taskRunId=").append(run.id)
                append(" trigger=").append(trigger.storageValue)
                diagnostics.standbyBucket?.let { bucket ->
                    append(" standbyBucket=").append(bucket.label)
                }
            },
        )

        try {
            val execution = container.taskRuntimeExecutor.execute(
                task = task,
                taskRunId = run.id,
            )
            val finishedAt = Instant.now()
            if (execution.success) {
                taskRepository.updateRun(
                    run.copy(
                        status = TaskRunStatus.Success,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        resultSummary = execution.summary,
                        outputMessageId = execution.outputMessageId,
                    ),
                )
                updateTaskStateAfterSuccess(
                    container = container,
                    task = task,
                    finishedAt = finishedAt,
                    trigger = trigger,
                )
                eventLogRepository.log(
                    category = EventCategory.Scheduler,
                    level = EventLevel.Info,
                    message = "Task ${task.name} completed.",
                    details = "taskRunId=${run.id}",
                )
            } else {
                taskRepository.updateRun(
                    run.copy(
                        status = TaskRunStatus.Failure,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        errorCode = execution.errorCode,
                        errorMessage = execution.errorMessage ?: execution.summary,
                        resultSummary = execution.summary,
                        outputMessageId = execution.outputMessageId,
                    ),
                )
                updateTaskStateAfterFailure(
                    container = container,
                    task = task,
                    finishedAt = finishedAt,
                    trigger = trigger,
                    errorCode = execution.errorCode ?: "TASK_EXECUTION_FAILED",
                    errorMessage = execution.errorMessage ?: execution.summary,
                    retryable = execution.retryable,
                )
            }
        } catch (error: ModelProviderException) {
            val finishedAt = Instant.now()
            taskRepository.updateRun(
                run.copy(
                    status = TaskRunStatus.Failure,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    errorCode = error.kind.toTaskErrorCode(),
                    errorMessage = error.userMessage,
                    resultSummary = error.userMessage,
                ),
            )
            updateTaskStateAfterFailure(
                container = container,
                task = task,
                finishedAt = finishedAt,
                trigger = trigger,
                errorCode = error.kind.toTaskErrorCode(),
                errorMessage = error.userMessage,
                retryable = error.kind.isRetryable(),
            )
        } catch (error: Exception) {
            val finishedAt = Instant.now()
            val message = error.message ?: "Task execution failed."
            taskRepository.updateRun(
                run.copy(
                    status = TaskRunStatus.Failure,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    errorCode = "WORK_INTERRUPTED",
                    errorMessage = message,
                    resultSummary = message,
                ),
            )
            updateTaskStateAfterFailure(
                container = container,
                task = task,
                finishedAt = finishedAt,
                trigger = trigger,
                errorCode = "WORK_INTERRUPTED",
                errorMessage = message,
                retryable = true,
            )
        } finally {
            if (isStopped) {
                val stopReasonLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    workStopReasonLabel(stopReason)
                } else {
                    "unavailable_pre_s"
                }
                withContext(NonCancellable) {
                    val stopDiagnostics = container.schedulerCoordinator.diagnostics()
                    eventLogRepository.log(
                        category = EventCategory.Scheduler,
                        level = EventLevel.Warn,
                        message = "Task worker stopped.",
                        details = buildString {
                            append("taskId=").append(task.id)
                            append(" trigger=").append(trigger.storageValue)
                            append(" stopReason=").append(stopReasonLabel)
                            stopDiagnostics.standbyBucket?.let { bucket ->
                                append(" standbyBucket=").append(bucket.label)
                            }
                        },
                    )
                }
            }
        }
        return Result.success()
    }

    private suspend fun updateTaskStateAfterSuccess(
        container: AppContainer,
        task: ai.androidclaw.data.model.Task,
        finishedAt: Instant,
        trigger: TaskTrigger,
    ) {
        if (trigger == TaskTrigger.Manual) {
            container.taskRepository.updateTask(
                task.copy(
                    lastRunAt = finishedAt,
                    updatedAt = finishedAt,
                ),
            )
            return
        }

        val nextRunAt = container.schedulerCoordinator.taskPlanner.nextScheduledRun(task, finishedAt)
        container.taskRepository.updateTask(
            task.copy(
                nextRunAt = nextRunAt,
                lastRunAt = finishedAt,
                failureCount = 0,
                updatedAt = finishedAt,
            ),
        )
        if (task.enabled && nextRunAt != null) {
            container.schedulerCoordinator.scheduleTask(task.id)
        }
    }

    private suspend fun updateTaskStateAfterFailure(
        container: AppContainer,
        task: ai.androidclaw.data.model.Task,
        finishedAt: Instant,
        trigger: TaskTrigger,
        errorCode: String,
        errorMessage: String,
        retryable: Boolean,
    ) {
        if (trigger == TaskTrigger.Manual) {
            container.taskRepository.updateTask(
                task.copy(
                    lastRunAt = finishedAt,
                    updatedAt = finishedAt,
                ),
            )
            container.eventLogRepository.log(
                category = EventCategory.Scheduler,
                level = EventLevel.Error,
                message = "Manual task ${task.name} failed.",
                details = "$errorCode: $errorMessage",
            )
            return
        }

        val newFailureCount = task.failureCount + 1
        val nextScheduledAt = container.schedulerCoordinator.taskPlanner.nextScheduledRun(task, finishedAt)
        val retryAt = if (retryable) {
            container.schedulerCoordinator.taskPlanner.nextRetryAt(task, newFailureCount, finishedAt)
        } else {
            null
        }
        val nextRunAt = container.schedulerCoordinator.taskPlanner.selectNextRun(nextScheduledAt, retryAt)
        container.taskRepository.updateTask(
            task.copy(
                nextRunAt = nextRunAt,
                lastRunAt = finishedAt,
                failureCount = newFailureCount,
                updatedAt = finishedAt,
            ),
        )
        if (task.enabled && nextRunAt != null) {
            container.schedulerCoordinator.scheduleTask(task.id)
        }
        container.eventLogRepository.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Error,
            message = "Task ${task.name} failed.",
            details = "$errorCode: $errorMessage",
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_TRIGGER = "task_trigger"
        const val KEY_SCHEDULED_AT_EPOCH_MS = "task_scheduled_at_epoch_ms"
    }
}

private fun ModelProviderFailureKind.isRetryable(): Boolean {
    return when (this) {
        ModelProviderFailureKind.Network,
        ModelProviderFailureKind.Timeout,
            -> true
        ModelProviderFailureKind.Configuration,
        ModelProviderFailureKind.Authentication,
        ModelProviderFailureKind.Response,
            -> false
    }
}

private fun ModelProviderFailureKind.toTaskErrorCode(): String {
    return when (this) {
        ModelProviderFailureKind.Configuration -> "MISSING_API_KEY"
        ModelProviderFailureKind.Authentication -> "AUTHENTICATION_FAILED"
        ModelProviderFailureKind.Network -> "NETWORK_UNAVAILABLE"
        ModelProviderFailureKind.Timeout -> "WORK_INTERRUPTED"
        ModelProviderFailureKind.Response -> "PROVIDER_RESPONSE_INVALID"
    }
}
