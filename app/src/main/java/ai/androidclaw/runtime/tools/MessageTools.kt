package ai.androidclaw.runtime.tools

import ai.androidclaw.data.MAX_PROVIDER_TIMEOUT_SECONDS
import ai.androidclaw.data.MIN_PROVIDER_TIMEOUT_SECONDS
import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.firstProviderEndpointPolicyError
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.EventLogEntry
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import ai.androidclaw.data.model.Task
import ai.androidclaw.data.model.TaskRun
import ai.androidclaw.data.model.TaskRunStatus
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MESSAGE_CONTENT_MAX_CHARS
import ai.androidclaw.data.repository.MESSAGE_REFERENCE_ID_MAX_CHARS
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.MAX_SAFE_DURATION_MINUTES
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.scheduler.TaskExecutionMode
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.scheduler.precisionMode
import ai.androidclaw.runtime.scheduler.schedulingDecision
import ai.androidclaw.runtime.scheduler.userVisiblePreciseWarnings
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillConfigField
import ai.androidclaw.runtime.skills.SkillConfigurationSnapshot
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillImportResult
import ai.androidclaw.runtime.skills.SkillPackageImportEntry
import ai.androidclaw.runtime.skills.SkillResolutionState
import ai.androidclaw.runtime.skills.SkillSecretField
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

internal fun messageToolEntries(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    clock: Clock,
): List<ToolRegistry.Entry> =
    buildList {
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
                                    name = "messages.create",
                                    aliases =
                                        listOf(
                                            "message.create",
                                            "messages.add",
                                            "message.add",
                                            "messages.append",
                                            "message.append",
                                            "chat.message.create",
                                            "chat.message.add",
                                            "chat.append",
                                        ),
                                    description = "Append one chat message to a session transcript.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to append to. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "role",
                                                required = false,
                                                description = "Message role: user, assistant, tool_call, tool_result, or system.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "content",
                                                required = false,
                                                description = "Message content to append. The alias text is also accepted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "text",
                                                description = "Alias for content.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "toolCallId",
                                                description = "Optional tool call reference id.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "taskRunId",
                                                description = "Optional automation task-run reference id.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to append to.",
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
                            val role =
                                arguments.optionalMessageRole("role")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.create requires role=user, assistant, tool_call, tool_result, or system.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "role")
                                            },
                                    )
                            val content =
                                arguments.optionalRawText("content")
                                    ?: arguments.optionalRawText("text")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.create requires non-empty content.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "content")
                                            },
                                    )
                            val toolCallId = arguments.optionalMessageReferenceId("toolCallId")
                            val taskRunId = arguments.optionalMessageReferenceId("taskRunId")
                            val message =
                                messageRepository.addMessage(
                                    sessionId = session.id,
                                    role = role,
                                    content = content,
                                    toolCallId = toolCallId,
                                    taskRunId = taskRunId,
                                )
                            val contentSnippet = message.content.toMessageSearchSnippet()
                            ToolExecutionResult.success(
                                summary = "Added ${message.role.name} message to \"${session.title}\".",
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
                                        put("inputTruncated", content.length > message.content.length)
                                        put("createdAtIso", message.createdAt.toString())
                                        put("hasProviderMeta", message.providerMeta != null)
                                        put("toolCallId", message.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("taskRunId", message.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("messageCount", messageRepository.getMessageCount(session.id))
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
                                    name = "messages.copy",
                                    aliases =
                                        listOf(
                                            "message.copy",
                                            "messages.duplicate",
                                            "message.duplicate",
                                            "chat.message.copy",
                                        ),
                                    description = "Copy one chat message into a target session or duplicate it in place.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messageId",
                                                required = false,
                                                description = "Message identifier to copy. The alias id is also accepted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "id",
                                                description = "Alias for messageId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "targetSessionId",
                                                description = "Session id to copy into. Defaults to the active session, then the source session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Alias for targetSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "copyProviderMeta",
                                                description = "Set false to omit provider metadata. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "copyReferences",
                                                description = "Set false to omit toolCallId and taskRunId references. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val messageId =
                                arguments.optionalText("messageId")
                                    ?: arguments.optionalText("id")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.copy requires a non-empty messageId.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "messageId")
                                            },
                                    )
                            val sourceMessage =
                                messageRepository.getMessage(messageId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Message $messageId was not found.",
                                        errorCode = "MISSING_MESSAGE",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_MESSAGE")
                                                put("messageId", messageId)
                                            },
                                    )
                            val sourceSession =
                                sessionRepository.getSession(sourceMessage.sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session ${sourceMessage.sessionId} for message $messageId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("messageId", messageId)
                                                put("sessionId", sourceMessage.sessionId)
                                            },
                                    )
                            val targetSessionId =
                                arguments.optionalText("targetSessionId")
                                    ?: arguments.optionalText("sessionId")
                                    ?: context.sessionId
                                    ?: sourceMessage.sessionId
                            val targetSession =
                                sessionRepository.getSession(targetSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Target session $targetSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("messageId", sourceMessage.id)
                                                put("targetSessionId", targetSessionId)
                                            },
                                    )
                            val copyProviderMeta = arguments.optionalBoolean("copyProviderMeta", defaultValue = true)
                            val copyReferences = arguments.optionalBoolean("copyReferences", defaultValue = true)
                            val copiedMessage =
                                messageRepository.addMessage(
                                    sessionId = targetSession.id,
                                    role = sourceMessage.role,
                                    content = sourceMessage.content,
                                    providerMeta =
                                        if (copyProviderMeta) {
                                            sourceMessage.providerMeta
                                        } else {
                                            null
                                        },
                                    toolCallId =
                                        if (copyReferences) {
                                            sourceMessage.toolCallId
                                        } else {
                                            null
                                        },
                                    taskRunId =
                                        if (copyReferences) {
                                            sourceMessage.taskRunId
                                        } else {
                                            null
                                        },
                                )
                            val contentSnippet = copiedMessage.content.toMessageSearchSnippet()
                            ToolExecutionResult.success(
                                summary =
                                    if (sourceSession.id == targetSession.id) {
                                        "Duplicated ${copiedMessage.role.name} message in \"${targetSession.title}\"."
                                    } else {
                                        "Copied ${copiedMessage.role.name} message into \"${targetSession.title}\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sourceMessageId", sourceMessage.id)
                                        put("messageId", copiedMessage.id)
                                        put("copiedMessageId", copiedMessage.id)
                                        put("sourceSessionId", sourceSession.id)
                                        put("sourceSessionTitle", sourceSession.title)
                                        put("sourceSessionArchived", sourceSession.archived)
                                        put("targetSessionId", targetSession.id)
                                        put("targetSessionTitle", targetSession.title)
                                        put("targetSessionArchived", targetSession.archived)
                                        put("sameSession", sourceSession.id == targetSession.id)
                                        put("role", copiedMessage.role.name)
                                        put("contentSnippet", contentSnippet)
                                        put("contentLength", copiedMessage.content.length)
                                        put("contentTruncated", contentSnippet.length < copiedMessage.content.length)
                                        put("createdAtIso", copiedMessage.createdAt.toString())
                                        put("copyProviderMeta", copyProviderMeta)
                                        put("copiedProviderMeta", copiedMessage.providerMeta != null)
                                        put("copyReferences", copyReferences)
                                        put("toolCallId", copiedMessage.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("taskRunId", copiedMessage.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("targetMessageCount", messageRepository.getMessageCount(targetSession.id))
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.move",
                                    aliases =
                                        listOf(
                                            "message.move",
                                            "messages.transfer",
                                            "message.transfer",
                                            "chat.message.move",
                                        ),
                                    description = "Move one chat message into a different target session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messageId",
                                                required = false,
                                                description = "Message identifier to move. The alias id is also accepted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "id",
                                                description = "Alias for messageId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "targetSessionId",
                                                description = "Destination session id. Defaults to the active session when it differs from the source.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Alias for targetSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val messageId =
                                arguments.optionalText("messageId")
                                    ?: arguments.optionalText("id")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.move requires a non-empty messageId.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "messageId")
                                            },
                                    )
                            val sourceMessage =
                                messageRepository.getMessage(messageId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Message $messageId was not found.",
                                        errorCode = "MISSING_MESSAGE",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_MESSAGE")
                                                put("messageId", messageId)
                                            },
                                    )
                            val sourceSession =
                                sessionRepository.getSession(sourceMessage.sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session ${sourceMessage.sessionId} for message $messageId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("messageId", messageId)
                                                put("sessionId", sourceMessage.sessionId)
                                            },
                                    )
                            val targetSessionId =
                                arguments.optionalText("targetSessionId")
                                    ?: arguments.optionalText("sessionId")
                                    ?: context.sessionId
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.move requires a targetSessionId or different active session.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("messageId", sourceMessage.id)
                                                put("field", "targetSessionId")
                                            },
                                    )
                            if (targetSessionId == sourceSession.id) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "messages.move targetSessionId must differ from the source session.",
                                    errorCode = "INVALID_TARGET_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_TARGET_SESSION")
                                            put("messageId", sourceMessage.id)
                                            put("sourceSessionId", sourceSession.id)
                                            put("targetSessionId", targetSessionId)
                                        },
                                )
                            }
                            val targetSession =
                                sessionRepository.getSession(targetSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Target session $targetSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("messageId", sourceMessage.id)
                                                put("targetSessionId", targetSessionId)
                                            },
                                    )
                            if (arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm message move with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("messageId", sourceMessage.id)
                                            put("sourceSessionId", sourceSession.id)
                                            put("targetSessionId", targetSession.id)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val movedMessage =
                                messageRepository.addMessage(
                                    sessionId = targetSession.id,
                                    role = sourceMessage.role,
                                    content = sourceMessage.content,
                                    providerMeta = sourceMessage.providerMeta,
                                    toolCallId = sourceMessage.toolCallId,
                                    taskRunId = sourceMessage.taskRunId,
                                )
                            val deleted = messageRepository.deleteMessage(sourceMessage.id)
                            if (!deleted) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Message ${sourceMessage.id} was copied but the source could not be deleted.",
                                    errorCode = "MOVE_SOURCE_DELETE_FAILED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MOVE_SOURCE_DELETE_FAILED")
                                            put("sourceMessageId", sourceMessage.id)
                                            put("movedMessageId", movedMessage.id)
                                            put("targetSessionId", targetSession.id)
                                        },
                                )
                            }
                            val wasCompactionBoundary = sourceSession.compactedUntilMessageId == sourceMessage.id
                            if (wasCompactionBoundary) {
                                sessionRepository.clearCompactionBoundary(sourceSession.id)
                            }
                            val updatedSourceSession = sessionRepository.getSession(sourceSession.id) ?: sourceSession
                            val updatedTargetSession = sessionRepository.getSession(targetSession.id) ?: targetSession
                            val contentSnippet = movedMessage.content.toMessageSearchSnippet()
                            ToolExecutionResult.success(
                                summary = "Moved ${movedMessage.role.name} message into \"${updatedTargetSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sourceMessageId", sourceMessage.id)
                                        put("messageId", movedMessage.id)
                                        put("movedMessageId", movedMessage.id)
                                        put("sourceSessionId", updatedSourceSession.id)
                                        put("sourceSessionTitle", updatedSourceSession.title)
                                        put("sourceSessionArchived", updatedSourceSession.archived)
                                        put("targetSessionId", updatedTargetSession.id)
                                        put("targetSessionTitle", updatedTargetSession.title)
                                        put("targetSessionArchived", updatedTargetSession.archived)
                                        put("role", movedMessage.role.name)
                                        put("contentSnippet", contentSnippet)
                                        put("contentLength", movedMessage.content.length)
                                        put("contentTruncated", contentSnippet.length < movedMessage.content.length)
                                        put("createdAtIso", movedMessage.createdAt.toString())
                                        put("hasProviderMeta", movedMessage.providerMeta != null)
                                        put("toolCallId", movedMessage.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("taskRunId", movedMessage.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("sourceMessageDeleted", true)
                                        put("sourceMessageCount", messageRepository.getMessageCount(updatedSourceSession.id))
                                        put("targetMessageCount", messageRepository.getMessageCount(updatedTargetSession.id))
                                        put("wasCompactionBoundary", wasCompactionBoundary)
                                        put("sourceCompactionBoundaryCleared", wasCompactionBoundary && updatedSourceSession.compactedUntilMessageId == null)
                                        put("sourceCompactedUntilMessageId", updatedSourceSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("sourceSummaryPreserved", sourceSession.summaryText != null && updatedSourceSession.summaryText == sourceSession.summaryText)
                                        put("sourceSummaryLength", updatedSourceSession.summaryText?.length ?: 0)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.update",
                                    aliases =
                                        listOf(
                                            "message.update",
                                            "messages.edit",
                                            "message.edit",
                                            "chat.message.update",
                                            "chat.message.edit",
                                        ),
                                    description = "Replace one chat message's content while preserving its session and metadata.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messageId",
                                                required = false,
                                                description = "Message identifier to update. The alias id is also accepted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "id",
                                                description = "Alias for messageId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "content",
                                                required = false,
                                                description = "Replacement message content. The alias text is also accepted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "text",
                                                description = "Alias for content.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val messageId =
                                arguments.optionalText("messageId")
                                    ?: arguments.optionalText("id")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.update requires a non-empty messageId.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "messageId")
                                            },
                                    )
                            val replacementContent =
                                arguments.optionalRawText("content")
                                    ?: arguments.optionalRawText("text")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.update requires non-empty replacement content.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "content")
                                            },
                                    )
                            val message =
                                messageRepository.getMessage(messageId)
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
                            if (arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm message update with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("messageId", message.id)
                                            put("sessionId", session.id)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val updatedMessage =
                                messageRepository.updateMessageContent(
                                    messageId = message.id,
                                    content = replacementContent,
                                ) ?: return@Entry ToolExecutionResult.failure(
                                    summary = "Message ${message.id} was not updated.",
                                    errorCode = "MISSING_MESSAGE",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_MESSAGE")
                                            put("messageId", message.id)
                                            put("sessionId", session.id)
                                        },
                                )
                            val updatedSession = sessionRepository.getSession(session.id) ?: session
                            val previousContentSnippet = message.content.toMessageSearchSnippet()
                            val contentSnippet = updatedMessage.content.toMessageSearchSnippet()
                            val wasCompactionBoundary = session.compactedUntilMessageId == message.id
                            ToolExecutionResult.success(
                                summary = "Updated ${updatedMessage.role.name} message from \"${updatedSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("messageId", updatedMessage.id)
                                        put("sessionId", updatedSession.id)
                                        put("sessionTitle", updatedSession.title)
                                        put("sessionArchived", updatedSession.archived)
                                        put("role", updatedMessage.role.name)
                                        put("updated", true)
                                        put("contentChanged", updatedMessage.content != message.content)
                                        put("previousContentSnippet", previousContentSnippet)
                                        put("previousContentLength", message.content.length)
                                        put("contentSnippet", contentSnippet)
                                        put("contentLength", updatedMessage.content.length)
                                        put("contentTruncated", contentSnippet.length < updatedMessage.content.length)
                                        put("inputTruncated", replacementContent.length > updatedMessage.content.length)
                                        put("createdAtIso", updatedMessage.createdAt.toString())
                                        put("hasProviderMeta", updatedMessage.providerMeta != null)
                                        put("toolCallId", updatedMessage.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("taskRunId", updatedMessage.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("wasCompactionBoundary", wasCompactionBoundary)
                                        put("compactedUntilMessageId", updatedSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("summaryPreserved", session.summaryText != null && updatedSession.summaryText == session.summaryText)
                                        put("summaryLength", updatedSession.summaryText?.length ?: 0)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.delete",
                                    aliases =
                                        listOf(
                                            "message.delete",
                                            "messages.remove",
                                            "message.remove",
                                        ),
                                    description = "Delete one chat message by id while preserving the rest of the session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messageId",
                                                required = false,
                                                description = "Message identifier to delete. The alias id is also accepted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "id",
                                                description = "Alias for messageId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val messageId =
                                arguments.optionalText("messageId")
                                    ?: arguments.optionalText("id")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.delete requires a non-empty messageId.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "messageId")
                                            },
                                    )
                            val message =
                                messageRepository.getMessage(messageId)
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
                            if (arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm message deletion with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("messageId", message.id)
                                            put("sessionId", session.id)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val wasCompactionBoundary = session.compactedUntilMessageId == message.id
                            val deleted = messageRepository.deleteMessage(message.id)
                            if (!deleted) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Message ${message.id} was not deleted.",
                                    errorCode = "MISSING_MESSAGE",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_MESSAGE")
                                            put("messageId", message.id)
                                            put("sessionId", session.id)
                                        },
                                )
                            }
                            if (wasCompactionBoundary) {
                                sessionRepository.clearCompactionBoundary(session.id)
                            }
                            val updatedSession = sessionRepository.getSession(session.id) ?: session
                            val contentSnippet = message.content.toMessageSearchSnippet()
                            ToolExecutionResult.success(
                                summary = "Deleted ${message.role.name} message from \"${updatedSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("messageId", message.id)
                                        put("sessionId", updatedSession.id)
                                        put("sessionTitle", updatedSession.title)
                                        put("sessionArchived", updatedSession.archived)
                                        put("role", message.role.name)
                                        put("deleted", true)
                                        put("messageCount", messageRepository.getMessageCount(updatedSession.id))
                                        put("contentSnippet", contentSnippet)
                                        put("contentLength", message.content.length)
                                        put("contentTruncated", contentSnippet.length < message.content.length)
                                        put("createdAtIso", message.createdAt.toString())
                                        put("hasProviderMeta", message.providerMeta != null)
                                        put("toolCallId", message.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("taskRunId", message.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("previousCompactedUntilMessageId", session.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("wasCompactionBoundary", wasCompactionBoundary)
                                        put("compactionBoundaryCleared", wasCompactionBoundary && updatedSession.compactedUntilMessageId == null)
                                        put("compactedUntilMessageId", updatedSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("summaryPreserved", session.summaryText != null && updatedSession.summaryText == session.summaryText)
                                        put("summaryLength", updatedSession.summaryText?.length ?: 0)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.page",
                                    aliases =
                                        listOf(
                                            "message.page",
                                            "chat.page",
                                            "messages.transcript",
                                            "chat.transcript",
                                            "session.transcript",
                                        ),
                                    description = "Return a bounded chronological page of messages for a chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "direction",
                                                description = "start, recent, before, or after. Defaults to start.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "anchorMessageId",
                                                description = "Anchor message id required for before or after pages.",
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
                            val directionText = arguments.optionalText("direction")
                            val direction =
                                directionText?.toMessagePageDirectionOrNull()
                                    ?: if (directionText == null) {
                                        MessagePageDirection.Start
                                    } else {
                                        return@Entry ToolExecutionResult.failure(
                                            summary = "messages.page direction must be start, recent, before, or after.",
                                            errorCode = "INVALID_ARGUMENTS",
                                            payload =
                                                buildJsonObject {
                                                    put("errorCode", "INVALID_ARGUMENTS")
                                                    put("field", "direction")
                                                },
                                        )
                                    }
                            val anchorMessageId = arguments.optionalText("anchorMessageId")
                            val anchorMessage =
                                anchorMessageId
                                    ?.let { messageId ->
                                        messageRepository
                                            .getMessagesByIds(listOf(messageId))
                                            .get(messageId)
                                    }
                            if ((direction == MessagePageDirection.Before || direction == MessagePageDirection.After) && anchorMessageId == null) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "messages.page requires anchorMessageId for before or after pages.",
                                    errorCode = "INVALID_ARGUMENTS",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_ARGUMENTS")
                                            put("field", "anchorMessageId")
                                            put("direction", direction.payloadName)
                                        },
                                )
                            }
                            if (anchorMessageId != null && anchorMessage == null) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Message $anchorMessageId was not found.",
                                    errorCode = "MISSING_MESSAGE",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_MESSAGE")
                                            put("messageId", anchorMessageId)
                                        },
                                )
                            }
                            if (anchorMessage != null && anchorMessage.sessionId != session.id) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Anchor message $anchorMessageId does not belong to session ${session.id}.",
                                    errorCode = "INVALID_PAGE_ANCHOR",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_PAGE_ANCHOR")
                                            put("sessionId", session.id)
                                            put("anchorMessageId", anchorMessage.id)
                                            put("anchorSessionId", anchorMessage.sessionId)
                                        },
                                )
                            }
                            val limit = arguments.optionalInt("limit", MESSAGE_RECENT_DEFAULT_LIMIT)
                            val messages =
                                when (direction) {
                                    MessagePageDirection.Start ->
                                        messageRepository.getFirstMessages(
                                            sessionId = session.id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.Recent ->
                                        messageRepository.getRecentMessagesChronological(
                                            sessionId = session.id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.Before ->
                                        messageRepository.getMessagesBefore(
                                            sessionId = session.id,
                                            anchorMessageId = requireNotNull(anchorMessage).id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.After ->
                                        messageRepository.getMessagesAfter(
                                            sessionId = session.id,
                                            anchorMessageId = requireNotNull(anchorMessage).id,
                                            limit = limit,
                                        )
                                }
                            ToolExecutionResult.success(
                                summary = "Loaded ${messages.size} chronological message(s) from \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("archived", session.archived)
                                        put("direction", direction.payloadName)
                                        put("anchorMessageId", anchorMessage?.id?.let(::JsonPrimitive) ?: JsonNull)
                                        put("messageCount", messageRepository.getMessageCount(session.id))
                                        put("returnedCount", messages.size)
                                        put("chronological", true)
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(message.toMessagePagePayload())
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
                                    name = "messages.context",
                                    aliases = listOf("message.context", "chat.context", "messages.around", "message.around"),
                                    description = "Return a bounded chronological transcript window around one message id.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messageId",
                                                required = true,
                                                description = "Anchor message identifier.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "radius",
                                                description = "Default number of messages before and after the anchor. Defaults to 3.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "beforeLimit",
                                                description = "Optional number of messages before the anchor.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "afterLimit",
                                                description = "Optional number of messages after the anchor.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val messageId =
                                arguments.optionalText("messageId")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.context requires a non-empty messageId.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "messageId")
                                            },
                                    )
                            val radius = arguments.optionalInt("radius", MESSAGE_CONTEXT_DEFAULT_RADIUS)
                            val beforeLimit = arguments.optionalInt("beforeLimit", radius)
                            val afterLimit = arguments.optionalInt("afterLimit", radius)
                            val context =
                                messageRepository.getMessageContext(
                                    messageId = messageId,
                                    beforeLimit = beforeLimit,
                                    afterLimit = afterLimit,
                                ) ?: return@Entry ToolExecutionResult.failure(
                                    summary = "Message $messageId was not found.",
                                    errorCode = "MISSING_MESSAGE",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_MESSAGE")
                                            put("messageId", messageId)
                                        },
                                )
                            val session =
                                sessionRepository.getSession(context.anchor.sessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session ${context.anchor.sessionId} for message $messageId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("messageId", messageId)
                                                put("sessionId", context.anchor.sessionId)
                                            },
                                    )
                            val messages =
                                context.before.map { message -> message.toMessageContextPayload(relativePosition = "before") } +
                                    context.anchor.toMessageContextPayload(relativePosition = "anchor", anchor = true) +
                                    context.after.map { message -> message.toMessageContextPayload(relativePosition = "after") }
                            ToolExecutionResult.success(
                                summary = "Loaded ${messages.size} message(s) around ${context.anchor.role.name} message from \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("messageId", context.anchor.id)
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("sessionArchived", session.archived)
                                        put("messageCount", messageRepository.getMessageCount(session.id))
                                        put("beforeCount", context.before.size)
                                        put("afterCount", context.after.size)
                                        put("returnedCount", messages.size)
                                        put("chronological", true)
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(message)
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
                                    name = "messages.reference",
                                    aliases =
                                        listOf(
                                            "message.reference",
                                            "chat.reference",
                                            "messages.by_reference",
                                            "message.by_reference",
                                            "messages.refs",
                                        ),
                                    description = "Return bounded chat messages linked to one tool call id or automation task run id.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "toolCallId",
                                                description = "Tool call id to inspect. Mutually exclusive with taskRunId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "taskRunId",
                                                description = "Automation task run id to inspect. Mutually exclusive with toolCallId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum result count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val toolCallId = arguments.optionalMessageReferenceId("toolCallId")
                            val taskRunId = arguments.optionalMessageReferenceId("taskRunId")
                            if ((toolCallId == null) == (taskRunId == null)) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "messages.reference requires exactly one of toolCallId or taskRunId.",
                                    errorCode = "INVALID_ARGUMENTS",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_ARGUMENTS")
                                            put("fields", "toolCallId,taskRunId")
                                        },
                                )
                            }
                            val limit = arguments.optionalInt("limit", MESSAGE_RECENT_DEFAULT_LIMIT)
                            val referenceType = if (toolCallId != null) "toolCallId" else "taskRunId"
                            val referenceId = toolCallId ?: requireNotNull(taskRunId)
                            val messages =
                                if (toolCallId != null) {
                                    messageRepository.getMessagesByToolCallId(
                                        toolCallId = toolCallId,
                                        limit = limit,
                                    )
                                } else {
                                    messageRepository.getMessagesByTaskRunId(
                                        taskRunId = requireNotNull(taskRunId),
                                        limit = limit,
                                    )
                                }
                            val sessionsById = mutableMapOf<String, Session?>()
                            messages
                                .map { message -> message.sessionId }
                                .distinct()
                                .forEach { sessionId ->
                                    sessionsById[sessionId] = sessionRepository.getSession(sessionId)
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (messages.isEmpty()) {
                                        "No messages found for $referenceType $referenceId."
                                    } else {
                                        "Found ${messages.size} message(s) for $referenceType $referenceId."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("referenceType", referenceType)
                                        put("referenceId", referenceId)
                                        put("resultCount", messages.size)
                                        put("recentFirst", true)
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(message.toMessageReferencePayload(sessionsById[message.sessionId]))
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
                                    name = "messages.role",
                                    aliases =
                                        listOf(
                                            "message.role",
                                            "chat.role",
                                            "messages.by_role",
                                            "message.by_role",
                                            "chat.by_role",
                                        ),
                                    description = "Return recent messages with one role for the active or specified chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "role",
                                                description = "Message role: user, assistant, tool_call, tool_result, or system.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum result count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val role =
                                arguments.optionalMessageRole("role")
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "messages.role requires role=user, assistant, tool_call, tool_result, or system.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("field", "role")
                                            },
                                    )
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
                            val messages =
                                messageRepository.getRecentMessagesByRole(
                                    sessionId = sessionId,
                                    role = role,
                                    limit = limit,
                                )
                            ToolExecutionResult.success(
                                summary = "Loaded ${messages.size} recent ${role.name} message(s) for \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("archived", session.archived)
                                        put("role", role.name)
                                        put("messageCount", messageRepository.getMessageCount(sessionId))
                                        put("returnedCount", messages.size)
                                        put("recentFirst", true)
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(message.toMessageReferencePayload(session))
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
                                    name = "messages.stats",
                                    aliases = listOf("message.stats", "chat.stats"),
                                    description = "Return aggregate transcript statistics for the active or specified chat session.",
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
                            val stats = messageRepository.getMessageStats(sessionId)
                            ToolExecutionResult.success(
                                summary = "Loaded transcript stats for \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("archived", session.archived)
                                        put("messageCount", stats.totalMessageCount)
                                        put("contentCharCount", stats.totalContentCharCount)
                                        put(
                                            "oldestMessageAtIso",
                                            stats.oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                                        )
                                        put(
                                            "newestMessageAtIso",
                                            stats.newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                                        )
                                        put(
                                            "roleStats",
                                            buildJsonArray {
                                                stats.roleStats.forEach { roleStats ->
                                                    add(roleStats.toMessageRoleStatsPayload())
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
                                    name = "messages.handoff",
                                    aliases =
                                        listOf(
                                            "message.handoff",
                                            "messages.snapshot",
                                            "message.snapshot",
                                            "transcript.handoff",
                                            "transcript.snapshot",
                                        ),
                                    description = "Prepare a compact transcript handoff without full message bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "direction",
                                                description = "recent or start. Defaults to recent.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum message count. Defaults to 12, max 50.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeSnippets",
                                                description = "Set false to omit message snippets. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to hand off.",
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
                            val directionText = arguments.optionalText("direction")
                            val direction =
                                directionText?.toMessagePageDirectionOrNull()
                                    ?: if (directionText == null) {
                                        MessagePageDirection.Recent
                                    } else {
                                        return@Entry ToolExecutionResult.failure(
                                            summary = "messages.handoff direction must be recent or start.",
                                            errorCode = "INVALID_ARGUMENTS",
                                            payload =
                                                buildJsonObject {
                                                    put("errorCode", "INVALID_ARGUMENTS")
                                                    put("field", "direction")
                                                },
                                        )
                                    }
                            if (direction != MessagePageDirection.Recent && direction != MessagePageDirection.Start) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "messages.handoff direction must be recent or start.",
                                    errorCode = "INVALID_ARGUMENTS",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_ARGUMENTS")
                                            put("field", "direction")
                                            put("direction", direction.payloadName)
                                        },
                                )
                            }
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = MESSAGE_HANDOFF_DEFAULT_LIMIT,
                                    ).coerceIn(0, MESSAGE_HANDOFF_MAX_LIMIT)
                            val includeSnippets = arguments.optionalBoolean("includeSnippets", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val stats = messageRepository.getMessageStats(session.id)
                            val messages =
                                when (direction) {
                                    MessagePageDirection.Start ->
                                        messageRepository.getFirstMessages(
                                            sessionId = session.id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.Recent ->
                                        messageRepository.getRecentMessagesChronological(
                                            sessionId = session.id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.Before,
                                    MessagePageDirection.After,
                                    -> emptyList()
                                }
                            val handoffMarkdown =
                                if (includeMarkdown) {
                                    session.toMessageHandoffMarkdown(
                                        stats = stats,
                                        messages = messages,
                                        direction = direction,
                                        limit = limit,
                                        includeSnippets = includeSnippets,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (messages.isEmpty()) {
                                        "Prepared empty transcript handoff for \"${session.title}\"."
                                    } else {
                                        "Prepared transcript handoff with ${messages.size} message(s) for \"${session.title}\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("archived", session.archived)
                                        put("direction", direction.payloadName)
                                        put("limit", limit)
                                        put("messageCount", stats.totalMessageCount)
                                        put("contentCharCount", stats.totalContentCharCount)
                                        put("oldestMessageAtIso", stats.oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                        put("newestMessageAtIso", stats.newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                        put("returnedCount", messages.size)
                                        put("omittedMessageCount", (stats.totalMessageCount - messages.size.toLong()).coerceAtLeast(0))
                                        put("chronological", true)
                                        put("includeSnippets", includeSnippets)
                                        put("includeMarkdown", includeMarkdown)
                                        put("fullMessageBodiesIncluded", false)
                                        put("providerMetaIncluded", false)
                                        put(
                                            "roleStats",
                                            buildJsonArray {
                                                stats.roleStats.forEach { roleStats ->
                                                    add(roleStats.toMessageRoleStatsPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(message.toMessageHandoffPayload(includeSnippet = includeSnippets))
                                                }
                                            },
                                        )
                                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.export",
                                    aliases =
                                        listOf(
                                            "message.export",
                                            "transcript.export",
                                            "chat.export",
                                            "session.messages.export",
                                        ),
                                    description = "Export a bounded transcript window without provider metadata.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to export. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "direction",
                                                description = "start or recent. Defaults to start.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum message count. Defaults to 50, max 100.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeBodies",
                                                description = "Set false to omit message bodies. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeSummary",
                                                description = "Set false to omit summary text. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit exportMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to export.",
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
                            val directionText = arguments.optionalText("direction")
                            val direction =
                                directionText?.toMessagePageDirectionOrNull()
                                    ?: if (directionText == null) {
                                        MessagePageDirection.Start
                                    } else {
                                        return@Entry ToolExecutionResult.failure(
                                            summary = "messages.export direction must be start or recent.",
                                            errorCode = "INVALID_ARGUMENTS",
                                            payload =
                                                buildJsonObject {
                                                    put("errorCode", "INVALID_ARGUMENTS")
                                                    put("field", "direction")
                                                },
                                        )
                                    }
                            if (direction != MessagePageDirection.Start && direction != MessagePageDirection.Recent) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "messages.export direction must be start or recent.",
                                    errorCode = "INVALID_ARGUMENTS",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_ARGUMENTS")
                                            put("field", "direction")
                                            put("direction", direction.payloadName)
                                        },
                                )
                            }
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = MESSAGE_EXPORT_DEFAULT_LIMIT,
                                    ).coerceIn(0, MESSAGE_EXPORT_MAX_LIMIT)
                            val includeBodies = arguments.optionalBoolean("includeBodies", defaultValue = true)
                            val includeSummary = arguments.optionalBoolean("includeSummary", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val stats = messageRepository.getMessageStats(session.id)
                            val messages =
                                when (direction) {
                                    MessagePageDirection.Start ->
                                        messageRepository.getFirstMessages(
                                            sessionId = session.id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.Recent ->
                                        messageRepository.getRecentMessagesChronological(
                                            sessionId = session.id,
                                            limit = limit,
                                        )
                                    MessagePageDirection.Before,
                                    MessagePageDirection.After,
                                    -> emptyList()
                                }
                            val exportMarkdown =
                                if (includeMarkdown) {
                                    session.toMessageExportMarkdown(
                                        stats = stats,
                                        messages = messages,
                                        direction = direction,
                                        limit = limit,
                                        includeBodies = includeBodies,
                                        includeSummary = includeSummary,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (messages.isEmpty()) {
                                        "Prepared empty transcript export for \"${session.title}\"."
                                    } else {
                                        "Prepared transcript export with ${messages.size} message(s) for \"${session.title}\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("exportFormat", MESSAGE_EXPORT_FORMAT)
                                        put("exportVersion", MESSAGE_EXPORT_VERSION)
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("isMain", session.isMain)
                                        put("archived", session.archived)
                                        put("createdAtIso", session.createdAt.toString())
                                        put("updatedAtIso", session.updatedAt.toString())
                                        put("direction", direction.payloadName)
                                        put("limit", limit)
                                        put("messageCount", stats.totalMessageCount)
                                        put("exportedMessageCount", messages.size)
                                        put("omittedMessageCount", (stats.totalMessageCount - messages.size.toLong()).coerceAtLeast(0))
                                        put("contentCharCount", stats.totalContentCharCount)
                                        put("oldestMessageAtIso", stats.oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                        put("newestMessageAtIso", stats.newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                        put("messageBodiesIncluded", includeBodies)
                                        put("fullMessageBodiesIncluded", includeBodies)
                                        put("providerMetaIncluded", false)
                                        put("includeSummary", includeSummary)
                                        put("summaryText", if (includeSummary) session.summaryText?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
                                        put("summaryTextIncluded", includeSummary)
                                        put("summaryLength", session.summaryText?.length ?: 0)
                                        put("compacted", session.compactedUntilMessageId != null)
                                        put("compactedUntilMessageId", session.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("includeMarkdown", includeMarkdown)
                                        put(
                                            "roleStats",
                                            buildJsonArray {
                                                stats.roleStats.forEach { roleStats ->
                                                    add(roleStats.toMessageRoleStatsPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(message.toMessageExportPayload(includeBody = includeBodies))
                                                }
                                            },
                                        )
                                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "messages.import",
                                    aliases =
                                        listOf(
                                            "message.import",
                                            "transcript.import",
                                            "chat.import",
                                            "session.messages.import",
                                        ),
                                    description = "Import a bounded transcript export into a new or existing session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "messages",
                                                description = "Array of exported message objects, or pass export.messages.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "export",
                                                description = "Optional messages.export payload containing a messages array and summary metadata.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "targetSessionId",
                                                description = "Existing session id to append to. Defaults to creating a new normal session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Alias for targetSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "title",
                                                description = "Title for the new session when targetSessionId is omitted.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum messages to scan. Defaults to 50, max 100.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeBodies",
                                                description = "Set false to omit message bodies from the result payload. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "importSummary",
                                                description = "Set false to skip importing export summary text. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "replaceSummary",
                                                description = "Set true to replace an existing target summary when importing summary text. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "dryRun",
                                                description = "Set true to preview importable messages without writing. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must be CONFIRM unless dryRun=true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val dryRun = arguments.optionalBoolean("dryRun", defaultValue = false)
                            if (!dryRun && arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry missingMessageImportConfirmationResult()
                            }
                            val rawEntries =
                                when (val parsedEntries = arguments.messageImportEntries()) {
                                    is MessageImportEntriesParseResult.Failure -> return@Entry parsedEntries.result
                                    is MessageImportEntriesParseResult.Success -> parsedEntries.entries
                                }
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = MESSAGE_IMPORT_DEFAULT_LIMIT,
                                    ).coerceIn(0, MESSAGE_IMPORT_MAX_LIMIT)
                            val scannedEntries = rawEntries.take(limit)
                            val candidates = mutableListOf<MessageImportCandidate>()
                            val skipped = mutableListOf<MessageImportSkippedEntry>()
                            scannedEntries.forEachIndexed { sourceIndex, element ->
                                when (val parsedCandidate = element.toMessageImportCandidate(sourceIndex = sourceIndex)) {
                                    is MessageImportCandidateParseResult.Candidate -> candidates += parsedCandidate.candidate
                                    is MessageImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
                                }
                            }
                            val targetSessionId = arguments.optionalText("targetSessionId") ?: arguments.optionalText("sessionId")
                            val existingTargetSession =
                                targetSessionId?.let { requestedSessionId ->
                                    sessionRepository.getSession(requestedSessionId)
                                        ?: return@Entry ToolExecutionResult.failure(
                                            summary = "Session $requestedSessionId was not found.",
                                            errorCode = "MISSING_SESSION",
                                            payload =
                                                buildJsonObject {
                                                    put("errorCode", "MISSING_SESSION")
                                                    put("sessionId", requestedSessionId)
                                                },
                                        )
                                }
                            val importSource = arguments.messageImportSourceObject()
                            val importTitle =
                                arguments.optionalText("title")
                                    ?: importSource?.optionalText("sessionTitle")?.let { sourceTitle -> "$sourceTitle import" }
                                    ?: "Imported transcript"
                            val targetSessionBeforeCount = existingTargetSession?.let { session -> messageRepository.getMessageCount(session.id) } ?: 0
                            val includeBodies = arguments.optionalBoolean("includeBodies", defaultValue = true)
                            val importSummary = arguments.optionalBoolean("importSummary", defaultValue = true)
                            val replaceSummary = arguments.optionalBoolean("replaceSummary", defaultValue = false)
                            val sourceSummary = importSource?.optionalRawText("summaryText")
                            val targetSession =
                                if (dryRun) {
                                    existingTargetSession
                                } else {
                                    existingTargetSession ?: sessionRepository.createSession(importTitle)
                                }
                            val importedMessages =
                                if (dryRun || targetSession == null) {
                                    emptyList()
                                } else {
                                    candidates.map { candidate ->
                                        MessageImportedItem(
                                            candidate = candidate,
                                            message =
                                                messageRepository.addMessage(
                                                    sessionId = targetSession.id,
                                                    role = candidate.role,
                                                    content = candidate.content,
                                                    toolCallId = candidate.toolCallId,
                                                    taskRunId = candidate.taskRunId,
                                                ),
                                        )
                                    }
                                }
                            val targetSessionCreated = !dryRun && existingTargetSession == null && targetSession != null
                            val targetSummaryBefore = existingTargetSession?.summaryText
                            val summaryImportable = importSummary && !sourceSummary.isNullOrBlank()
                            val summaryImported =
                                if (!dryRun && targetSession != null && summaryImportable) {
                                    val canWriteSummary = targetSessionCreated || replaceSummary || targetSession.summaryText.isNullOrBlank()
                                    if (canWriteSummary) {
                                        sessionRepository.updateSummaryState(
                                            id = targetSession.id,
                                            summaryText = sourceSummary,
                                            compactedUntilMessageId = null,
                                        )
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            val targetSessionAfterCount = targetSession?.let { session -> messageRepository.getMessageCount(session.id) } ?: targetSessionBeforeCount
                            ToolExecutionResult.success(
                                summary =
                                    if (dryRun) {
                                        "Prepared dry-run transcript import with ${candidates.size} importable message(s)."
                                    } else {
                                        "Imported ${importedMessages.size} message(s) into \"${targetSession?.title ?: importTitle}\"; skipped ${skipped.size}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("importFormat", MESSAGE_IMPORT_FORMAT)
                                        put("importVersion", MESSAGE_IMPORT_VERSION)
                                        put("acceptedExportFormat", MESSAGE_EXPORT_FORMAT)
                                        put("acceptedExportVersion", MESSAGE_EXPORT_VERSION)
                                        put("messageLimit", limit)
                                        put("importLimit", limit)
                                        put("dryRun", dryRun)
                                        put("targetSessionId", targetSession?.id?.let(::JsonPrimitive) ?: JsonNull)
                                        put("targetSessionTitle", targetSession?.title ?: importTitle)
                                        put("targetSessionCreated", targetSessionCreated)
                                        put("targetSessionArchived", targetSession?.archived ?: existingTargetSession?.archived ?: false)
                                        put("targetMessageCountBefore", targetSessionBeforeCount)
                                        put("targetMessageCountAfter", targetSessionAfterCount)
                                        put("newMessageCountDelta", targetSessionAfterCount - targetSessionBeforeCount)
                                        put("receivedMessageCount", rawEntries.size)
                                        put("scannedMessageCount", scannedEntries.size)
                                        put("omittedInputMessageCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                                        put("importableMessageCount", candidates.size)
                                        put("importedMessageCount", importedMessages.size)
                                        put("skippedMessageCount", skipped.size)
                                        put("invalidMessageCount", skipped.count { entry -> entry.code.startsWith("messages.import.invalid") })
                                        put("messageBodiesIncluded", includeBodies)
                                        put("fullMessageBodiesIncluded", includeBodies)
                                        put("providerMetaImported", false)
                                        put("providerMetaIncluded", false)
                                        put("sourceCreatedAtPreserved", false)
                                        put("sourceMessageIdsPreserved", false)
                                        put("importSummary", importSummary)
                                        put("replaceSummary", replaceSummary)
                                        put("summaryImportable", summaryImportable)
                                        put("summaryImported", summaryImported)
                                        put("summaryTextIncluded", false)
                                        put("summaryLength", sourceSummary?.length ?: 0)
                                        put("targetHadSummaryBefore", !targetSummaryBefore.isNullOrBlank())
                                        put("compactionBoundaryImported", false)
                                        put(
                                            "candidateMessages",
                                            buildJsonArray {
                                                candidates.forEach { candidate ->
                                                    add(candidate.toMessageImportCandidatePayload(includeBody = includeBodies))
                                                }
                                            },
                                        )
                                        put(
                                            "importedMessages",
                                            buildJsonArray {
                                                importedMessages.forEach { importedItem ->
                                                    add(importedItem.toMessageImportedPayload(includeBody = includeBodies))
                                                }
                                            },
                                        )
                                        put(
                                            "skippedMessages",
                                            buildJsonArray {
                                                skipped.forEach { skippedEntry ->
                                                    add(skippedEntry.toMessageImportSkippedPayload())
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
                                    name = "messages.doctor",
                                    aliases =
                                        listOf(
                                            "message.doctor",
                                            "messages.health",
                                            "message.health",
                                            "messages.check",
                                            "message.check",
                                            "transcript.doctor",
                                            "transcript.health",
                                            "transcript.check",
                                        ),
                                    description = "Return actionable transcript diagnostics without message bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum recent message checks and diagnostic issues. Defaults to 20, max 50.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit doctorMarkdown. Defaults to true.",
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
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = MESSAGE_DOCTOR_DEFAULT_LIMIT,
                                    ).coerceIn(0, MESSAGE_DOCTOR_MAX_LIMIT)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val stats = messageRepository.getMessageStats(session.id)
                            val recentMessages =
                                messageRepository.getRecentMessagesChronological(
                                    sessionId = session.id,
                                    limit = limit,
                                )
                            val issues =
                                session.toMessageDoctorIssues(
                                    stats = stats,
                                    recentMessages = recentMessages,
                                )
                            val includedIssues = issues.take(limit)
                            val status = issues.toMessageDoctorStatus()
                            val doctorMarkdown =
                                if (includeMarkdown) {
                                    includedIssues.toMessageDoctorMarkdown(
                                        status = status,
                                        session = session,
                                        stats = stats,
                                        recentCheckCount = recentMessages.size,
                                        issueCount = issues.size,
                                        limit = limit,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    when {
                                        issues.isEmpty() ->
                                            "Message doctor found no issues across ${recentMessages.size} checked message(s)."
                                        includedIssues.size == issues.size ->
                                            "Message doctor found ${issues.size} issue(s) across ${recentMessages.size} checked message(s)."
                                        else ->
                                            "Message doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("status", status)
                                        put("sessionId", session.id)
                                        put("sessionTitle", session.title)
                                        put("archived", session.archived)
                                        put("messageCount", stats.totalMessageCount)
                                        put("contentCharCount", stats.totalContentCharCount)
                                        put("oldestMessageAtIso", stats.oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                        put("newestMessageAtIso", stats.newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                        put("limit", limit)
                                        put("recentCheckCount", recentMessages.size)
                                        put("recentChecksOmitted", (stats.totalMessageCount - recentMessages.size.toLong()).coerceAtLeast(0))
                                        put("issueCount", issues.size)
                                        put("includedIssueCount", includedIssues.size)
                                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                                        put("includeMarkdown", includeMarkdown)
                                        put("messageBodiesIncluded", false)
                                        put("providerMetaIncluded", false)
                                        put(
                                            "roleStats",
                                            buildJsonArray {
                                                stats.roleStats.forEach { roleStats ->
                                                    add(roleStats.toMessageRoleStatsPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "messageChecks",
                                            buildJsonArray {
                                                recentMessages.forEach { message ->
                                                    add(message.toMessageDoctorCheckPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "issues",
                                            buildJsonArray {
                                                includedIssues.forEach { issue ->
                                                    add(issue.toMessageDoctorPayload())
                                                }
                                            },
                                        )
                                        put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
    }
