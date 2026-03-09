package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun createBuiltInToolRegistry(
    application: Application,
    settingsDataStore: SettingsDataStore,
    sessionRepository: SessionRepository,
    taskRepository: TaskRepository,
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
): ToolRegistry {
    lateinit var toolRegistry: ToolRegistry
    toolRegistry = ToolRegistry(
        tools = listOf(
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "tasks.list",
                    aliases = listOf("task.list"),
                    description = "List known automation capabilities and any saved tasks.",
                ),
            ) { _ ->
                val tasks = taskRepository.observeTasks().first()
                ToolExecutionResult.success(
                    summary = if (tasks.isEmpty()) {
                        "No persisted tasks yet. Scheduler supports once, interval, and cron execution."
                    } else {
                        "Found ${tasks.size} persisted task(s)."
                    },
                    payload = buildJsonObject {
                        put("supportsOnce", true)
                        put("supportsInterval", true)
                        put("supportsCron", true)
                        put("taskCount", tasks.size)
                        put("taskNames", buildJsonArray {
                            tasks.forEach { add(JsonPrimitive(it.name)) }
                        })
                    },
                )
            },
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "health.status",
                    aliases = listOf("health.check"),
                    description = "Return lightweight runtime health information and tool availability.",
                ),
            ) { _ ->
                val providerType = settingsDataStore.settings.first().providerType
                ToolExecutionResult.success(
                    summary = "Runtime bootstrapped with ${providerType.displayName}, bundled skills, and scheduler preview support.",
                    payload = buildJsonObject {
                        put("provider", providerType.providerId)
                        put("schedulerReady", true)
                        put("skillsReady", true)
                        put("tools", buildJsonArray {
                            toolRegistry.descriptors().forEach { tool ->
                                add(
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("aliases", buildJsonArray {
                                            tool.aliases.forEach { add(JsonPrimitive(it)) }
                                        })
                                        put("availabilityStatus", tool.availability.status.name)
                                        put("foregroundRequired", tool.foregroundRequired)
                                    },
                                )
                            }
                        })
                    },
                )
            },
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "sessions.list",
                    aliases = listOf("session.list"),
                    description = "List known chat sessions.",
                ),
            ) { _ ->
                val sessions = sessionRepository.observeSessions().first()
                ToolExecutionResult.success(
                    summary = if (sessions.isEmpty()) {
                        "No sessions found."
                    } else {
                        "Found ${sessions.size} session(s)."
                    },
                    payload = buildJsonObject {
                        put("sessionCount", sessions.size)
                        put("sessions", buildJsonArray {
                            sessions.forEach { session ->
                                add(
                                    buildJsonObject {
                                        put("id", session.id)
                                        put("title", session.title)
                                        put("isMain", session.isMain)
                                        put("archived", session.archived)
                                    },
                                )
                            }
                        })
                    },
                )
            },
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "skills.list",
                    aliases = listOf("skill.list"),
                    description = "List bundled skills and their current eligibility.",
                ),
            ) { _ ->
                val skills = bundledSkillsProvider()
                ToolExecutionResult.success(
                    summary = if (skills.isEmpty()) {
                        "No bundled skills found."
                    } else {
                        "Found ${skills.size} bundled skill(s)."
                    },
                    payload = buildJsonObject {
                        put("skillCount", skills.size)
                        put("skills", buildJsonArray {
                            skills.forEach { skill ->
                                add(
                                    buildJsonObject {
                                        put("id", skill.id)
                                        put("name", skill.displayName)
                                        put("enabled", skill.enabled)
                                        put("sourceType", skill.sourceType.name)
                                        put("eligibilityStatus", skill.eligibility.status.name)
                                        put("eligibilityReasons", buildJsonArray {
                                            skill.eligibility.reasons.forEach { add(JsonPrimitive(it)) }
                                        })
                                    },
                                )
                            }
                        })
                    },
                )
            },
            ToolRegistry.Entry(
                descriptor = ToolDescriptor(
                    name = "notifications.post",
                    aliases = listOf("notification.post"),
                    description = "Post a lightweight Android notification.",
                    requiredPermissions = listOf(
                        ToolPermissionRequirement(
                            permission = android.Manifest.permission.POST_NOTIFICATIONS,
                            displayName = "Post notifications",
                        ),
                    ),
                    arguments = listOf(
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
            ) { arguments ->
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
                        payload = buildJsonObject {
                            put("errorCode", "PERMISSION_REQUIRED")
                            put("toolName", "notifications.post")
                        },
                    )
                }
                if (!notificationManager.areNotificationsEnabled()) {
                    return@Entry ToolExecutionResult.failure(
                        summary = "Enable app notifications to use notifications.post.",
                        errorCode = "TOOL_UNAVAILABLE",
                        payload = buildJsonObject {
                            put("errorCode", "TOOL_UNAVAILABLE")
                            put("toolName", "notifications.post")
                        },
                    )
                }
                ensureToolNotificationChannel(application)
                val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                notificationManager.notify(
                    notificationId,
                    NotificationCompat.Builder(application, TOOL_NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build(),
                )
                ToolExecutionResult.success(
                    summary = "Posted notification \"$title\".",
                    payload = buildJsonObject {
                        put("notificationId", notificationId)
                        put("title", title)
                        put("body", body)
                    },
                )
            },
        ),
    )
    return toolRegistry
}

private const val TOOL_NOTIFICATION_CHANNEL_ID = "androidclaw.tools"

internal fun notificationToolAvailability(application: Application): ToolAvailability {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            application,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return ToolAvailability(
            status = ToolAvailabilityStatus.PermissionRequired,
            reason = "Grant notification permission to use notifications.post.",
        )
    }
    if (!NotificationManagerCompat.from(application).areNotificationsEnabled()) {
        return ToolAvailability(
            status = ToolAvailabilityStatus.Unavailable,
            reason = "Enable app notifications to use notifications.post.",
        )
    }
    return ToolAvailability()
}

private fun ensureToolNotificationChannel(application: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
    }
    val notificationManager = application.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        TOOL_NOTIFICATION_CHANNEL_ID,
        "AndroidClaw tools",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "Notifications created by AndroidClaw tool executions."
    }
    notificationManager.createNotificationChannel(channel)
}
