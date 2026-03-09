package ai.androidclaw.runtime.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskExactAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TASK_EXACT_ALARM) {
            return
        }
        val taskId = intent.getStringExtra(TaskExecutionWorker.KEY_TASK_ID) ?: return
        val scheduledAt = Instant.ofEpochMilli(
            intent.getLongExtra(
                TaskExecutionWorker.KEY_SCHEDULED_AT_EPOCH_MS,
                System.currentTimeMillis(),
            ),
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            SchedulerCoordinator.nextWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            buildTaskExecutionWorkRequest(
                taskId = taskId,
                trigger = TaskTrigger.Scheduled,
                scheduledAt = scheduledAt,
                initialDelay = Duration.ZERO,
            ),
        )
    }
}

class ExactAlarmPermissionStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            return
        }
        val pendingResult = goAsync()
        val application = context.applicationContext as? AndroidClawApplication ?: run {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val diagnostics = application.container.schedulerCoordinator.diagnostics()
                application.container.eventLogRepository.log(
                    category = EventCategory.Scheduler,
                    level = EventLevel.Info,
                    message = "Exact alarm permission state changed.",
                    details = "granted=${diagnostics.exactAlarmGranted}",
                )
                application.container.schedulerCoordinator.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun buildTaskExecutionWorkRequest(
    taskId: String,
    trigger: TaskTrigger,
    scheduledAt: Instant,
    initialDelay: Duration,
): OneTimeWorkRequest {
    return OneTimeWorkRequestBuilder<TaskExecutionWorker>()
        .setInputData(
            androidx.work.Data.Builder()
                .putString(TaskExecutionWorker.KEY_TASK_ID, taskId)
                .putString(TaskExecutionWorker.KEY_TRIGGER, trigger.storageValue)
                .putLong(TaskExecutionWorker.KEY_SCHEDULED_AT_EPOCH_MS, scheduledAt.toEpochMilli())
                .build(),
        )
        .setInitialDelay(initialDelay)
        .addTag(SchedulerCoordinator.taskTag(taskId))
        .build()
}

internal fun buildTaskExactAlarmPendingIntent(
    context: Context,
    taskId: String,
    scheduledAt: Instant? = null,
): PendingIntent {
    return PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, TaskExactAlarmReceiver::class.java)
            .setAction(ACTION_TASK_EXACT_ALARM)
            .setData(
                Uri.Builder()
                    .scheme("androidclaw")
                    .authority("scheduler")
                    .appendPath("task")
                    .appendPath(taskId)
                    .build(),
            )
            .putExtra(TaskExecutionWorker.KEY_TASK_ID, taskId)
            .apply {
                if (scheduledAt != null) {
                    putExtra(TaskExecutionWorker.KEY_SCHEDULED_AT_EPOCH_MS, scheduledAt.toEpochMilli())
                }
            },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private const val ACTION_TASK_EXACT_ALARM = "ai.androidclaw.runtime.scheduler.action.TASK_EXACT_ALARM"
