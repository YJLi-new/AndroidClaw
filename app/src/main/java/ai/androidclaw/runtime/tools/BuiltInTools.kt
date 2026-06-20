package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MESSAGE_CONTENT_MAX_CHARS
import ai.androidclaw.data.repository.MESSAGE_REFERENCE_ID_MAX_CHARS
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.skills.SkillConfigurationSnapshot
import ai.androidclaw.runtime.skills.SkillImportResult
import ai.androidclaw.runtime.skills.SkillPackageImportEntry
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock

internal fun createBuiltInToolRegistry(
    application: Application,
    settingsDataStore: SettingsDataStore,
    sessionRepository: SessionRepository,
    taskRepository: TaskRepository,
    schedulerCoordinator: SchedulerCoordinator,
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
    skillEnabledUpdater: suspend (skillId: String, enabled: Boolean) -> Unit = { _, _ -> },
    skillInventoryRefresher: suspend (sessionId: String?, forceRefresh: Boolean) -> List<SkillSnapshot> = { _, _ ->
        bundledSkillsProvider()
    },
    skillConfigurationReader: suspend (SkillSnapshot) -> SkillConfigurationSnapshot = { skill ->
        skill.toDefaultConfigurationSnapshot()
    },
    skillConfigurationUpdater: suspend (SkillSnapshot, String, String?) -> SkillConfigurationSnapshot = { skill, configPath, value ->
        skill.toDefaultConfigurationSnapshot().withUpdatedConfigField(
            configPath = configPath,
            value = value,
        )
    },
    skillSecretClearer: suspend (SkillSnapshot, String) -> SkillConfigurationSnapshot = { skill, envName ->
        skill.toDefaultConfigurationSnapshot().withClearedSecretField(envName)
    },
    skillPackageImporter: suspend (List<SkillPackageImportEntry>, Boolean, Boolean) -> SkillImportResult = { entries, _, _ ->
        SkillImportResult(
            importedSkillNames = entries.map { entry -> entry.frontmatter.name },
            replacedSkillNames = emptyList(),
        )
    },
    providerSecretStore: ProviderSecretStore? = null,
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
                    addAll(
                        runtimeToolEntries(
                            settingsDataStore = settingsDataStore,
                            sessionRepository = sessionRepository,
                            taskRepository = taskRepository,
                            schedulerCoordinator = schedulerCoordinator,
                            bundledSkillsProvider = bundledSkillsProvider,
                            providerSecretStore = providerSecretStore,
                            memoryRepository = memoryRepository,
                            eventLogRepository = eventLogRepository,
                            toolRegistryProvider = { toolRegistry },
                            clock = clock,
                        ),
                    )
                    addAll(
                        providerToolEntries(
                            settingsDataStore = settingsDataStore,
                            providerSecretStore = providerSecretStore,
                            clock = clock,
                        ),
                    )
                    addAll(
                        toolDiscoveryEntries(
                            toolRegistryProvider = { toolRegistry },
                            clock = clock,
                        ),
                    )
                    eventLogRepository?.let { repository ->
                        addAll(
                            eventToolEntries(
                                eventLogRepository = repository,
                            ),
                        )
                    }
                    memoryRepository?.let { repository ->
                        addAll(
                            memoryToolEntries(
                                settingsDataStore = settingsDataStore,
                                memoryRepository = repository,
                                sessionRepository = sessionRepository,
                                messageRepository = messageRepository,
                            ),
                        )
                    }
                    addAll(
                        sessionToolEntries(
                            sessionRepository = sessionRepository,
                            messageRepository = messageRepository,
                            taskRepository = taskRepository,
                            schedulerCoordinator = schedulerCoordinator,
                            clock = clock,
                        ),
                    )
                    addAll(
                        messageToolEntries(
                            sessionRepository = sessionRepository,
                            messageRepository = messageRepository,
                            clock = clock,
                        ),
                    )
                    addAll(
                        skillToolEntries(
                            bundledSkillsProvider = bundledSkillsProvider,
                            skillEnabledUpdater = skillEnabledUpdater,
                            skillInventoryRefresher = skillInventoryRefresher,
                            skillConfigurationReader = skillConfigurationReader,
                            skillConfigurationUpdater = skillConfigurationUpdater,
                            skillSecretClearer = skillSecretClearer,
                            skillPackageImporter = skillPackageImporter,
                            clock = clock,
                        ),
                    )
                    addAll(notificationToolEntries(application = application))
                },
        )
    return toolRegistry
}

internal const val COMPACT_SUMMARY_MAX_CHARS = 4_000
internal const val MESSAGE_CONTEXT_DEFAULT_RADIUS = 3
internal const val MESSAGE_DOCTOR_DEFAULT_LIMIT = 20
internal const val MESSAGE_DOCTOR_MAX_LIMIT = 50
internal const val MESSAGE_DOCTOR_LARGE_TRANSCRIPT_CHARS = 100_000L
internal const val MESSAGE_DOCTOR_TEXT_MAX_CHARS = 500
internal const val MESSAGE_EXPORT_FORMAT = "androidclaw.messages.export.v1"
internal const val MESSAGE_EXPORT_VERSION = 1
internal const val MESSAGE_EXPORT_DEFAULT_LIMIT = 50
internal const val MESSAGE_EXPORT_MAX_LIMIT = 100
internal const val MESSAGE_HANDOFF_DEFAULT_LIMIT = 12
internal const val MESSAGE_HANDOFF_MAX_LIMIT = 50
internal const val MESSAGE_IMPORT_FORMAT = "androidclaw.messages.import.v1"
internal const val MESSAGE_IMPORT_VERSION = 1
internal const val MESSAGE_IMPORT_DEFAULT_LIMIT = 50
internal const val MESSAGE_IMPORT_MAX_LIMIT = 100
internal const val MEMORY_EXPORT_FORMAT = "androidclaw.memory.export.v1"
internal const val MEMORY_IMPORT_FORMAT = "androidclaw.memory.import.v1"
internal const val MESSAGE_RECENT_DEFAULT_LIMIT = 20
internal const val MESSAGE_SEARCH_DEFAULT_LIMIT = 20
internal const val MESSAGE_SEARCH_SNIPPET_MAX_CHARS = 500
internal const val SESSION_ACTIVITY_SNIPPET_MAX_CHARS = 300
internal const val SESSION_COMPARE_DEFAULT_RECENT_LIMIT = 3
internal const val SESSION_DOCTOR_CHECK_MAX_LIMIT = 20
internal const val SESSION_DOCTOR_DEFAULT_LIMIT = 20
internal const val SESSION_DOCTOR_LARGE_CONTENT_CHARS = 40_000L
internal const val SESSION_DOCTOR_LARGE_MESSAGE_COUNT = 100L
internal const val SESSION_DOCTOR_MAX_LIMIT = 50
internal const val SESSION_DOCTOR_TEXT_MAX_CHARS = 500
internal const val SESSION_EXPORT_FORMAT = "androidclaw.sessions.export.v1"
internal const val SESSION_EXPORT_VERSION = 1
internal const val SESSION_EXPORT_DEFAULT_LIMIT = 50
internal const val SESSION_EXPORT_MAX_LIMIT = 100
internal const val SESSION_HANDOFF_DEFAULT_RECENT_LIMIT = 8
internal const val SESSION_HANDOFF_MAX_RECENT_LIMIT = 20
internal const val SESSION_IMPORT_FORMAT = "androidclaw.sessions.import.v1"
internal const val SESSION_IMPORT_VERSION = 1
internal const val SESSION_IMPORT_DEFAULT_LIMIT = 50
internal const val SESSION_IMPORT_MAX_LIMIT = 100
internal const val SESSION_SEARCH_DEFAULT_LIMIT = 20
internal const val SESSION_SUMMARY_SNIPPET_MAX_CHARS = 500

internal enum class MessagePageDirection(
    val payloadName: String,
) {
    Start("start"),
    Recent("recent"),
    Before("before"),
    After("after"),
}

internal fun String.toMessageSearchSnippet(): String =
    if (length <= MESSAGE_SEARCH_SNIPPET_MAX_CHARS) {
        this
    } else {
        take(MESSAGE_SEARCH_SNIPPET_MAX_CHARS)
    }

internal fun Session.toMessageDoctorIssues(
    stats: MessageRepository.SessionMessageStats,
    recentMessages: List<ChatMessage>,
): List<MessageDoctorIssue> =
    buildList {
        fun addIssue(
            severity: String,
            code: String,
            summary: String,
            action: String,
            message: ChatMessage? = null,
            detail: String? = null,
        ) {
            add(
                MessageDoctorIssue(
                    id = "${message?.id ?: id}:$code",
                    severity = severity,
                    code = code,
                    sessionId = id,
                    messageId = message?.id,
                    role = message?.role?.name,
                    summary = summary.toMessageDoctorText(),
                    action = action.toMessageDoctorText(),
                    detail = detail?.toMessageDoctorText(),
                ),
            )
        }

        val roleCounts = stats.roleStats.associate { roleStats -> roleStats.role to roleStats.messageCount }
        if (archived) {
            addIssue(
                severity = "Warning",
                code = "messages.session.archived",
                summary = "Session $title is archived, so its transcript is hidden from normal active-session flows.",
                action = "Unarchive the session before continuing active work in this transcript.",
            )
        }
        if (stats.totalMessageCount == 0L) {
            addIssue(
                severity = "Warning",
                code = "messages.empty",
                summary = "Session $title has no persisted messages.",
                action = "Send or import messages before relying on transcript context.",
            )
        } else {
            if ((roleCounts[MessageRole.User] ?: 0L) == 0L) {
                addIssue(
                    severity = "Warning",
                    code = "messages.user.missing",
                    summary = "Transcript has messages but no user turns.",
                    action = "Add or import the user turn that anchors this conversation if the transcript should be replayable.",
                )
            }
            if ((roleCounts[MessageRole.User] ?: 0L) > 0L && (roleCounts[MessageRole.Assistant] ?: 0L) == 0L) {
                addIssue(
                    severity = "Warning",
                    code = "messages.assistant.missing",
                    summary = "Transcript has user turns but no assistant turns.",
                    action = "Run the provider or import assistant responses before treating the transcript as complete.",
                )
            }
            if ((roleCounts[MessageRole.ToolResult] ?: 0L) > 0L && (roleCounts[MessageRole.ToolCall] ?: 0L) == 0L) {
                addIssue(
                    severity = "Warning",
                    code = "messages.tool_calls.missing",
                    summary = "Transcript has tool results but no tool-call messages.",
                    action = "Import or repair matching tool-call records so provider replay can preserve tool context.",
                )
            }
            if (stats.totalContentCharCount >= MESSAGE_DOCTOR_LARGE_TRANSCRIPT_CHARS && summaryText.isNullOrBlank()) {
                addIssue(
                    severity = "Warning",
                    code = "messages.large_unsummarized",
                    summary = "Transcript has ${stats.totalContentCharCount} content characters without a session summary.",
                    action = "Run sessions.compact or sessions.summary.update before relying on long-session context.",
                )
            }
        }
        recentMessages.forEach { message ->
            if (message.content.isBlank()) {
                addIssue(
                    severity = "Error",
                    code = "message.content.blank",
                    summary = "A ${message.role.name} message has blank content.",
                    action = "Delete the blank message or replace it with meaningful content.",
                    message = message,
                )
            }
            if (message.content.length >= MESSAGE_CONTENT_MAX_CHARS) {
                addIssue(
                    severity = "Warning",
                    code = "message.content.max_length",
                    summary = "A ${message.role.name} message is at the $MESSAGE_CONTENT_MAX_CHARS character storage limit.",
                    action = "Review whether the message was truncated and summarize or split it if needed.",
                    message = message,
                )
            }
            if ((message.role == MessageRole.ToolCall || message.role == MessageRole.ToolResult) && message.toolCallId.isNullOrBlank()) {
                addIssue(
                    severity = "Error",
                    code = "message.tool_reference.missing",
                    summary = "${message.role.name} message is missing toolCallId.",
                    action = "Repair the toolCallId reference or remove the orphaned tool message before provider replay.",
                    message = message,
                )
            }
        }
    }

internal fun ChatMessage.toMessageDoctorCheckPayload(): JsonObject =
    buildJsonObject {
        put("messageId", id)
        put("role", role.name)
        put("createdAtIso", createdAt.toString())
        put("contentLength", content.length)
        put("contentAtStorageLimit", content.length >= MESSAGE_CONTENT_MAX_CHARS)
        put("hasProviderMeta", providerMeta != null)
        put("hasToolCallId", !toolCallId.isNullOrBlank())
        put("hasTaskRunId", !taskRunId.isNullOrBlank())
        put("messageBodyIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun List<MessageDoctorIssue>.toMessageDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun MessageDoctorIssue.toMessageDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("sessionId", sessionId)
        put("messageId", messageId?.let(::JsonPrimitive) ?: JsonNull)
        put("role", role?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", summary)
        put("action", action)
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun List<MessageDoctorIssue>.toMessageDoctorMarkdown(
    status: String,
    session: Session,
    stats: MessageRepository.SessionMessageStats,
    recentCheckCount: Int,
    issueCount: Int,
    limit: Int,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Message doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Session: `${session.title.toHandoffLine()}`")
        appendLine("- Session id: `${session.id}`")
        appendLine("- Archived: ${session.archived}")
        appendLine("- Messages: ${stats.totalMessageCount}")
        appendLine("- Content characters: ${stats.totalContentCharCount}")
        appendLine("- Recent messages checked: $recentCheckCount of up to $limit")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Message bodies included: false")
        appendLine("- Provider metadata included: false")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No message issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toMessageDoctorMarkdownLine())
            }
        }
    }
}

internal fun MessageDoctorIssue.toMessageDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(messageId?.toHandoffLine() ?: "session")
        append("` code=")
        append(code)
        role?.let { role ->
            append(" role=")
            append(role)
        }
        append(": ")
        append(summary.toHandoffLine())
        detail?.let { detail ->
            append(" detail=")
            append(detail.toHandoffLine())
        }
        append(" Action: ")
        append(action.toHandoffLine())
    }

internal fun String.toMessageDoctorText(): String = toHandoffLine().take(MESSAGE_DOCTOR_TEXT_MAX_CHARS)

internal fun ChatMessage.toMessageHandoffPayload(includeSnippet: Boolean): JsonObject {
    val contentSnippet = content.toMessageSearchSnippet()
    return buildJsonObject {
        put("messageId", id)
        put("role", role.name)
        put("createdAtIso", createdAt.toString())
        put("contentSnippet", if (includeSnippet) JsonPrimitive(contentSnippet) else JsonNull)
        put("contentLength", content.length)
        put("contentTruncated", contentSnippet.length < content.length)
        put("messageBodyIncluded", false)
        put("providerMetaIncluded", false)
        put("hasProviderMeta", providerMeta != null)
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun ChatMessage.toMessageExportPayload(includeBody: Boolean): JsonObject =
    buildJsonObject {
        put("messageId", id)
        put("sourceMessageId", id)
        put("role", role.name)
        put("createdAtIso", createdAt.toString())
        put("content", if (includeBody) JsonPrimitive(content) else JsonNull)
        put("contentLength", content.length)
        put("messageBodyIncluded", includeBody)
        put("fullMessageBodyIncluded", includeBody)
        put("providerMetaIncluded", false)
        put("hasProviderMeta", providerMeta != null)
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun JsonObject.messageImportSourceObject(): JsonObject? =
    (this["export"] as? JsonObject)
        ?: (this["payload"] as? JsonObject)
        ?: this

internal fun JsonObject.messageImportEntries(): MessageImportEntriesParseResult {
    val directEntries = this["messages"]
    val exportEntries = (this["export"] as? JsonObject)?.get("messages")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("messages")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return MessageImportEntriesParseResult.Failure(
            missingMessageImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(MessageImportEntriesParseResult::Success)
        ?: MessageImportEntriesParseResult.Failure(invalidMessageImportEntriesResult())
}

internal fun JsonElement.toMessageImportCandidate(sourceIndex: Int): MessageImportCandidateParseResult {
    val objectValue =
        this as? JsonObject ?: return messageImportSkipped(
            sourceIndex = sourceIndex,
            code = "messages.import.invalid_entry",
            summary = "Import entry must be a message object.",
        )
    val role =
        objectValue.optionalMessageRole("role")
            ?: return messageImportSkipped(
                sourceIndex = sourceIndex,
                code = "messages.import.invalid_role",
                summary = "Import entry skipped because role is missing or unsupported.",
            )
    val content =
        objectValue.optionalRawText("content")
            ?: objectValue.optionalRawText("text")
            ?: return messageImportSkipped(
                sourceIndex = sourceIndex,
                code = "messages.import.invalid_missing_content",
                summary = "Import entry skipped because content is missing or blank.",
            )
    return MessageImportCandidateParseResult.Candidate(
        MessageImportCandidate(
            sourceIndex = sourceIndex,
            role = role,
            content = content.take(MESSAGE_CONTENT_MAX_CHARS),
            sourceMessageId =
                objectValue.optionalMessageReferenceId("sourceMessageId")
                    ?: objectValue.optionalMessageReferenceId("messageId"),
            sourceCreatedAtIso =
                objectValue.optionalText("createdAtIso")
                    ?: objectValue.optionalText("createdAt"),
            toolCallId = objectValue.optionalMessageReferenceId("toolCallId"),
            taskRunId = objectValue.optionalMessageReferenceId("taskRunId"),
        ),
    )
}

internal fun messageImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): MessageImportCandidateParseResult.Skipped =
    MessageImportCandidateParseResult.Skipped(
        MessageImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

internal fun missingMessageImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import messages, or dryRun=true to preview without writing.",
        errorCode = "MISSING_MESSAGE_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MESSAGE_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

internal fun missingMessageImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide a messages array or an export object containing messages to import.",
        errorCode = "MISSING_MESSAGE_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MESSAGE_IMPORT_ENTRIES")
                put("field", "messages")
            },
    )

internal fun invalidMessageImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Message import entries must be an array.",
        errorCode = "INVALID_MESSAGE_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_MESSAGE_IMPORT_ENTRIES")
                put("field", "messages")
            },
    )

internal fun MessageImportCandidate.toMessageImportCandidatePayload(includeBody: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceMessageId", sourceMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("role", role.name)
        put("sourceCreatedAtIso", sourceCreatedAtIso?.let(::JsonPrimitive) ?: JsonNull)
        put("content", if (includeBody) JsonPrimitive(content) else JsonNull)
        put("contentLength", content.length)
        put("messageBodyIncluded", includeBody)
        put("fullMessageBodyIncluded", includeBody)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun MessageImportedItem.toMessageImportedPayload(includeBody: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", candidate.sourceIndex)
        put("sourceMessageId", candidate.sourceMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("newMessageId", message.id)
        put("sessionId", message.sessionId)
        put("role", message.role.name)
        put("sourceCreatedAtIso", candidate.sourceCreatedAtIso?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAtIso", message.createdAt.toString())
        put("content", if (includeBody) JsonPrimitive(message.content) else JsonNull)
        put("contentLength", message.content.length)
        put("messageBodyIncluded", includeBody)
        put("fullMessageBodyIncluded", includeBody)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
        put("hasProviderMeta", message.providerMeta != null)
        put("toolCallId", message.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", message.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun MessageImportSkippedEntry.toMessageImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
    }

internal fun Session.toMessageExportMarkdown(
    stats: MessageRepository.SessionMessageStats,
    messages: List<ChatMessage>,
    direction: MessagePageDirection,
    limit: Int,
    includeBodies: Boolean,
    includeSummary: Boolean,
): String =
    buildString {
        appendLine("# Transcript export: ${title.toHandoffLine()}")
        appendLine()
        appendLine("- Format: $MESSAGE_EXPORT_FORMAT")
        appendLine("- Version: $MESSAGE_EXPORT_VERSION")
        appendLine("- Session id: `$id`")
        appendLine("- Main session: $isMain")
        appendLine("- Archived: $archived")
        appendLine("- Direction: ${direction.payloadName}")
        appendLine("- Messages: ${stats.totalMessageCount}")
        appendLine("- Messages exported: ${messages.size} of up to $limit")
        appendLine("- Omitted messages: ${(stats.totalMessageCount - messages.size.toLong()).coerceAtLeast(0)}")
        appendLine("- Message bodies included: $includeBodies")
        appendLine("- Provider metadata included: false")
        appendLine("- Summary included: $includeSummary")
        appendLine("- Compacted until message: ${compactedUntilMessageId ?: "none"}")
        appendLine()
        appendLine("## Summary")
        if (includeSummary) {
            appendLine(summaryText?.toHandoffLine() ?: "_No summary stored._")
        } else {
            appendLine("_Summary omitted._")
        }
        appendLine()
        appendLine("## Messages")
        if (messages.isEmpty()) {
            appendLine("_No messages exported._")
        } else {
            messages.forEach { message ->
                append("- ")
                append(message.createdAt)
                append(" `")
                append(message.id.toHandoffLine())
                append("` ")
                append(message.role.name)
                append(": ")
                if (includeBodies) {
                    appendLine(message.content.toHandoffLine())
                } else {
                    appendLine("_Message body omitted._")
                }
            }
        }
    }

internal fun Session.toMessageHandoffMarkdown(
    stats: MessageRepository.SessionMessageStats,
    messages: List<ChatMessage>,
    direction: MessagePageDirection,
    limit: Int,
    includeSnippets: Boolean,
): String =
    buildString {
        appendLine("# Transcript handoff: ${title.toHandoffLine()}")
        appendLine()
        appendLine("- Session id: `$id`")
        appendLine("- Archived: $archived")
        appendLine("- Direction: ${direction.payloadName}")
        appendLine("- Messages: ${stats.totalMessageCount}")
        appendLine("- Content characters: ${stats.totalContentCharCount}")
        appendLine("- Messages included: ${messages.size} of up to $limit")
        appendLine("- Omitted messages: ${(stats.totalMessageCount - messages.size.toLong()).coerceAtLeast(0)}")
        appendLine("- Snippets included: $includeSnippets")
        appendLine("- Full message bodies included: false")
        appendLine("- Provider metadata included: false")
        appendLine()
        appendLine("## Role stats")
        if (stats.roleStats.isEmpty()) {
            appendLine("_No role stats available._")
        } else {
            stats.roleStats.forEach { roleStats ->
                append("- ")
                append(roleStats.role.name)
                append(": ")
                append(roleStats.messageCount)
                append(" message(s), ")
                append(roleStats.contentCharCount)
                appendLine(" character(s)")
            }
        }
        appendLine()
        appendLine("## Messages")
        if (messages.isEmpty()) {
            appendLine("_No messages included._")
        } else {
            messages.forEach { message ->
                append("- ")
                append(message.createdAt)
                append(" `")
                append(message.id.toHandoffLine())
                append("` ")
                append(message.role.name)
                append(": ")
                if (includeSnippets) {
                    appendLine(message.content.toMessageSearchSnippet().toHandoffLine())
                } else {
                    appendLine("_Snippet omitted._")
                }
            }
        }
    }

internal fun ChatMessage.toMessageContextPayload(
    relativePosition: String,
    anchor: Boolean = false,
): JsonObject {
    val contentSnippet = content.toMessageSearchSnippet()
    return buildJsonObject {
        put("messageId", id)
        put("relativePosition", relativePosition)
        put("anchor", anchor)
        put("role", role.name)
        put("contentSnippet", contentSnippet)
        put("contentLength", content.length)
        put("contentTruncated", contentSnippet.length < content.length)
        put("createdAtIso", createdAt.toString())
        put("hasProviderMeta", providerMeta != null)
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun ChatMessage.toMessageReferencePayload(session: Session?): JsonObject {
    val contentSnippet = content.toMessageSearchSnippet()
    return buildJsonObject {
        put("messageId", id)
        put("sessionId", sessionId)
        put("sessionTitle", session?.title?.let(::JsonPrimitive) ?: JsonNull)
        put("sessionArchived", session?.archived?.let(::JsonPrimitive) ?: JsonNull)
        put("sessionMissing", session == null)
        put("role", role.name)
        put("contentSnippet", contentSnippet)
        put("contentLength", content.length)
        put("contentTruncated", contentSnippet.length < content.length)
        put("createdAtIso", createdAt.toString())
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun ChatMessage.toMessagePagePayload(): JsonObject {
    val contentSnippet = content.toMessageSearchSnippet()
    return buildJsonObject {
        put("messageId", id)
        put("role", role.name)
        put("contentSnippet", contentSnippet)
        put("contentLength", content.length)
        put("contentTruncated", contentSnippet.length < content.length)
        put("createdAtIso", createdAt.toString())
        put("hasProviderMeta", providerMeta != null)
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun Session.toSessionHandoffMarkdown(
    messageCount: Int,
    recentMessages: List<ChatMessage>,
    recentLimit: Int,
    summarySnippet: String?,
): String =
    buildString {
        appendLine("# Session handoff: ${title.toHandoffLine()}")
        appendLine()
        appendLine("- Session id: `$id`")
        appendLine("- Main session: $isMain")
        appendLine("- Archived: $archived")
        appendLine("- Messages: $messageCount")
        appendLine("- Recent messages included: ${recentMessages.size} of up to $recentLimit")
        appendLine("- Created: $createdAt")
        appendLine("- Updated: $updatedAt")
        appendLine("- Compacted until message: ${compactedUntilMessageId ?: "none"}")
        appendLine()
        appendLine("## Summary")
        appendLine(summarySnippet?.ifBlank { "_Blank summary._" } ?: "_No summary included._")
        appendLine()
        appendLine("## Recent messages")
        if (recentMessages.isEmpty()) {
            appendLine("_No recent messages included._")
        } else {
            recentMessages.forEach { message ->
                append("- ")
                append(message.createdAt)
                append(" ")
                append(message.role.name)
                append(": ")
                appendLine(message.content.toMessageSearchSnippet().toHandoffLine())
            }
        }
    }

internal fun MessageRepository.RoleMessageStats.toMessageRoleStatsPayload(): JsonObject =
    buildJsonObject {
        put("role", role.name)
        put("messageCount", messageCount)
        put("contentCharCount", contentCharCount)
        put("oldestMessageAtIso", oldestMessageAt.toString())
        put("newestMessageAtIso", newestMessageAt.toString())
    }

internal fun MessageRepository.SessionMessageStats.toSessionMessageStatsPayload(): JsonObject =
    buildJsonObject {
        put("messageCount", totalMessageCount)
        put("contentCharCount", totalContentCharCount)
        put("oldestMessageAtIso", oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestMessageAtIso", newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put(
            "roleStats",
            buildJsonArray {
                roleStats.forEach { stats ->
                    add(stats.toMessageRoleStatsPayload())
                }
            },
        )
    }

internal fun Session.toSessionExportPayload(
    stats: MessageRepository.SessionMessageStats,
    includeSummary: Boolean,
): JsonObject =
    buildJsonObject {
        put("sessionId", id)
        put("sourceSessionId", id)
        put("title", title)
        put("isMain", isMain)
        put("archived", archived)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("summaryText", if (includeSummary) summaryText?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        put("summaryTextIncluded", includeSummary)
        put("summaryBodyIncluded", includeSummary)
        put("summaryLength", summaryText?.length ?: 0)
        put("summaryOmitted", !includeSummary && summaryText != null)
        put("compacted", compactedUntilMessageId != null)
        put("compactedUntilMessageId", compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("messageBodiesIncluded", false)
        put("fullMessageBodiesIncluded", false)
        put("providerMetaIncluded", false)
        put("messagesIncluded", false)
        put("messageStats", stats.toSessionMessageStatsPayload())
    }

internal fun List<Session>.toSessionExportMarkdown(
    requestedSessionId: String?,
    candidateSessionCount: Int,
    limit: Int,
    includeArchived: Boolean,
    includeSummaries: Boolean,
    messageStatsBySessionId: Map<String, MessageRepository.SessionMessageStats>,
): String {
    val exportedSessions = this
    return buildString {
        appendLine("# Session export")
        appendLine()
        appendLine("- Format: $SESSION_EXPORT_FORMAT")
        appendLine("- Version: $SESSION_EXPORT_VERSION")
        appendLine("- Requested session id: ${requestedSessionId?.toHandoffLine() ?: "none"}")
        appendLine("- Include archived: $includeArchived")
        appendLine("- Sessions exported: ${exportedSessions.size} of $candidateSessionCount candidate(s)")
        appendLine("- Limit: $limit")
        appendLine("- Summary text included: $includeSummaries")
        appendLine("- Message bodies included: false")
        appendLine("- Provider metadata included: false")
        appendLine()
        appendLine("## Sessions")
        if (exportedSessions.isEmpty()) {
            appendLine("_No sessions exported._")
        } else {
            exportedSessions.forEach { session ->
                val stats = messageStatsBySessionId.getValue(session.id)
                append("- `")
                append(session.title.toHandoffLine())
                append("` id=`")
                append(session.id)
                append("` main=")
                append(session.isMain)
                append(" archived=")
                append(session.archived)
                append(" messages=")
                append(stats.totalMessageCount)
                append(" summaryLength=")
                append(session.summaryText?.length ?: 0)
                append(" compacted=")
                append(session.compactedUntilMessageId != null)
                appendLine()
                if (includeSummaries && !session.summaryText.isNullOrBlank()) {
                    appendLine("  - Summary: ${session.summaryText.toHandoffLine()}")
                }
            }
        }
    }
}

internal fun JsonObject.sessionImportEntries(): SessionImportEntriesParseResult {
    val directEntries = this["sessions"]
    val exportEntries = (this["export"] as? JsonObject)?.get("sessions")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("sessions")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return SessionImportEntriesParseResult.Failure(
            missingSessionImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(SessionImportEntriesParseResult::Success)
        ?: SessionImportEntriesParseResult.Failure(invalidSessionImportEntriesResult())
}

internal fun JsonElement.toSessionImportCandidate(sourceIndex: Int): SessionImportCandidateParseResult {
    val objectValue =
        this as? JsonObject ?: return sessionImportSkipped(
            sourceIndex = sourceIndex,
            code = "sessions.import.invalid_entry",
            summary = "Import entry must be a session object.",
        )
    val title =
        objectValue.optionalText("title")
            ?: objectValue.optionalText("sessionTitle")
            ?: objectValue.optionalText("name")
            ?: return sessionImportSkipped(
                sourceIndex = sourceIndex,
                code = "sessions.import.invalid_title",
                summary = "Import entry skipped because title is missing or blank.",
            )
    val sourceCompactedUntilMessageId =
        objectValue.optionalMessageReferenceId("compactedUntilMessageId")
            ?: objectValue.optionalMessageReferenceId("sourceCompactedUntilMessageId")
    val messageStats = objectValue["messageStats"] as? JsonObject
    return SessionImportCandidateParseResult.Candidate(
        SessionImportCandidate(
            sourceIndex = sourceIndex,
            sourceSessionId =
                objectValue.optionalMessageReferenceId("sourceSessionId")
                    ?: objectValue.optionalMessageReferenceId("sessionId")
                    ?: objectValue.optionalMessageReferenceId("id"),
            title = title,
            sourceIsMain = objectValue.optionalBoolean("isMain", defaultValue = false),
            sourceArchived = objectValue.optionalBoolean("archived", defaultValue = false),
            summaryText =
                objectValue.optionalRawText("summaryText")
                    ?: objectValue.optionalRawText("summary"),
            sourceCompacted =
                objectValue.optionalBoolean(
                    field = "compacted",
                    defaultValue = sourceCompactedUntilMessageId != null,
                ),
            sourceCompactedUntilMessageId = sourceCompactedUntilMessageId,
            sourceCreatedAtIso =
                objectValue.optionalText("createdAtIso")
                    ?: objectValue.optionalText("createdAt"),
            sourceUpdatedAtIso =
                objectValue.optionalText("updatedAtIso")
                    ?: objectValue.optionalText("updatedAt"),
            sourceMessageCount =
                messageStats?.optionalText("messageCount")?.toLongOrNull()
                    ?: objectValue.optionalText("messageCount")?.toLongOrNull(),
        ),
    )
}

internal fun sessionImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): SessionImportCandidateParseResult.Skipped =
    SessionImportCandidateParseResult.Skipped(
        SessionImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

internal fun missingSessionImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import sessions, or dryRun=true to preview without writing.",
        errorCode = "MISSING_SESSION_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_SESSION_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

internal fun missingSessionImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide a sessions array or an export object containing sessions to import.",
        errorCode = "MISSING_SESSION_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_SESSION_IMPORT_ENTRIES")
                put("field", "sessions")
            },
    )

internal fun invalidSessionImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Session import entries must be an array.",
        errorCode = "INVALID_SESSION_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_SESSION_IMPORT_ENTRIES")
                put("field", "sessions")
            },
    )

internal fun SessionImportCandidate.toSessionImportCandidatePayload(includeSummary: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceSessionId", sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("title", title)
        put("sourceIsMain", sourceIsMain)
        put("sourceArchived", sourceArchived)
        put("sourceCreatedAtIso", sourceCreatedAtIso?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceUpdatedAtIso", sourceUpdatedAtIso?.let(::JsonPrimitive) ?: JsonNull)
        put("summaryText", if (includeSummary) summaryText?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        put("summaryTextIncluded", includeSummary)
        put("summaryLength", summaryText?.length ?: 0)
        put("sourceCompacted", sourceCompacted)
        put("sourceCompactedUntilMessageId", sourceCompactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceMessageCount", sourceMessageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("messageBodiesIncluded", false)
        put("providerMetaIncluded", false)
        put("sourceCreatedAtPreserved", false)
        put("sourceUpdatedAtPreserved", false)
        put("sourceMainPreserved", false)
        put("compactionBoundaryPreserved", false)
    }

internal fun SessionImportedItem.toSessionImportedPayload(includeSummary: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", candidate.sourceIndex)
        put("sourceSessionId", candidate.sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("newSessionId", session.id)
        put("sessionId", session.id)
        put("title", session.title)
        put("sourceTitle", candidate.title)
        put("sourceIsMain", candidate.sourceIsMain)
        put("importedAsMain", session.isMain)
        put("sourceArchived", candidate.sourceArchived)
        put("importedArchived", session.archived)
        put("archivedPreserved", archivedPreserved)
        put("sourceCreatedAtIso", candidate.sourceCreatedAtIso?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceUpdatedAtIso", candidate.sourceUpdatedAtIso?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAtIso", session.createdAt.toString())
        put("updatedAtIso", session.updatedAt.toString())
        put("summaryImported", summaryImported)
        put("summaryText", if (includeSummary) session.summaryText?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        put("summaryTextIncluded", includeSummary)
        put("summaryLength", session.summaryText?.length ?: 0)
        put("sourceCompacted", candidate.sourceCompacted)
        put("sourceCompactedUntilMessageId", candidate.sourceCompactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("compactedUntilMessageId", session.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("compactionBoundaryPreserved", false)
        put("sourceMessageCount", candidate.sourceMessageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("messageBodiesImported", false)
        put("messageBodiesIncluded", false)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
        put("sourceCreatedAtPreserved", false)
        put("sourceUpdatedAtPreserved", false)
        put("sourceMainPreserved", false)
    }

internal fun SessionImportSkippedEntry.toSessionImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
    }

internal fun Session.toSessionComparePayload(
    stats: MessageRepository.SessionMessageStats,
    recentMessages: List<ChatMessage>,
): JsonObject {
    val summarySnippet = summaryText?.take(SESSION_SUMMARY_SNIPPET_MAX_CHARS)
    return buildJsonObject {
        put("sessionId", id)
        put("title", title)
        put("isMain", isMain)
        put("archived", archived)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("messageCount", stats.totalMessageCount)
        put("contentCharCount", stats.totalContentCharCount)
        put("oldestMessageAtIso", stats.oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestMessageAtIso", stats.newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("summaryLength", summaryText?.length ?: 0)
        put("summarySnippet", summarySnippet?.let(::JsonPrimitive) ?: JsonNull)
        put("summaryTruncated", summaryText?.let { it.length > SESSION_SUMMARY_SNIPPET_MAX_CHARS } ?: false)
        put("compacted", compactedUntilMessageId != null)
        put("compactedUntilMessageId", compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "roleStats",
            buildJsonArray {
                stats.roleStats.forEach { roleStats ->
                    add(roleStats.toMessageRoleStatsPayload())
                }
            },
        )
        put(
            "recentMessages",
            buildJsonArray {
                recentMessages.forEach { message ->
                    add(message.toMessagePagePayload())
                }
            },
        )
    }
}

internal fun Session.toSessionSummaryPayload(): JsonObject {
    val summarySnippet = summaryText?.take(SESSION_SUMMARY_SNIPPET_MAX_CHARS)
    return buildJsonObject {
        put("sessionId", id)
        put("title", title)
        put("isMain", isMain)
        put("archived", archived)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("summaryLength", summaryText?.length ?: 0)
        put("summaryTruncated", summaryText?.let { it.length > SESSION_SUMMARY_SNIPPET_MAX_CHARS } ?: false)
        put("summarySnippet", summarySnippet?.let(::JsonPrimitive) ?: JsonNull)
        put("compactedUntilMessageId", compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("compacted", compactedUntilMessageId != null)
    }
}

internal fun SessionRepository.SessionActivity.toSessionActivityPayload(): JsonObject {
    val latestMessageSnippet = latestMessageContent?.take(SESSION_ACTIVITY_SNIPPET_MAX_CHARS)
    return buildJsonObject {
        put("sessionId", session.id)
        put("title", session.title)
        put("isMain", session.isMain)
        put("archived", session.archived)
        put("createdAtIso", session.createdAt.toString())
        put("updatedAtIso", session.updatedAt.toString())
        put("activityAtIso", (latestMessageCreatedAt ?: session.updatedAt).toString())
        put("messageCount", messageCount)
        put("hasSummary", session.summaryText != null)
        put("summaryLength", session.summaryText?.length ?: 0)
        put("compacted", session.compactedUntilMessageId != null)
        put("compactedUntilMessageId", session.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "latestMessage",
            latestMessageId?.let { messageId ->
                buildJsonObject {
                    put("messageId", messageId)
                    put("role", latestMessageRole?.name?.let(::JsonPrimitive) ?: JsonNull)
                    put("contentSnippet", latestMessageSnippet?.let(::JsonPrimitive) ?: JsonNull)
                    put("contentLength", latestMessageContent?.length ?: 0)
                    put("contentTruncated", latestMessageContent?.let { it.length > SESSION_ACTIVITY_SNIPPET_MAX_CHARS } ?: false)
                    put("createdAtIso", latestMessageCreatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                }
            } ?: JsonNull,
        )
    }
}

internal fun SessionRepository.SessionStats.toSessionStatsPayload(): JsonObject =
    buildJsonObject {
        put("sessionCount", totalSessionCount)
        put("totalSessionCount", totalSessionCount)
        put("activeSessionCount", activeSessionCount)
        put("archivedSessionCount", archivedSessionCount)
        put("mainSessionCount", mainSessionCount)
        put("summarizedSessionCount", summarizedSessionCount)
        put("compactedSessionCount", compactedSessionCount)
        put("oldestSessionCreatedAtIso", oldestSessionCreatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestSessionUpdatedAtIso", newestSessionUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestArchivedAtIso", newestArchivedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    }

internal fun List<Session>.findSessionByIdentifier(identifier: String): Session? =
    firstOrNull { session ->
        session.id.equals(identifier, ignoreCase = true) ||
            session.title.equals(identifier, ignoreCase = true)
    }

internal fun SessionRepository.SessionStats.toSessionDoctorGlobalIssues(): List<SessionDoctorIssue> =
    buildList {
        when {
            mainSessionCount == 0L ->
                add(
                    SessionDoctorIssue(
                        id = "sessions.main.missing",
                        severity = "Warning",
                        code = "sessions.main.missing",
                        sessionId = null,
                        title = null,
                        isMain = null,
                        archived = null,
                        summary = "No main session is currently persisted.",
                        action = "Open chat or create the main session before relying on main-session automations.",
                    ),
                )
            mainSessionCount > 1L ->
                add(
                    SessionDoctorIssue(
                        id = "sessions.main.duplicate",
                        severity = "Error",
                        code = "sessions.main.duplicate",
                        sessionId = null,
                        title = null,
                        isMain = null,
                        archived = null,
                        summary = "More than one main session is persisted.",
                        action = "Keep exactly one main session and archive or repair duplicates.",
                        detail = "mainSessionCount=$mainSessionCount",
                    ),
                )
        }
    }

internal fun Session.toSessionDoctorIssues(
    stats: MessageRepository.SessionMessageStats,
    boundaryMessage: ChatMessage?,
): List<SessionDoctorIssue> =
    buildList {
        fun addIssue(
            severity: String,
            code: String,
            summary: String,
            action: String,
            detail: String? = null,
        ) {
            add(
                SessionDoctorIssue(
                    id = "$id:$code",
                    severity = severity,
                    code = code,
                    sessionId = id,
                    title = title,
                    isMain = isMain,
                    archived = archived,
                    summary = summary.toSessionDoctorText(),
                    action = action.toSessionDoctorText(),
                    detail = detail?.toSessionDoctorText(),
                ),
            )
        }

        if (title.isBlank()) {
            addIssue(
                severity = "Warning",
                code = "session.title.blank",
                summary = "Session has a blank title.",
                action = "Run sessions.rename with a short descriptive title.",
            )
        }
        if (isMain && archived) {
            addIssue(
                severity = "Error",
                code = "session.main.archived",
                summary = "The main session is archived.",
                action = "Unarchive the main session or repair the persisted main-session row.",
            )
        }
        val boundaryMessageId = compactedUntilMessageId
        if (boundaryMessageId != null && summaryText.isNullOrBlank()) {
            addIssue(
                severity = "Error",
                code = "session.compaction.summary_missing",
                summary = "Session $title is compacted but has no stored summary text.",
                action = "Run sessions.summary.update to add a summary, or sessions.uncompact to expose older messages.",
                detail = "compactedUntilMessageId=$boundaryMessageId",
            )
        }
        if (boundaryMessageId != null) {
            when {
                boundaryMessage == null ->
                    addIssue(
                        severity = "Error",
                        code = "session.compaction.boundary_missing",
                        summary = "Session $title has a compaction boundary that does not reference an existing message.",
                        action = "Run sessions.uncompact or update the summary boundary after inspecting the transcript.",
                        detail = "compactedUntilMessageId=$boundaryMessageId",
                    )
                boundaryMessage.sessionId != id ->
                    addIssue(
                        severity = "Error",
                        code = "session.compaction.boundary_foreign",
                        summary = "Session $title compaction boundary points to a message from another session.",
                        action = "Run sessions.uncompact or compact this session again with an in-session boundary.",
                        detail = "compactedUntilMessageId=$boundaryMessageId boundarySessionId=${boundaryMessage.sessionId}",
                    )
            }
        }
        if (
            summaryText.isNullOrBlank() &&
            (
                stats.totalMessageCount >= SESSION_DOCTOR_LARGE_MESSAGE_COUNT ||
                    stats.totalContentCharCount >= SESSION_DOCTOR_LARGE_CONTENT_CHARS
            )
        ) {
            addIssue(
                severity = "Warning",
                code = "session.summary.missing_large",
                summary = "Session $title is large and has no lightweight summary.",
                action = "Run sessions.compact or sessions.summary.update before using it as long-lived context.",
                detail = "messageCount=${stats.totalMessageCount} contentCharCount=${stats.totalContentCharCount}",
            )
        }
    }

internal fun Session.toSessionDoctorCheckPayload(
    stats: MessageRepository.SessionMessageStats,
    boundaryMessage: ChatMessage?,
): JsonObject {
    val boundaryMessageId = compactedUntilMessageId
    val boundaryStatus =
        when {
            boundaryMessageId == null -> "None"
            boundaryMessage == null -> "Missing"
            boundaryMessage.sessionId != id -> "ForeignSession"
            else -> "Present"
        }
    return buildJsonObject {
        put("sessionId", id)
        put("title", title)
        put("isMain", isMain)
        put("archived", archived)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("messageCount", stats.totalMessageCount)
        put("contentCharCount", stats.totalContentCharCount)
        put("oldestMessageAtIso", stats.oldestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestMessageAtIso", stats.newestMessageAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("summaryLength", summaryText?.length ?: 0)
        put("compacted", boundaryMessageId != null)
        put("compactedUntilMessageId", boundaryMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("compactionBoundaryStatus", boundaryStatus)
        put("compactionBoundarySessionId", boundaryMessage?.sessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("transcriptBodiesOmitted", true)
        put("summaryBodyOmitted", true)
    }
}

internal fun List<SessionDoctorIssue>.toSessionDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun SessionDoctorIssue.toSessionDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("sessionId", sessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("title", title?.let(::JsonPrimitive) ?: JsonNull)
        put("isMain", isMain?.let(::JsonPrimitive) ?: JsonNull)
        put("archived", archived?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", summary)
        put("action", action)
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun List<SessionDoctorIssue>.toSessionDoctorMarkdown(
    status: String,
    totalSessionCount: Long,
    candidateSessionCount: Int,
    issueCount: Int,
    limit: Int,
    includeArchived: Boolean,
    requestedSessionId: String?,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Session doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Sessions in inventory: $totalSessionCount")
        appendLine("- Candidate sessions after filters: $candidateSessionCount")
        appendLine("- Requested session filter: ${requestedSessionId?.toHandoffLine() ?: "none"}")
        appendLine("- Archived sessions included: $includeArchived")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Limit: $limit")
        appendLine("- Transcript bodies omitted: true")
        appendLine("- Summary bodies omitted: true")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No session issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toSessionDoctorMarkdownLine())
            }
        }
    }
}

internal fun SessionDoctorIssue.toSessionDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(title?.toHandoffLine() ?: "runtime")
        append("`")
        sessionId?.let { id ->
            append(" id=`")
            append(id.toHandoffLine())
            append("`")
        }
        append(" code=")
        append(code)
        append(": ")
        append(summary.toHandoffLine())
        detail?.let { detail ->
            append(" detail=")
            append(detail.toHandoffLine())
        }
        append(" Action: ")
        append(action.toHandoffLine())
    }

internal fun String.toSessionDoctorText(): String = toHandoffLine().take(SESSION_DOCTOR_TEXT_MAX_CHARS)

internal fun JsonObject.optionalMessageReferenceId(field: String): String? =
    optionalText(field)
        ?.take(MESSAGE_REFERENCE_ID_MAX_CHARS)
        ?.ifBlank { null }

internal fun JsonObject.optionalEventSourceId(field: String): String? =
    optionalText(field)
        ?.take(EVENT_LOG_FILTER_MAX_CHARS)
        ?.ifBlank { null }

internal fun JsonObject.optionalMessageRole(field: String): MessageRole? =
    when (optionalText(field)?.lowercase()?.replace("-", "_")) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        "tool_call", "toolcall", "tool" -> MessageRole.ToolCall
        "tool_result", "toolresult" -> MessageRole.ToolResult
        "system" -> MessageRole.System
        else -> null
    }

internal fun String.toToolAvailabilityStatusOrNull(): ToolAvailabilityStatus? =
    when (lowercase().replace("-", "_").replace(" ", "_")) {
        "available" -> ToolAvailabilityStatus.Available
        "unavailable" -> ToolAvailabilityStatus.Unavailable
        "permission_required", "permissionrequired", "permission" -> ToolAvailabilityStatus.PermissionRequired
        "foreground_required", "foregroundrequired", "foreground" -> ToolAvailabilityStatus.ForegroundRequired
        "disabled_by_config", "disabledbyconfig", "disabled" -> ToolAvailabilityStatus.DisabledByConfig
        else -> null
    }

internal fun String.toMessagePageDirectionOrNull(): MessagePageDirection? =
    when (lowercase().replace("-", "_")) {
        "start", "first", "oldest", "from_start" -> MessagePageDirection.Start
        "recent", "latest", "last", "end" -> MessagePageDirection.Recent
        "before" -> MessagePageDirection.Before
        "after" -> MessagePageDirection.After
        else -> null
    }

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

internal fun ensureToolNotificationChannel(application: Application) {
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
