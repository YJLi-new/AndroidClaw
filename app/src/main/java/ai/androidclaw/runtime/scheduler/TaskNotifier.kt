package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.Task
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

internal const val TASK_RESULTS_NOTIFICATION_CHANNEL_ID = "androidclaw.tasks.results"
internal const val TASK_FAILURES_NOTIFICATION_CHANNEL_ID = "androidclaw.tasks.failures"

interface TaskNotifier {
    fun notifyTaskSucceeded(
        task: Task,
        taskRunId: String,
        trigger: TaskTrigger,
        summary: String?,
        nextRunAt: Instant?,
    )

    fun notifyTaskFailed(
        task: Task,
        taskRunId: String,
        trigger: TaskTrigger,
        errorCode: String?,
        errorMessage: String,
        nextRunAt: Instant?,
    )
}

class AndroidTaskNotifier(
    private val context: Context,
) : TaskNotifier {
    override fun notifyTaskSucceeded(
        task: Task,
        taskRunId: String,
        trigger: TaskTrigger,
        summary: String?,
        nextRunAt: Instant?,
    ) {
        postNotification(
            channelId = TASK_RESULTS_NOTIFICATION_CHANNEL_ID,
            notificationId = stableNotificationId(task.id, taskRunId),
            title = "Task completed: ${task.name}",
            body =
                buildSuccessBody(
                    trigger = trigger,
                    summary = summary,
                    nextRunAt = nextRunAt,
                ),
            smallIcon = android.R.drawable.ic_dialog_info,
            priority = NotificationCompat.PRIORITY_LOW,
        )
    }

    override fun notifyTaskFailed(
        task: Task,
        taskRunId: String,
        trigger: TaskTrigger,
        errorCode: String?,
        errorMessage: String,
        nextRunAt: Instant?,
    ) {
        postNotification(
            channelId = TASK_FAILURES_NOTIFICATION_CHANNEL_ID,
            notificationId = stableNotificationId(task.id, taskRunId),
            title = "Task failed: ${task.name}",
            body =
                buildFailureBody(
                    trigger = trigger,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    nextRunAt = nextRunAt,
                ),
            smallIcon = android.R.drawable.ic_dialog_alert,
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        smallIcon: Int,
        priority: Int,
    ) {
        if (!canPostNotifications(context)) {
            return
        }
        ensureTaskNotificationChannels(context)
        val notification =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setAutoCancel(true)
                .apply {
                    buildLaunchPendingIntent(context)?.let(::setContentIntent)
                }.build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

internal fun ensureTaskNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
    }
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val resultsChannel =
        NotificationChannel(
            TASK_RESULTS_NOTIFICATION_CHANNEL_ID,
            "Task results",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Completion notifications for scheduled and run-now task executions."
            setShowBadge(false)
        }
    val failuresChannel =
        NotificationChannel(
            TASK_FAILURES_NOTIFICATION_CHANNEL_ID,
            "Task failures",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Failure notifications for scheduled and run-now task executions."
        }
    notificationManager.createNotificationChannels(listOf(resultsChannel, failuresChannel))
}

private fun buildSuccessBody(
    trigger: TaskTrigger,
    summary: String?,
    nextRunAt: Instant?,
): String =
    buildString {
        append(if (trigger == TaskTrigger.Manual) "Run now completed." else "Scheduled run completed.")
        summary?.takeIf { it.isNotBlank() }?.let {
            append("\n").append(it)
        }
        nextRunAt?.let {
            append("\nNext run: ").append(DateTimeFormatter.ISO_INSTANT.format(it))
        }
    }

private fun buildFailureBody(
    trigger: TaskTrigger,
    errorCode: String?,
    errorMessage: String,
    nextRunAt: Instant?,
): String =
    buildString {
        append(if (trigger == TaskTrigger.Manual) "Run now failed." else "Scheduled run failed.")
        errorCode?.let {
            append("\nCode: ").append(it)
        }
        append("\n").append(errorMessage)
        nextRunAt?.let {
            append("\nNext attempt: ").append(DateTimeFormatter.ISO_INSTANT.format(it))
        }
    }

private fun canPostNotifications(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionStatus =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
            return false
        }
    }
    return true
}

private fun buildLaunchPendingIntent(context: Context): PendingIntent? {
    val launchIntent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(context, 0, launchIntent, flags)
}

private fun stableNotificationId(
    taskId: String,
    taskRunId: String,
): Int = "${taskId}_$taskRunId".hashCode().absoluteValue
