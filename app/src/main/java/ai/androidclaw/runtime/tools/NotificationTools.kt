package ai.androidclaw.runtime.tools

import android.app.Application
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun notificationToolEntries(application: Application): List<ToolRegistry.Entry> =
    buildList {
        add(
            ToolRegistry.Entry(
                descriptor =
                    ToolDescriptor(
                        name = "notifications.post",
                        aliases = listOf("notification.post"),
                        description = "Post a lightweight Android notification.",
                        requiredPermissions =
                            listOf(
                                ToolPermissionRequirement(
                                    permission = android.Manifest.permission.POST_NOTIFICATIONS,
                                    displayName = "Post notifications",
                                ),
                            ),
                        arguments =
                            listOf(
                                ToolArgumentSpec(
                                    name = "title",
                                    required = true,
                                    description = "Notification title",
                                ),
                                ToolArgumentSpec(
                                    name = "body",
                                    description = "Notification body",
                                ),
                            ),
                    ),
                availabilityProvider = { notificationToolAvailability(application) },
            ) { _, arguments ->
                val title = arguments["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val body = arguments["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val notificationManager = NotificationManagerCompat.from(application)
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        application,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return@Entry ToolExecutionResult.failure(
                        summary = "Grant notification permission to use notifications.post.",
                        errorCode = "PERMISSION_REQUIRED",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PERMISSION_REQUIRED")
                                put("toolName", "notifications.post")
                            },
                    )
                }
                if (!notificationManager.areNotificationsEnabled()) {
                    return@Entry ToolExecutionResult.failure(
                        summary = "Enable app notifications to use notifications.post.",
                        errorCode = "TOOL_UNAVAILABLE",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_UNAVAILABLE")
                                put("toolName", "notifications.post")
                            },
                    )
                }
                ensureToolNotificationChannel(application)
                val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                notificationManager.notify(
                    notificationId,
                    NotificationCompat
                        .Builder(application, TOOL_NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build(),
                )
                ToolExecutionResult.success(
                    summary = "Posted notification \"$title\".",
                    payload =
                        buildJsonObject {
                            put("notificationId", notificationId)
                            put("title", title)
                            put("body", body)
                        },
                )
            },
        )
    }
