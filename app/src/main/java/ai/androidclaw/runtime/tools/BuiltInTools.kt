package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock

internal fun createBuiltInToolRegistry(
    application: Application,
    settingsDataStore: SettingsDataStore,
    sessionRepository: SessionRepository,
    taskRepository: TaskRepository,
    schedulerCoordinator: SchedulerCoordinator,
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
    messageRepository: MessageRepository,
    memoryRepository: MemoryRepository? = null,
    eventLogRepository: EventLogRepository? = null,
    clock: Clock = Clock.systemDefaultZone(),
): ToolRegistry {
    lateinit var toolRegistry: ToolRegistry
    toolRegistry =
        ToolRegistry(
            eventLogger = { level, message, details ->
                eventLogRepository?.log(
                    category = EventCategory.Tool,
                    level = level,
                    message = message,
                    details = details,
                )
            },
            tools =
                buildList {
                    addAll(
                        taskToolEntries(
                            taskRepository = taskRepository,
                            sessionRepository = sessionRepository,
                            schedulerCoordinator = schedulerCoordinator,
                            clock = clock,
                        ),
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "health.status",
                                    aliases = listOf("health.check"),
                                    description = "Return lightweight runtime health information and tool availability.",
                                ),
                        ) { _, _ ->
                            val providerType = settingsDataStore.settings.first().providerType
                            ToolExecutionResult.success(
                                summary = "Runtime bootstrapped with ${providerType.displayName}, bundled skills, and scheduler preview support.",
                                payload =
                                    buildJsonObject {
                                        put("provider", providerType.providerId)
                                        put("schedulerReady", true)
                                        put("skillsReady", true)
                                        put(
                                            "tools",
                                            buildJsonArray {
                                                toolRegistry.descriptors().forEach { tool ->
                                                    add(
                                                        buildJsonObject {
                                                            put("name", tool.name)
                                                            put(
                                                                "aliases",
                                                                buildJsonArray {
                                                                    tool.aliases.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                            put("availabilityStatus", tool.availability.status.name)
                                                            put("foregroundRequired", tool.foregroundRequired)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    memoryRepository?.let { repository ->
                        addAll(
                            memoryToolEntries(
                                settingsDataStore = settingsDataStore,
                                memoryRepository = repository,
                            ),
                        )
                    }
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.list",
                                    aliases = listOf("session.list"),
                                    description = "List known chat sessions.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "includeArchived",
                                                description = "Set true to include archived sessions.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val includeArchived = arguments.optionalBoolean("includeArchived")
                            val activeSessions = sessionRepository.observeSessions().first()
                            val sessions =
                                if (includeArchived) {
                                    (activeSessions + sessionRepository.observeArchivedSessions().first())
                                        .sortedByDescending { session -> session.updatedAt }
                                } else {
                                    activeSessions
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (sessions.isEmpty()) {
                                        "No sessions found."
                                    } else {
                                        "Found ${sessions.size} session(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionCount", sessions.size)
                                        put("includeArchived", includeArchived)
                                        put(
                                            "sessions",
                                            buildJsonArray {
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
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.get",
                                    aliases = listOf("session.get"),
                                    description = "Return details for the active or specified chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to inspect.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            val session =
                                sessionRepository.getSession(sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session $sessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sessionId)
                                            },
                                    )
                            val messageCount = messageRepository.getMessageCount(sessionId)
                            ToolExecutionResult.success(
                                summary = "Loaded session \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("title", session.title)
                                        put("isMain", session.isMain)
                                        put("archived", session.archived)
                                        put("createdAtIso", session.createdAt.toString())
                                        put("updatedAtIso", session.updatedAt.toString())
                                        put("messageCount", messageCount)
                                        put("summaryText", session.summaryText?.let(::JsonPrimitive) ?: JsonNull)
                                        put("summaryLength", session.summaryText?.length ?: 0)
                                        put(
                                            "compactedUntilMessageId",
                                            session.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull,
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.search",
                                    aliases = listOf("session.search"),
                                    description = "Search active chat sessions by title.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "query",
                                                required = true,
                                                description = "Title text to search for.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum result count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val query =
                                arguments.optionalText("query")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "sessions.search requires a non-empty query.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "query")
                                            },
                                    )
                            val limit = arguments.optionalInt("limit", SESSION_SEARCH_DEFAULT_LIMIT)
                            val results = sessionRepository.searchSessions(query = query, limit = limit)
                            ToolExecutionResult.success(
                                summary =
                                    if (results.isEmpty()) {
                                        "No active sessions matched \"$query\"."
                                    } else {
                                        "Found ${results.size} active session(s) matching \"$query\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("query", query)
                                        put("resultCount", results.size)
                                        put("activeOnly", true)
                                        put(
                                            "sessions",
                                            buildJsonArray {
                                                results.forEach { result ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", result.sessionId)
                                                            put("title", result.sessionTitle)
                                                            put("archived", false)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.archive",
                                    aliases = listOf("session.archive"),
                                    description = "Archive a normal chat session so it leaves the active session list.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to archive. Defaults to the active session.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to archive.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            val existingSession =
                                sessionRepository.getSession(sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session $sessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sessionId)
                                            },
                                    )
                            if (existingSession.isMain) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "The main session cannot be archived.",
                                    errorCode = "MAIN_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MAIN_SESSION")
                                            put("sessionId", sessionId)
                                        },
                                )
                            }
                            sessionRepository.archiveSession(sessionId)
                            val archivedSession = sessionRepository.getSession(sessionId)
                            ToolExecutionResult.success(
                                summary = "Archived session \"${archivedSession?.title ?: existingSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", sessionId)
                                        put("title", archivedSession?.title ?: existingSession.title)
                                        put("isMain", existingSession.isMain)
                                        put("archived", archivedSession?.archived ?: true)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.unarchive",
                                    aliases = listOf("session.unarchive"),
                                    description = "Restore an archived chat session to the active session list.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to restore. Defaults to the active session.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active or specified session is available to restore.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            val existingSession =
                                sessionRepository.getSession(sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session $sessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sessionId)
                                            },
                                    )
                            sessionRepository.unarchiveSession(sessionId)
                            val restoredSession = sessionRepository.getSession(sessionId)
                            ToolExecutionResult.success(
                                summary = "Restored session \"${restoredSession?.title ?: existingSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", sessionId)
                                        put("title", restoredSession?.title ?: existingSession.title)
                                        put("isMain", restoredSession?.isMain ?: existingSession.isMain)
                                        put("archived", restoredSession?.archived ?: false)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.create",
                                    aliases = listOf("session.create"),
                                    description = "Create a new normal chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "title",
                                                required = true,
                                                description = "Title for the new session.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val title =
                                arguments.optionalText("title")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "sessions.create requires a non-empty title.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "title")
                                            },
                                    )
                            val session = sessionRepository.createSession(title = title)
                            ToolExecutionResult.success(
                                summary = "Created session \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("title", session.title)
                                        put("isMain", session.isMain)
                                        put("archived", session.archived)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.search",
                                    aliases = listOf("message.search", "chat.search"),
                                    description = "Search active-session chat messages by content.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "query",
                                                required = true,
                                                description = "Message text to search for.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum result count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val query =
                                arguments.optionalText("query")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.search requires a non-empty query.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "query")
                                            },
                                    )
                            val limit = arguments.optionalInt("limit", MESSAGE_SEARCH_DEFAULT_LIMIT)
                            val results = messageRepository.searchMessages(query = query, limit = limit)
                            ToolExecutionResult.success(
                                summary =
                                    if (results.isEmpty()) {
                                        "No active-session messages matched \"$query\"."
                                    } else {
                                        "Found ${results.size} active-session message(s) matching \"$query\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("query", query)
                                        put("resultCount", results.size)
                                        put("activeSessionsOnly", true)
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                results.forEach { result ->
                                                    val contentSnippet = result.content.toMessageSearchSnippet()
                                                    add(
                                                        buildJsonObject {
                                                            put("messageId", result.messageId)
                                                            put("sessionId", result.sessionId)
                                                            put("sessionTitle", result.sessionTitle)
                                                            put("role", result.role.name)
                                                            put("contentSnippet", contentSnippet)
                                                            put("contentLength", result.content.length)
                                                            put("contentTruncated", contentSnippet.length < result.content.length)
                                                            put("createdAtIso", result.createdAt.toString())
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.get",
                                    aliases = listOf("message.get", "chat.message.get"),
                                    description = "Return one chat message by id with session metadata.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messageId",
                                                required = true,
                                                description = "Message identifier to inspect.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val messageId =
                                arguments.optionalText("messageId")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.get requires a non-empty messageId.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "messageId")
                                            },
                                    )
                            val message =
                                messageRepository
                                    .getMessagesByIds(listOf(messageId))
                                    .get(messageId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Message $messageId was not found.",
                                        errorCode = "MISSING_MESSAGE",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_MESSAGE")
                                                put("messageId", messageId)
                                            },
                                    )
                            val session =
                                sessionRepository.getSession(message.sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session ${message.sessionId} for message $messageId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("messageId", messageId)
                                                put("sessionId", message.sessionId)
                                            },
                                    )
                            val contentSnippet = message.content.toMessageSearchSnippet()
                            ToolExecutionResult.success(
                                summary = "Loaded ${message.role.name} message from \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("messageId", message.id)
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("sessionArchived", session.archived)
                                        put("role", message.role.name)
                                        put("contentSnippet", contentSnippet)
                                        put("contentLength", message.content.length)
                                        put("contentTruncated", contentSnippet.length < message.content.length)
                                        put("createdAtIso", message.createdAt.toString())
                                        put("hasProviderMeta", message.providerMeta != null)
                                        put("toolCallId", message.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("taskRunId", message.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.recent",
                                    aliases = listOf("message.recent", "chat.recent"),
                                    description = "Return recent messages for the active or specified chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum message count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to inspect.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            val session =
                                sessionRepository.getSession(sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session $sessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sessionId)
                                            },
                                    )
                            val limit = arguments.optionalInt("limit", MESSAGE_RECENT_DEFAULT_LIMIT)
                            val messages = messageRepository.getRecentMessages(sessionId = sessionId, limit = limit)
                            ToolExecutionResult.success(
                                summary = "Loaded ${messages.size} recent message(s) for \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("archived", session.archived)
                                        put("messageCount", messageRepository.getMessageCount(sessionId))
                                        put("returnedCount", messages.size)
                                        put("recentFirst", true)
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    val contentSnippet = message.content.toMessageSearchSnippet()
                                                    add(
                                                        buildJsonObject {
                                                            put("messageId", message.id)
                                                            put("role", message.role.name)
                                                            put("contentSnippet", contentSnippet)
                                                            put("contentLength", message.content.length)
                                                            put("contentTruncated", contentSnippet.length < message.content.length)
                                                            put("createdAtIso", message.createdAt.toString())
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.rename",
                                    aliases = listOf("session.rename"),
                                    description = "Rename the active or specified chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to rename. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "title",
                                                required = true,
                                                description = "New session title.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to rename.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            val existingSession =
                                sessionRepository.getSession(sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session $sessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sessionId)
                                            },
                                    )
                            val title =
                                arguments.optionalText("title")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "sessions.rename requires a non-empty title.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("sessionId", sessionId)
                                                put("field", "title")
                                            },
                                    )
                            sessionRepository.updateTitle(id = sessionId, title = title)
                            val renamedSession = sessionRepository.getSession(sessionId)
                            val storedTitle = renamedSession?.title ?: title
                            ToolExecutionResult.success(
                                summary = "Renamed session to \"$storedTitle\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", sessionId)
                                        put("previousTitle", existingSession.title)
                                        put("title", storedTitle)
                                        put("isMain", existingSession.isMain)
                                        put("archived", existingSession.archived)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.compact",
                                    aliases = listOf("session.compact"),
                                    description = "Store a compacted summary and mark older active-session messages as summarized.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "summary",
                                                description = "Compacted summary text to store.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "command",
                                                description = "Raw slash command text used as the summary fallback.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "compactedUntilMessageId",
                                                description = "Last message id included in the compacted summary.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to compact.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            if (sessionRepository.getSession(sessionId) == null) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Active session is no longer available to compact.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                            put("sessionId", sessionId)
                                        },
                                )
                            }
                            val compactedUntilMessageId = arguments.optionalText("compactedUntilMessageId")
                            if (compactedUntilMessageId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No earlier messages are available to compact.",
                                    errorCode = "EMPTY_COMPACT_SOURCE",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "EMPTY_COMPACT_SOURCE")
                                            put("sessionId", sessionId)
                                        },
                                )
                            }
                            val boundaryMessage =
                                messageRepository
                                    .getMessagesByIds(listOf(compactedUntilMessageId))
                                    .get(compactedUntilMessageId)
                            if (boundaryMessage?.sessionId != sessionId) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Compaction boundary was not found in this session.",
                                    errorCode = "INVALID_COMPACT_BOUNDARY",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_COMPACT_BOUNDARY")
                                            put("sessionId", sessionId)
                                            put("compactedUntilMessageId", compactedUntilMessageId)
                                        },
                                )
                            }
                            val compactedSummary =
                                arguments.optionalText("summary")
                                    ?: arguments.optionalText("command")
                            if (compactedSummary.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Provide an explicit summary after /compact.",
                                    errorCode = "MISSING_SUMMARY",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SUMMARY")
                                            put("sessionId", sessionId)
                                        },
                                )
                            }
                            val boundedSummary = compactedSummary.take(COMPACT_SUMMARY_MAX_CHARS)
                            sessionRepository.updateSummaryAndCompactionBoundary(
                                id = sessionId,
                                summaryText = boundedSummary,
                                compactedUntilMessageId = compactedUntilMessageId,
                            )
                            ToolExecutionResult.success(
                                summary = "Compacted this session summary and hid older messages.",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", sessionId)
                                        put("summaryText", boundedSummary)
                                        put("summaryLength", boundedSummary.length)
                                        put("truncated", compactedSummary.length > COMPACT_SUMMARY_MAX_CHARS)
                                        put("compactedUntilMessageId", compactedUntilMessageId)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.list",
                                    aliases = listOf("skill.list"),
                                    description = "List bundled skills and their current eligibility.",
                                ),
                        ) { _, _ ->
                            val skills = bundledSkillsProvider()
                            ToolExecutionResult.success(
                                summary =
                                    if (skills.isEmpty()) {
                                        "No bundled skills found."
                                    } else {
                                        "Found ${skills.size} bundled skill(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("skillCount", skills.size)
                                        put(
                                            "skills",
                                            buildJsonArray {
                                                skills.forEach { skill ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", skill.id)
                                                            put("name", skill.displayName)
                                                            put("enabled", skill.enabled)
                                                            put("sourceType", skill.sourceType.name)
                                                            put("eligibilityStatus", skill.eligibility.status.name)
                                                            put(
                                                                "eligibilityReasons",
                                                                buildJsonArray {
                                                                    skill.eligibility.reasons.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                            put(
                                                                "secretStatuses",
                                                                buildJsonArray {
                                                                    skill.secretStatuses.forEach { (envName, configured) ->
                                                                        add(
                                                                            buildJsonObject {
                                                                                put("envName", envName)
                                                                                put("configured", configured)
                                                                            },
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                            put(
                                                                "configStatuses",
                                                                buildJsonArray {
                                                                    skill.configStatuses.forEach { (path, configured) ->
                                                                        add(
                                                                            buildJsonObject {
                                                                                put("path", path)
                                                                                put("configured", configured)
                                                                            },
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                            )
                        },
                    )
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
                },
        )
    return toolRegistry
}

// These handlers are the typed automation contract for v5. They intentionally mirror the
// repository's real schedule model instead of inventing a second scheduler abstraction.
private fun taskToolEntries(
    taskRepository: TaskRepository,
    sessionRepository: SessionRepository,
    schedulerCoordinator: SchedulerCoordinator,
    clock: Clock,
): List<ToolRegistry.Entry> {
    return listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.list",
                    aliases = listOf("task.list"),
                    description = "List known automation capabilities and persisted tasks.",
                ),
        ) { _, _ ->
            val diagnostics = schedulerCoordinator.diagnostics()
            val tasks = taskRepository.observeTasks().first()
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No persisted tasks yet. Scheduler supports once, interval, and cron execution."
                    } else {
                        "Found ${tasks.size} persisted task(s)."
                    },
                payload =
                    buildJsonObject {
                        put("supportsOnce", true)
                        put("supportsInterval", true)
                        put("supportsCron", true)
                        put(
                            "minimumBackgroundIntervalMinutes",
                            schedulerCoordinator.capabilities().minimumBackgroundInterval.toMinutes(),
                        )
                        put("taskCount", tasks.size)
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(
                                        buildTaskPayload(
                                            task = task,
                                            latestRun = taskRepository.getLatestRun(task.id),
                                            sessionRepository = sessionRepository,
                                            diagnostics = diagnostics,
                                        ),
                                    )
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.get",
                    description = "Return a canonical task payload and its latest run summary.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.get",
                    summary = "tasks.get requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.get", taskId = taskId)
            ToolExecutionResult.success(
                summary = "Loaded task ${task.name}.",
                payload =
                    buildJsonObject {
                        put(
                            "task",
                            buildTaskPayload(
                                task = task,
                                latestRun = taskRepository.getLatestRun(task.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.search",
                    aliases = listOf("task.search"),
                    description = "Search persisted automations by name or prompt text.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Task name or prompt text to search for.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 20.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val query =
                arguments.optionalText("query")
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.search",
                        summary = "tasks.search requires a non-empty query.",
                        field = "query",
                    )
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_SEARCH_DEFAULT_LIMIT,
                )
            val tasks = taskRepository.searchTasks(query = query, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No tasks matched \"$query\"."
                    } else {
                        "Found ${tasks.size} task(s) matching \"$query\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put("resultCount", tasks.size)
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(task.toTaskSearchPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.runs",
                    aliases = listOf("task.runs", "tasks.history", "task.history"),
                    description = "Return recent run history for a scheduled automation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Task identifier",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.runs",
                    summary = "tasks.runs requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.runs", taskId = taskId)
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRuns(taskId = task.id, limit = limit)
            ToolExecutionResult.success(
                summary = "Loaded ${runs.size} recent run(s) for task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("taskName", task.name)
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(run.toTaskRunHistoryPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor = taskCreateDescriptor(),
        ) { context, arguments ->
            val spec =
                try {
                    parseTaskCreateSpec(
                        arguments = arguments,
                        context = context,
                        sessionRepository = sessionRepository,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = clock.instant(),
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.create",
                        summary = error.message ?: "tasks.create received invalid arguments.",
                    )
                }
            when (spec) {
                is TaskToolParseResult.Failure -> spec.result
                is TaskToolParseResult.Success -> {
                    val createdTask =
                        taskRepository.createTask(
                            name = spec.value.name,
                            prompt = spec.value.prompt,
                            schedule = spec.value.schedule,
                            executionMode = spec.value.executionMode,
                            targetSessionId = spec.value.targetSessionId,
                            precise = spec.value.precise,
                            maxRetries = spec.value.maxRetries,
                        )
                    schedulerCoordinator.scheduleTask(createdTask.id)
                    val reloadedTask = taskRepository.getTask(createdTask.id) ?: createdTask
                    ToolExecutionResult.success(
                        summary = "Created task ${reloadedTask.name}.",
                        payload =
                            buildJsonObject {
                                put(
                                    "task",
                                    buildTaskPayload(
                                        task = reloadedTask,
                                        latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                        sessionRepository = sessionRepository,
                                        diagnostics = schedulerCoordinator.diagnostics(),
                                    ),
                                )
                            },
                    )
                }
            }
        },
        ToolRegistry.Entry(
            descriptor = taskUpdateDescriptor(),
        ) { context, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.update",
                    summary = "tasks.update requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val existingTask =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.update", taskId = taskId)
            val updatedTask =
                try {
                    parseTaskUpdate(
                        existingTask = existingTask,
                        arguments = arguments,
                        context = context,
                        sessionRepository = sessionRepository,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = clock.instant(),
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.update",
                        summary = error.message ?: "tasks.update received invalid arguments.",
                    )
                }
            when (updatedTask) {
                is TaskToolParseResult.Failure -> updatedTask.result
                is TaskToolParseResult.Success -> {
                    taskRepository.updateTask(updatedTask.value)
                    if (updatedTask.value.enabled) {
                        schedulerCoordinator.scheduleTask(updatedTask.value.id)
                    } else {
                        schedulerCoordinator.cancelTask(updatedTask.value.id)
                    }
                    val reloadedTask = taskRepository.getTask(updatedTask.value.id) ?: updatedTask.value
                    ToolExecutionResult.success(
                        summary = "Updated task ${reloadedTask.name}.",
                        payload =
                            buildJsonObject {
                                put(
                                    "task",
                                    buildTaskPayload(
                                        task = reloadedTask,
                                        latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                        sessionRepository = sessionRepository,
                                        diagnostics = schedulerCoordinator.diagnostics(),
                                    ),
                                )
                            },
                    )
                }
            }
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.enable",
                    description = "Enable a task and reschedule its next work.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.enable",
                    summary = "tasks.enable requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.enable", taskId = taskId)
            val updatedTask =
                task.copy(
                    enabled = true,
                    updatedAt = clock.instant(),
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.scheduleTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Enabled task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put(
                            "task",
                            buildTaskPayload(
                                task = reloadedTask,
                                latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.disable",
                    description = "Disable a task and cancel its queued work.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.disable",
                    summary = "tasks.disable requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.disable", taskId = taskId)
            val updatedTask =
                task.copy(
                    enabled = false,
                    updatedAt = clock.instant(),
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.cancelTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Disabled task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put(
                            "task",
                            buildTaskPayload(
                                task = reloadedTask,
                                latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.delete",
                    description = "Delete a task and cancel any future work.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.delete",
                    summary = "tasks.delete requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.delete", taskId = taskId)
            schedulerCoordinator.cancelTask(task.id)
            taskRepository.deleteTask(task.id)
            ToolExecutionResult.success(
                summary = "Deleted task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("deletedTaskId", task.id)
                        put("deletedTaskName", task.name)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                taskToggleDescriptor(
                    name = "tasks.run_now",
                    description = "Queue immediate execution without changing the future schedule.",
                ),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run_now",
                    summary = "tasks.run_now requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.run_now", taskId = taskId)
            val queuedAt = clock.instant()
            schedulerCoordinator.runNow(task.id)
            val reloadedTask = taskRepository.getTask(task.id) ?: task
            ToolExecutionResult.success(
                summary = "Queued run now for ${task.name}.",
                payload =
                    buildJsonObject {
                        put("queuedAtIso", queuedAt.toString())
                        put("trigger", "manual")
                        put(
                            "task",
                            buildTaskPayload(
                                task = reloadedTask,
                                latestRun = taskRepository.getLatestRun(reloadedTask.id),
                                sessionRepository = sessionRepository,
                                diagnostics = schedulerCoordinator.diagnostics(),
                            ),
                        )
                    },
            )
        },
    )
}

private fun taskCreateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.create",
        description = "Create a scheduled automation using explicit schedule fields.",
        arguments = taskMutationArguments(requiredTaskId = false),
    )

private fun taskUpdateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.update",
        description = "Patch an existing task without replacing unspecified fields.",
        arguments = taskMutationArguments(requiredTaskId = true),
    )

private fun taskToggleDescriptor(
    name: String,
    description: String,
): ToolDescriptor =
    ToolDescriptor(
        name = name,
        description = description,
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "taskId",
                    required = true,
                    description = "Task identifier",
                ),
            ),
    )

private fun taskMutationArguments(requiredTaskId: Boolean): List<ToolArgumentSpec> =
    buildList {
        if (requiredTaskId) {
            add(
                ToolArgumentSpec(
                    name = "taskId",
                    required = true,
                    description = "Task identifier",
                ),
            )
        }
        add(
            ToolArgumentSpec(
                name = "name",
                required = !requiredTaskId,
                description = "Task name",
            ),
        )
        add(
            ToolArgumentSpec(
                name = "prompt",
                required = !requiredTaskId,
                description = "Prompt sent when the task runs",
            ),
        )
        add(
            ToolArgumentSpec(
                name = "scheduleKind",
                required = !requiredTaskId,
                description = "once | interval | cron",
            ),
        )
        add(ToolArgumentSpec(name = "atIso", description = "ISO-8601 timestamp for once schedules"))
        add(ToolArgumentSpec(name = "anchorAtIso", description = "ISO-8601 anchor for interval schedules"))
        add(ToolArgumentSpec(name = "repeatEveryMinutes", description = "Interval cadence in minutes"))
        add(ToolArgumentSpec(name = "cronExpression", description = "Cron expression for cron schedules"))
        add(ToolArgumentSpec(name = "timezone", description = "ZoneId for cron schedules"))
        add(ToolArgumentSpec(name = "executionMode", description = "MAIN_SESSION | ISOLATED_SESSION"))
        add(ToolArgumentSpec(name = "targetSessionId", description = "Persisted target session id"))
        add(ToolArgumentSpec(name = "targetSessionAlias", description = "main | current"))
        add(ToolArgumentSpec(name = "precise", description = "true | false"))
        add(ToolArgumentSpec(name = "maxRetries", description = "Non-negative retry count"))
    }

private const val COMPACT_SUMMARY_MAX_CHARS = 4_000
private const val MESSAGE_RECENT_DEFAULT_LIMIT = 20
private const val MESSAGE_SEARCH_DEFAULT_LIMIT = 20
private const val MESSAGE_SEARCH_SNIPPET_MAX_CHARS = 500
private const val SESSION_SEARCH_DEFAULT_LIMIT = 20
private const val TASK_RUN_HISTORY_DEFAULT_LIMIT = 10
private const val TASK_SEARCH_DEFAULT_LIMIT = 20
private const val TOOL_NOTIFICATION_CHANNEL_ID = "androidclaw.tools"

private fun String.toMessageSearchSnippet(): String =
    if (length <= MESSAGE_SEARCH_SNIPPET_MAX_CHARS) {
        this
    } else {
        take(MESSAGE_SEARCH_SNIPPET_MAX_CHARS)
    }

private fun TaskRun.toTaskRunHistoryPayload() =
    buildJsonObject {
        put("id", id)
        put("status", status.name)
        put("scheduledAtIso", scheduledAt.toString())
        put("startedAtIso", startedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("finishedAtIso", finishedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("resultSummary", resultSummary?.let(::JsonPrimitive) ?: JsonNull)
        put("errorCode", errorCode?.let(::JsonPrimitive) ?: JsonNull)
        put("errorMessage", errorMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("outputMessageId", outputMessageId?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun Task.toTaskSearchPayload(): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("promptSnippet", promptSnippet)
        put("promptLength", prompt.length)
        put("promptTruncated", promptSnippet.length < prompt.length)
    }
}

private fun TaskSchedule.toTaskSearchKind(): String =
    when (this) {
        is TaskSchedule.Once -> "once"
        is TaskSchedule.Interval -> "interval"
        is TaskSchedule.Cron -> "cron"
    }

private fun kotlinx.serialization.json.JsonObject.optionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
}

private fun kotlinx.serialization.json.JsonObject.optionalBoolean(
    field: String,
    defaultValue: Boolean = false,
): Boolean {
    val primitive = this[field] as? JsonPrimitive ?: return defaultValue
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> defaultValue
    }
}

private fun kotlinx.serialization.json.JsonObject.optionalInt(
    field: String,
    defaultValue: Int,
): Int = optionalText(field)?.toIntOrNull() ?: defaultValue

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
    val channel =
        NotificationChannel(
            TOOL_NOTIFICATION_CHANNEL_ID,
            "AndroidClaw tools",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications created by AndroidClaw tool executions."
        }
    notificationManager.createNotificationChannel(channel)
}
