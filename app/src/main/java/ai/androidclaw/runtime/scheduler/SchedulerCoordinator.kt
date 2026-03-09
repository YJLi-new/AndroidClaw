package ai.androidclaw.runtime.scheduler

import android.app.AlarmManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.os.Build
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.TaskRepository
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

class SchedulerCoordinator(
    private val application: Application,
    private val clock: Clock,
    private val taskRepository: TaskRepository? = null,
    private val eventLogRepository: EventLogRepository? = null,
) {
    private val alarmManager = application.getSystemService(AlarmManager::class.java)
    private val workManager by lazy(LazyThreadSafetyMode.NONE) {
        WorkManager.getInstance(application)
    }
    internal val taskPlanner = TaskPlanner()

    fun capabilities(): SchedulerCapabilities {
        val diagnostics = diagnostics()
        return SchedulerCapabilities(
            minimumBackgroundInterval = Duration.ofMinutes(15),
            supportsExactAlarms = diagnostics.supportsExactAlarms,
            supportedKinds = listOf("once", "interval", "cron"),
        )
    }

    fun diagnostics(): SchedulerDiagnostics {
        val supportsExactAlarms = alarmManager != null
        val exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            supportsExactAlarms
        }
        val standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            application.getSystemService(UsageStatsManager::class.java)
                ?.appStandbyBucket
                ?.let { bucket -> StandbyBucketInfo(value = bucket, label = standbyBucketLabel(bucket)) }
        } else {
            null
        }
        return SchedulerDiagnostics(
            supportsExactAlarms = supportsExactAlarms,
            exactAlarmGranted = exactAlarmGranted,
            standbyBucket = standbyBucket,
        )
    }

    fun nextRunPreview(expression: String, zoneId: ZoneId = ZoneId.systemDefault()): Instant? {
        return NextRunCalculator.computeNextRun(
            schedule = TaskSchedule.Cron(
                expression = CronExpression.parse(expression),
                zoneId = zoneId,
            ),
            after = clock.instant(),
        )
    }

    suspend fun scheduleTask(taskId: String) {
        val repository = requireTaskRepository()
        val task = repository.getTask(taskId)
        if (task == null || !task.enabled) {
            cancelTask(taskId)
            return
        }

        val scheduledAt = taskPlanner.initialScheduleAt(task, clock.instant())
        if (scheduledAt == null) {
            cancelExactAlarm(taskId)
            workManager.cancelUniqueWork(nextWorkName(taskId))
            return
        }
        if (task.nextRunAt != scheduledAt) {
            repository.updateTask(
                task.copy(
                    nextRunAt = scheduledAt,
                    updatedAt = clock.instant(),
                ),
            )
        }
        val diagnostics = diagnostics()
        val decision = task.schedulingDecision(diagnostics)
        when (decision.path) {
            TaskSchedulingPath.ExactAlarm -> {
                workManager.cancelUniqueWork(nextWorkName(taskId))
                scheduleExactAlarm(taskId, scheduledAt)
            }
            TaskSchedulingPath.WorkManagerApproximate -> {
                cancelExactAlarm(taskId)
                enqueueWork(
                    uniqueWorkName = nextWorkName(taskId),
                    taskId = taskId,
                    trigger = TaskTrigger.Scheduled,
                    scheduledAt = scheduledAt,
                )
            }
        }
        eventLogRepository?.log(
            category = EventCategory.Scheduler,
            level = if (decision.degradedReason == null) EventLevel.Info else EventLevel.Warn,
            message = when {
                decision.degradedReason != null -> "Exact alarm degraded for task ${task.name}."
                decision.path == TaskSchedulingPath.ExactAlarm -> "Scheduled exact alarm for task ${task.name}."
                else -> "Scheduled task ${task.name}."
            },
            details = buildString {
                append("nextRunAt=").append(scheduledAt)
                append(" path=").append(decision.path.name)
                append(" precision=").append(task.precisionMode.name)
                diagnostics.standbyBucket?.let { bucket ->
                    append(" standbyBucket=").append(bucket.label)
                }
                decision.degradedReason?.let { reason ->
                    append(" degradedReason=").append(reason)
                }
            },
        )
    }

    suspend fun cancelTask(taskId: String) {
        cancelExactAlarm(taskId)
        workManager.cancelUniqueWork(nextWorkName(taskId))
        workManager.cancelUniqueWork(runNowWorkName(taskId))
        eventLogRepository?.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Info,
            message = "Cancelled scheduled work for task $taskId.",
        )
    }

    suspend fun rescheduleAll() {
        val repository = requireTaskRepository()
        repository.observeTasks().first().forEach { task ->
            if (task.enabled) {
                scheduleTask(task.id)
            } else {
                cancelTask(task.id)
            }
        }
    }

    suspend fun runNow(taskId: String) {
        val repository = requireTaskRepository()
        val task = repository.getTask(taskId) ?: return
        enqueueWork(
            uniqueWorkName = runNowWorkName(taskId),
            taskId = taskId,
            trigger = TaskTrigger.Manual,
            scheduledAt = clock.instant(),
        )
        eventLogRepository?.log(
            category = EventCategory.Scheduler,
            level = EventLevel.Info,
            message = "Queued run-now for task ${task.name}.",
        )
    }

    private fun enqueueWork(
        uniqueWorkName: String,
        taskId: String,
        trigger: TaskTrigger,
        scheduledAt: Instant,
    ) {
        val initialDelay = Duration.between(clock.instant(), scheduledAt).coerceAtLeast(Duration.ZERO)
        val request = buildTaskExecutionWorkRequest(
            taskId = taskId,
            trigger = trigger,
            scheduledAt = scheduledAt,
            initialDelay = initialDelay,
        )
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun scheduleExactAlarm(taskId: String, scheduledAt: Instant) {
        val manager = alarmManager ?: return
        val pendingIntent = buildTaskExactAlarmPendingIntent(
            context = application,
            taskId = taskId,
            scheduledAt = scheduledAt,
        )
        manager.cancel(pendingIntent)
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            scheduledAt.toEpochMilli(),
            pendingIntent,
        )
    }

    private fun cancelExactAlarm(taskId: String) {
        alarmManager?.cancel(
            buildTaskExactAlarmPendingIntent(
                context = application,
                taskId = taskId,
            ),
        )
    }

    private fun requireTaskRepository(): TaskRepository {
        return requireNotNull(taskRepository) {
            "TaskRepository is required for scheduler execution APIs."
        }
    }

    companion object {
        fun nextWorkName(taskId: String): String = "task-next:$taskId"

        fun runNowWorkName(taskId: String): String = "task-run-now:$taskId"

        fun taskTag(taskId: String): String = "task:$taskId"
    }
}

enum class TaskTrigger(val storageValue: String) {
    Scheduled("scheduled"),
    Manual("manual");

    companion object {
        fun fromStorage(value: String?): TaskTrigger {
            return when (value) {
                Manual.storageValue -> Manual
                else -> Scheduled
            }
        }
    }
}
