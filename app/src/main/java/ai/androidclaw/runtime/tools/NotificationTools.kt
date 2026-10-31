package ai.androidclaw.runtime.tools

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

internal const val TOOL_NOTIFICATION_CHANNEL_ID = "androidclaw_tool_notifications"
private const val NOTIFY_TITLE_MAX_CHARS = 120
private const val NOTIFY_BODY_MAX_CHARS = 1_000
private val nextNotificationId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 10_000)

internal fun notifyToolEntry(application: Application): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = "notify",
                aliases = listOf("notifications.post", "notification.post"),
                description = "Show an Android notification to the user. Works from scheduled tasks too.",
                riskTier = ToolRiskTier.ExternalSideEffect,
                allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                requiredPermissions =
                    listOf(
                        ToolPermissionRequirement(
                            permission = android.Manifest.permission.POST_NOTIFICATIONS,
                            displayName = "Post notifications",
                        ),
                    ),
                arguments =
                    listOf(
                        stringArg("title", "Notification title.", required = true),
                        stringArg("body", "Notification text."),
                    ),
            ),
        availabilityProvider = { notificationAvailability(application) },
    ) { _, arguments ->
        val title = arguments.requireText("title", "notify").clip(NOTIFY_TITLE_MAX_CHARS)
        val body = arguments.optionalText("body").orEmpty().clip(NOTIFY_BODY_MAX_CHARS)
        ensureToolNotificationChannel(application)
        val notificationId = nextNotificationId.incrementAndGet()
        val notification =
            NotificationCompat
                .Builder(application, TOOL_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(application, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@Entry toolFailure("PERMISSION_REQUIRED", "Allow notifications for AndroidClaw to use notify.")
        }
        try {
            NotificationManagerCompat.from(application).notify(notificationId, notification)
        } catch (_: SecurityException) {
            return@Entry toolFailure("PERMISSION_REQUIRED", "Allow notifications for AndroidClaw to use notify.")
        }
        toolSuccess(
            summary = "Posted notification \"$title\".",
            payload = buildJsonObject { put("notificationId", notificationId) },
        )
    }

private fun notificationAvailability(application: Application): ToolAvailability =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(application, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED ->
            ToolAvailability(
                status = ToolAvailabilityStatus.PermissionRequired,
                reason = "Allow notifications for AndroidClaw to use notify.",
            )
        !NotificationManagerCompat.from(application).areNotificationsEnabled() ->
            ToolAvailability(
                status = ToolAvailabilityStatus.Unavailable,
                reason = "Notifications for AndroidClaw are turned off in system settings.",
            )
        else -> ToolAvailability()
    }

private fun ensureToolNotificationChannel(application: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
    }
    val manager = application.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(TOOL_NOTIFICATION_CHANNEL_ID) != null) {
        return
    }
    manager.createNotificationChannel(
        NotificationChannel(
            TOOL_NOTIFICATION_CHANNEL_ID,
            "Assistant notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
}
