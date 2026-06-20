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

internal fun sessionToolEntries(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    taskRepository: TaskRepository,
    schedulerCoordinator: SchedulerCoordinator,
    clock: Clock,
): List<ToolRegistry.Entry> =
    buildList {
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
                                    name = "sessions.export",
                                    aliases =
                                        listOf(
                                            "session.export",
                                            "sessions.backup",
                                            "session.backup",
                                            "chat.sessions.export",
                                            "chat.session.export",
                                        ),
                                    description = "Export bounded session metadata, summaries, and message statistics without transcript bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Optional session id or title to export. Defaults to recent sessions.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeArchived",
                                                description = "Set false to omit archived sessions. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum session count. Defaults to 50, max 100.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeSummaries",
                                                description = "Set false to omit summary text. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit exportMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val requestedSessionId =
                                arguments.optionalText("sessionId")
                                    ?: arguments.optionalText("id")
                                    ?: arguments.optionalText("title")
                                    ?: arguments.optionalText("name")
                            val includeArchived = arguments.optionalBoolean("includeArchived", defaultValue = true)
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SESSION_EXPORT_DEFAULT_LIMIT,
                                    ).coerceIn(0, SESSION_EXPORT_MAX_LIMIT)
                            val includeSummaries = arguments.optionalBoolean("includeSummaries", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val activeSessions = sessionRepository.observeSessions().first()
                            val archivedSessions = sessionRepository.observeArchivedSessions().first()
                            val allSessions =
                                (activeSessions + archivedSessions)
                                    .distinctBy { session -> session.id }
                                    .sortedByDescending { session -> session.updatedAt }
                            val candidateSessions =
                                if (requestedSessionId != null) {
                                    listOf(
                                        allSessions.findSessionByIdentifier(requestedSessionId)
                                            ?: return@Entry ToolExecutionResult.failure(
                                                summary = "Session $requestedSessionId was not found.",
                                                errorCode = "MISSING_SESSION",
                                                payload =
                                                    buildJsonObject {
                                                        put("errorCode", "MISSING_SESSION")
                                                        put("toolName", "sessions.export")
                                                        put("sessionId", requestedSessionId)
                                                    },
                                            ),
                                    )
                                } else if (includeArchived) {
                                    allSessions
                                } else {
                                    activeSessions.sortedByDescending { session -> session.updatedAt }
                                }
                            val exportedSessions = candidateSessions.take(limit)
                            val messageStatsBySessionId =
                                exportedSessions.associate { session ->
                                    session.id to messageRepository.getMessageStats(session.id)
                                }
                            val sessionStats = sessionRepository.getSessionStats()
                            val exportMarkdown =
                                if (includeMarkdown) {
                                    exportedSessions.toSessionExportMarkdown(
                                        requestedSessionId = requestedSessionId,
                                        candidateSessionCount = candidateSessions.size,
                                        limit = limit,
                                        includeArchived = includeArchived,
                                        includeSummaries = includeSummaries,
                                        messageStatsBySessionId = messageStatsBySessionId,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (exportedSessions.isEmpty()) {
                                        "Prepared empty session metadata export."
                                    } else {
                                        "Prepared session metadata export with ${exportedSessions.size} session(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("exportFormat", SESSION_EXPORT_FORMAT)
                                        put("exportVersion", SESSION_EXPORT_VERSION)
                                        put("requestedSessionId", requestedSessionId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("includeArchived", includeArchived)
                                        put("limit", limit)
                                        put("candidateSessionCount", candidateSessions.size)
                                        put("exportedSessionCount", exportedSessions.size)
                                        put("omittedSessionCount", (candidateSessions.size - exportedSessions.size).coerceAtLeast(0))
                                        put("includeSummaries", includeSummaries)
                                        put("summaryTextIncluded", includeSummaries)
                                        put("summaryBodiesIncluded", includeSummaries)
                                        put("messageBodiesIncluded", false)
                                        put("fullMessageBodiesIncluded", false)
                                        put("providerMetaIncluded", false)
                                        put("messageStatsIncluded", true)
                                        put("compactionBoundaryIdsIncluded", true)
                                        put(
                                            "aggregateMessageCount",
                                            messageStatsBySessionId.values.sumOf { stats -> stats.totalMessageCount },
                                        )
                                        put(
                                            "aggregateContentCharCount",
                                            messageStatsBySessionId.values.sumOf { stats -> stats.totalContentCharCount },
                                        )
                                        put("stats", sessionStats.toSessionStatsPayload())
                                        put(
                                            "sessions",
                                            buildJsonArray {
                                                exportedSessions.forEach { session ->
                                                    add(
                                                        session.toSessionExportPayload(
                                                            stats = messageStatsBySessionId.getValue(session.id),
                                                            includeSummary = includeSummaries,
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        put("includeMarkdown", includeMarkdown)
                                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.import",
                                    aliases =
                                        listOf(
                                            "session.import",
                                            "sessions.restore",
                                            "session.restore",
                                            "chat.sessions.import",
                                            "chat.session.import",
                                        ),
                                    description = "Import bounded session metadata exported by sessions.export without transcript bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessions",
                                                description = "Array of exported session objects, or pass export.sessions.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "export",
                                                description = "Optional sessions.export payload containing a sessions array.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum sessions to scan. Defaults to 50, max 100.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeArchived",
                                                description = "Set false to skip source archived sessions. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "importSummaries",
                                                description = "Set false to skip importing summary text. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "preserveArchived",
                                                description = "Set false to import archived source sessions as active. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "dryRun",
                                                description = "Set true to preview importable sessions without writing. Defaults to false.",
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
                                return@Entry missingSessionImportConfirmationResult()
                            }
                            val rawEntries =
                                when (val parsedEntries = arguments.sessionImportEntries()) {
                                    is SessionImportEntriesParseResult.Failure -> return@Entry parsedEntries.result
                                    is SessionImportEntriesParseResult.Success -> parsedEntries.entries
                                }
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SESSION_IMPORT_DEFAULT_LIMIT,
                                    ).coerceIn(0, SESSION_IMPORT_MAX_LIMIT)
                            val includeArchived = arguments.optionalBoolean("includeArchived", defaultValue = true)
                            val importSummaries = arguments.optionalBoolean("importSummaries", defaultValue = true)
                            val preserveArchived = arguments.optionalBoolean("preserveArchived", defaultValue = true)
                            val scannedEntries = rawEntries.take(limit)
                            val candidates = mutableListOf<SessionImportCandidate>()
                            val skipped = mutableListOf<SessionImportSkippedEntry>()
                            scannedEntries.forEachIndexed { sourceIndex, element ->
                                when (val parsedCandidate = element.toSessionImportCandidate(sourceIndex = sourceIndex)) {
                                    is SessionImportCandidateParseResult.Candidate ->
                                        if (!includeArchived && parsedCandidate.candidate.sourceArchived) {
                                            skipped +=
                                                SessionImportSkippedEntry(
                                                    sourceIndex = sourceIndex,
                                                    code = "sessions.import.archived_skipped",
                                                    summary = "Source session skipped because includeArchived=false.",
                                                )
                                        } else {
                                            candidates += parsedCandidate.candidate
                                        }
                                    is SessionImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
                                }
                            }
                            val importedSessions =
                                if (dryRun) {
                                    emptyList()
                                } else {
                                    candidates.map { candidate ->
                                        val createdSession =
                                            sessionRepository.createSession(
                                                title = candidate.title,
                                                isMain = false,
                                            )
                                        val summaryImported =
                                            importSummaries &&
                                                !candidate.summaryText.isNullOrBlank()
                                        if (summaryImported) {
                                            sessionRepository.updateSummaryState(
                                                id = createdSession.id,
                                                summaryText = candidate.summaryText,
                                                compactedUntilMessageId = null,
                                            )
                                        }
                                        val archivedPreserved = preserveArchived && candidate.sourceArchived
                                        if (archivedPreserved) {
                                            sessionRepository.archiveSession(createdSession.id)
                                        }
                                        SessionImportedItem(
                                            candidate = candidate,
                                            session = sessionRepository.getSession(createdSession.id) ?: createdSession,
                                            summaryImported = summaryImported,
                                            archivedPreserved = archivedPreserved,
                                        )
                                    }
                                }
                            val statsAfter = sessionRepository.getSessionStats()
                            ToolExecutionResult.success(
                                summary =
                                    if (dryRun) {
                                        "Prepared dry-run session import with ${candidates.size} importable session(s)."
                                    } else {
                                        "Imported ${importedSessions.size} session metadata shell(s); skipped ${skipped.size}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("importFormat", SESSION_IMPORT_FORMAT)
                                        put("importVersion", SESSION_IMPORT_VERSION)
                                        put("acceptedExportFormat", SESSION_EXPORT_FORMAT)
                                        put("acceptedExportVersion", SESSION_EXPORT_VERSION)
                                        put("sessionLimit", limit)
                                        put("importLimit", limit)
                                        put("dryRun", dryRun)
                                        put("includeArchived", includeArchived)
                                        put("preserveArchived", preserveArchived)
                                        put("importSummaries", importSummaries)
                                        put("summaryTextIncluded", importSummaries)
                                        put("messageBodiesImported", false)
                                        put("messageBodiesIncluded", false)
                                        put("fullMessageBodiesIncluded", false)
                                        put("providerMetaImported", false)
                                        put("providerMetaIncluded", false)
                                        put("sourceCreatedAtPreserved", false)
                                        put("sourceUpdatedAtPreserved", false)
                                        put("sourceMainPreserved", false)
                                        put("compactionBoundaryPreserved", false)
                                        put("runContextPreserved", false)
                                        put("receivedSessionCount", rawEntries.size)
                                        put("scannedSessionCount", scannedEntries.size)
                                        put("omittedInputSessionCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                                        put("importableSessionCount", candidates.size)
                                        put("importedSessionCount", importedSessions.size)
                                        put("skippedSessionCount", skipped.size)
                                        put("invalidSessionCount", skipped.count { entry -> entry.code.startsWith("sessions.import.invalid") })
                                        put("archivedSessionSkippedCount", skipped.count { entry -> entry.code == "sessions.import.archived_skipped" })
                                        put("sourceMainSessionCount", candidates.count { candidate -> candidate.sourceIsMain })
                                        put("importedMainSessionCount", 0)
                                        put("summaryImportableCount", candidates.count { candidate -> !candidate.summaryText.isNullOrBlank() })
                                        put("summaryImportedCount", importedSessions.count { session -> session.summaryImported })
                                        put("archivedPreservedCount", importedSessions.count { session -> session.archivedPreserved })
                                        put("sessionCountAfter", statsAfter.totalSessionCount)
                                        put("activeSessionCountAfter", statsAfter.activeSessionCount)
                                        put("archivedSessionCountAfter", statsAfter.archivedSessionCount)
                                        put(
                                            "importableSessions",
                                            buildJsonArray {
                                                candidates.forEach { candidate ->
                                                    add(candidate.toSessionImportCandidatePayload(includeSummary = importSummaries))
                                                }
                                            },
                                        )
                                        put(
                                            "importedSessions",
                                            buildJsonArray {
                                                importedSessions.forEach { imported ->
                                                    add(imported.toSessionImportedPayload(includeSummary = importSummaries))
                                                }
                                            },
                                        )
                                        put(
                                            "skippedSessions",
                                            buildJsonArray {
                                                skipped.forEach { skippedEntry ->
                                                    add(skippedEntry.toSessionImportSkippedPayload())
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
                                    name = "sessions.delete",
                                    aliases =
                                        listOf(
                                            "session.delete",
                                            "sessions.remove",
                                            "session.remove",
                                            "sessions.destroy",
                                            "session.destroy",
                                        ),
                                    description = "Permanently delete a normal session and its transcript.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to delete. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to delete.",
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
                                    summary = "The main session cannot be deleted.",
                                    errorCode = "MAIN_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MAIN_SESSION")
                                            put("sessionId", sessionId)
                                        },
                                )
                            }
                            if (arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm session deletion with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("sessionId", sessionId)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val deletedMessageCount = messageRepository.getMessageCount(sessionId)
                            val deleted = sessionRepository.deleteSession(sessionId)
                            if (!deleted) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Session $sessionId was not deleted.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                            put("sessionId", sessionId)
                                        },
                                )
                            }
                            ToolExecutionResult.success(
                                summary = "Deleted session \"${existingSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", existingSession.id)
                                        put("title", existingSession.title)
                                        put("isMain", existingSession.isMain)
                                        put("archived", existingSession.archived)
                                        put("deleted", true)
                                        put("deletedMessageCount", deletedMessageCount)
                                        put("hadSummary", existingSession.summaryText != null)
                                        put("previousSummaryLength", existingSession.summaryText?.length ?: 0)
                                        put("previousCompacted", existingSession.compactedUntilMessageId != null)
                                        put("previousCompactedUntilMessageId", existingSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.clear",
                                    aliases =
                                        listOf(
                                            "session.clear",
                                            "sessions.messages.clear",
                                            "session.messages.clear",
                                            "messages.clear",
                                            "chat.clear",
                                        ),
                                    description = "Clear a session transcript while preserving the session row.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to clear. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "clearSummary",
                                                description = "Set true to also delete the stored summary. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to clear.",
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
                            if (arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm transcript clearing with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("sessionId", sessionId)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val clearSummary = arguments.optionalBoolean("clearSummary")
                            val previousMessageCount = messageRepository.getMessageCount(sessionId)
                            messageRepository.deleteSessionMessages(sessionId)
                            sessionRepository.updateSummaryState(
                                id = sessionId,
                                summaryText =
                                    if (clearSummary) {
                                        null
                                    } else {
                                        existingSession.summaryText
                                    },
                                compactedUntilMessageId = null,
                            )
                            val updatedSession = sessionRepository.getSession(sessionId) ?: existingSession
                            val remainingMessageCount = messageRepository.getMessageCount(sessionId)
                            ToolExecutionResult.success(
                                summary = "Cleared $previousMessageCount message(s) from session \"${updatedSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", updatedSession.id)
                                        put("title", updatedSession.title)
                                        put("isMain", updatedSession.isMain)
                                        put("archived", updatedSession.archived)
                                        put("deletedMessageCount", previousMessageCount)
                                        put("messageCount", remainingMessageCount)
                                        put("clearSummary", clearSummary)
                                        put("summaryPreserved", !clearSummary && updatedSession.summaryText != null)
                                        put("previousSummaryLength", existingSession.summaryText?.length ?: 0)
                                        put("summaryLength", updatedSession.summaryText?.length ?: 0)
                                        put("previousCompacted", existingSession.compactedUntilMessageId != null)
                                        put("previousCompactedUntilMessageId", existingSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("compacted", updatedSession.compactedUntilMessageId != null)
                                        put("compactedUntilMessageId", updatedSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
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
                                    name = "sessions.handoff",
                                    aliases =
                                        listOf(
                                            "session.handoff",
                                            "sessions.snapshot",
                                            "session.snapshot",
                                            "chat.handoff",
                                        ),
                                    description = "Return a compact session handoff with metadata, summary, and recent messages.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to inspect. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "recentLimit",
                                                description = "Recent chronological message count. Defaults to 8, max 20.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeSummary",
                                                description = "Set false to omit summary text. Defaults to true.",
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
                                    summary = "No active session is available for handoff.",
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
                            val recentLimit =
                                arguments
                                    .optionalInt(
                                        field = "recentLimit",
                                        defaultValue = SESSION_HANDOFF_DEFAULT_RECENT_LIMIT,
                                    ).coerceIn(0, SESSION_HANDOFF_MAX_RECENT_LIMIT)
                            val includeSummary = arguments.optionalBoolean("includeSummary", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val messageCount = messageRepository.getMessageCount(session.id)
                            val recentMessages =
                                messageRepository.getRecentMessagesChronological(
                                    sessionId = session.id,
                                    limit = recentLimit,
                                )
                            val summaryText = session.summaryText
                            val summarySnippet =
                                summaryText
                                    ?.take(SESSION_SUMMARY_SNIPPET_MAX_CHARS)
                                    ?.takeIf { includeSummary }
                            val summaryTruncated =
                                if (summarySnippet == null) {
                                    false
                                } else {
                                    summarySnippet.length < summaryText.orEmpty().length
                                }
                            val handoffMarkdown =
                                if (includeMarkdown) {
                                    session.toSessionHandoffMarkdown(
                                        messageCount = messageCount,
                                        recentMessages = recentMessages,
                                        recentLimit = recentLimit,
                                        summarySnippet = summarySnippet,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary = "Prepared handoff snapshot for \"${session.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sessionId", session.id)
                                        put("title", session.title)
                                        put("isMain", session.isMain)
                                        put("archived", session.archived)
                                        put("createdAtIso", session.createdAt.toString())
                                        put("updatedAtIso", session.updatedAt.toString())
                                        put("messageCount", messageCount)
                                        put("recentLimit", recentLimit)
                                        put("recentCount", recentMessages.size)
                                        put("summaryIncluded", includeSummary)
                                        put("summarySnippet", summarySnippet?.let(::JsonPrimitive) ?: JsonNull)
                                        put("summaryLength", summaryText?.length ?: 0)
                                        put("summaryTruncated", summaryTruncated)
                                        put("compacted", session.compactedUntilMessageId != null)
                                        put(
                                            "compactedUntilMessageId",
                                            session.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull,
                                        )
                                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                        put(
                                            "recentMessages",
                                            buildJsonArray {
                                                recentMessages.forEach { message ->
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
                                    name = "sessions.summaries",
                                    aliases =
                                        listOf(
                                            "session.summaries",
                                            "sessions.summarized",
                                            "session.summarized",
                                            "sessions.compacted",
                                            "session.compacted",
                                        ),
                                    description = "List sessions that carry summary or compaction boundary metadata.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "includeArchived",
                                                description = "Set true to include archived summarized sessions.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum result count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val includeArchived = arguments.optionalBoolean("includeArchived")
                            val limit = arguments.optionalInt("limit", SESSION_SEARCH_DEFAULT_LIMIT)
                            val sessions =
                                sessionRepository.listSummarizedSessions(
                                    limit = limit,
                                    includeArchived = includeArchived,
                                )
                            ToolExecutionResult.success(
                                summary =
                                    if (sessions.isEmpty()) {
                                        "No summarized sessions found."
                                    } else {
                                        "Found ${sessions.size} summarized session(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionCount", sessions.size)
                                        put("includeArchived", includeArchived)
                                        put(
                                            "sessions",
                                            buildJsonArray {
                                                sessions.forEach { session ->
                                                    add(session.toSessionSummaryPayload())
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
                                    name = "sessions.summary.update",
                                    aliases =
                                        listOf(
                                            "session.summary.update",
                                            "sessions.summary.set",
                                            "session.summary.set",
                                            "sessions.summary.clear",
                                            "session.summary.clear",
                                        ),
                                    description = "Set or clear lightweight summary metadata for one chat session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to update. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "summary",
                                                description = "Replacement summary text. Capped at 4000 characters.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "summaryText",
                                                description = "Alias for summary.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "clearSummary",
                                                description = "Set true to clear summary text and any compaction boundary.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Required as CONFIRM when clearing summary text.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available for summary update.",
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
                            val clearSummary =
                                arguments.optionalBoolean("clearSummary") ||
                                    context.requestedName.endsWith(".clear")
                            val requestedSummary =
                                arguments.optionalText("summary")
                                    ?: arguments.optionalText("summaryText")
                            if (clearSummary) {
                                if (arguments.optionalText("confirm") != "CONFIRM") {
                                    return@Entry ToolExecutionResult.failure(
                                        summary = "Confirm summary clearing with confirm=CONFIRM.",
                                        errorCode = "CONFIRMATION_REQUIRED",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "CONFIRMATION_REQUIRED")
                                                put("sessionId", sessionId)
                                                put("field", "confirm")
                                            },
                                    )
                                }
                                sessionRepository.updateSummaryState(
                                    id = sessionId,
                                    summaryText = null,
                                    compactedUntilMessageId = null,
                                )
                            } else {
                                if (requestedSummary.isNullOrBlank()) {
                                    return@Entry ToolExecutionResult.failure(
                                        summary = "sessions.summary.update requires summary text or clearSummary=true.",
                                        errorCode = "MISSING_SUMMARY",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SUMMARY")
                                                put("sessionId", sessionId)
                                                put("field", "summary")
                                            },
                                    )
                                }
                                sessionRepository.updateSummaryState(
                                    id = sessionId,
                                    summaryText = requestedSummary.take(COMPACT_SUMMARY_MAX_CHARS),
                                    compactedUntilMessageId = existingSession.compactedUntilMessageId,
                                )
                            }
                            val updatedSession = sessionRepository.getSession(sessionId) ?: existingSession
                            ToolExecutionResult.success(
                                summary =
                                    if (clearSummary) {
                                        "Cleared summary metadata for session \"${updatedSession.title}\"."
                                    } else {
                                        "Updated summary metadata for session \"${updatedSession.title}\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionId", updatedSession.id)
                                        put("title", updatedSession.title)
                                        put("archived", updatedSession.archived)
                                        put("clearSummary", clearSummary)
                                        put("summaryCleared", updatedSession.summaryText == null)
                                        put("previousSummaryLength", existingSession.summaryText?.length ?: 0)
                                        put("summaryLength", updatedSession.summaryText?.length ?: 0)
                                        put("summaryTruncated", requestedSummary?.let { it.length > COMPACT_SUMMARY_MAX_CHARS } ?: false)
                                        put("summaryText", updatedSession.summaryText?.let(::JsonPrimitive) ?: JsonNull)
                                        put("previousCompactedUntilMessageId", existingSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("compactedUntilMessageId", updatedSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("compacted", updatedSession.compactedUntilMessageId != null)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.activity",
                                    aliases =
                                        listOf(
                                            "session.activity",
                                            "sessions.timeline",
                                            "session.timeline",
                                            "sessions.recent",
                                            "session.recent",
                                        ),
                                    description = "List recently active sessions with message counts and latest-message snippets.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "includeArchived",
                                                description = "Set true to include archived sessions.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum result count. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val includeArchived = arguments.optionalBoolean("includeArchived")
                            val limit = arguments.optionalInt("limit", SESSION_SEARCH_DEFAULT_LIMIT)
                            val activity =
                                sessionRepository.listSessionActivity(
                                    limit = limit,
                                    includeArchived = includeArchived,
                                )
                            ToolExecutionResult.success(
                                summary =
                                    if (activity.isEmpty()) {
                                        "No sessions found."
                                    } else {
                                        "Loaded activity for ${activity.size} session(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionCount", activity.size)
                                        put("includeArchived", includeArchived)
                                        put(
                                            "sessions",
                                            buildJsonArray {
                                                activity.forEach { item ->
                                                    add(item.toSessionActivityPayload())
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
                                    name = "sessions.stats",
                                    aliases = listOf("session.stats", "chat.sessions.stats"),
                                    description = "Return aggregate chat-session statistics without loading transcripts.",
                                ),
                        ) { _, _ ->
                            val stats = sessionRepository.getSessionStats()
                            ToolExecutionResult.success(
                                summary = "Loaded stats for ${stats.totalSessionCount} session(s).",
                                payload = stats.toSessionStatsPayload(),
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.doctor",
                                    aliases =
                                        listOf(
                                            "session.doctor",
                                            "sessions.check",
                                            "session.check",
                                            "chat.doctor",
                                            "chat.check",
                                        ),
                                    description = "Return actionable session diagnostics without transcript bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Optional session id or title to inspect. Defaults to all sessions.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeArchived",
                                                description = "Set false to omit archived sessions when no sessionId is provided. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum diagnostic issues to include. Defaults to 20.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val stats = sessionRepository.getSessionStats()
                            val includeArchived = arguments.optionalBoolean("includeArchived", defaultValue = true)
                            val identifier =
                                arguments.optionalText("sessionId")
                                    ?: arguments.optionalText("id")
                                    ?: arguments.optionalText("title")
                                    ?: arguments.optionalText("name")
                            val activeSessions = sessionRepository.observeSessions().first()
                            val archivedSessions = sessionRepository.observeArchivedSessions().first()
                            val allSessions =
                                (activeSessions + archivedSessions)
                                    .distinctBy { session -> session.id }
                                    .sortedByDescending { session -> session.updatedAt }
                            val candidates =
                                if (identifier == null) {
                                    if (includeArchived) {
                                        allSessions
                                    } else {
                                        activeSessions
                                    }
                                } else {
                                    listOf(
                                        allSessions.findSessionByIdentifier(identifier)
                                            ?: return@Entry ToolExecutionResult.failure(
                                                summary = "Session $identifier was not found.",
                                                errorCode = "MISSING_SESSION",
                                                payload =
                                                    buildJsonObject {
                                                        put("errorCode", "MISSING_SESSION")
                                                        put("toolName", "sessions.doctor")
                                                        put("sessionId", identifier)
                                                    },
                                            ),
                                    )
                                }
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SESSION_DOCTOR_DEFAULT_LIMIT,
                                    ).coerceIn(0, SESSION_DOCTOR_MAX_LIMIT)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val boundaryMessages =
                                messageRepository.getMessagesByIds(
                                    candidates.mapNotNull { session -> session.compactedUntilMessageId },
                                )
                            val sessionMessageStats =
                                candidates.associate { session ->
                                    session.id to messageRepository.getMessageStats(session.id)
                                }
                            val issues =
                                stats.toSessionDoctorGlobalIssues() +
                                    candidates.flatMap { session ->
                                        session.toSessionDoctorIssues(
                                            stats = sessionMessageStats.getValue(session.id),
                                            boundaryMessage = session.compactedUntilMessageId?.let { messageId -> boundaryMessages[messageId] },
                                        )
                                    }
                            val includedIssues = issues.take(limit)
                            val status = issues.toSessionDoctorStatus()
                            val includedChecks = candidates.take(SESSION_DOCTOR_CHECK_MAX_LIMIT)
                            val doctorMarkdown =
                                if (includeMarkdown) {
                                    includedIssues.toSessionDoctorMarkdown(
                                        status = status,
                                        totalSessionCount = stats.totalSessionCount,
                                        candidateSessionCount = candidates.size,
                                        issueCount = issues.size,
                                        limit = limit,
                                        includeArchived = includeArchived,
                                        requestedSessionId = identifier,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    when {
                                        issues.isEmpty() ->
                                            "Session doctor found no issues across ${candidates.size} candidate session(s)."
                                        includedIssues.size == issues.size ->
                                            "Session doctor found ${issues.size} issue(s) across ${candidates.size} candidate session(s)."
                                        else ->
                                            "Session doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("status", status)
                                        put("sessionCount", stats.totalSessionCount)
                                        put("candidateSessionCount", candidates.size)
                                        put("sessionCheckCount", includedChecks.size)
                                        put("sessionChecksOmitted", (candidates.size - includedChecks.size).coerceAtLeast(0))
                                        put("requestedSessionId", identifier?.let(::JsonPrimitive) ?: JsonNull)
                                        put("includeArchived", includeArchived)
                                        put("transcriptBodiesOmitted", true)
                                        put("summaryBodiesOmitted", true)
                                        put("issueCount", issues.size)
                                        put("includedIssueCount", includedIssues.size)
                                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                                        put("limit", limit)
                                        put("includeMarkdown", includeMarkdown)
                                        put("stats", stats.toSessionStatsPayload())
                                        put(
                                            "sessionChecks",
                                            buildJsonArray {
                                                includedChecks.forEach { session ->
                                                    add(
                                                        session.toSessionDoctorCheckPayload(
                                                            stats = sessionMessageStats.getValue(session.id),
                                                            boundaryMessage =
                                                                session.compactedUntilMessageId
                                                                    ?.let { messageId -> boundaryMessages[messageId] },
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        put(
                                            "issues",
                                            buildJsonArray {
                                                includedIssues.forEach { issue ->
                                                    add(issue.toSessionDoctorPayload())
                                                }
                                            },
                                        )
                                        put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
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
                                    name = "sessions.fork",
                                    aliases =
                                        listOf(
                                            "session.fork",
                                            "sessions.duplicate",
                                            "session.duplicate",
                                            "sessions.copy",
                                            "session.copy",
                                        ),
                                    description = "Fork an existing chat session into a new active session with copied messages.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to fork. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "title",
                                                description = "Title for the fork. Defaults to the source title plus \" fork\".",
                                            ),
                                            ToolArgumentSpec(
                                                name = "copyMessages",
                                                description = "Set false to create an empty fork. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "copySummary",
                                                description = "Set false to skip summary and compaction metadata. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sourceSessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sourceSessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to fork.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                        },
                                )
                            }
                            val sourceSession =
                                sessionRepository.getSession(sourceSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Session $sourceSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sourceSessionId)
                                            },
                                    )
                            val forkTitle = arguments.optionalText("title") ?: "${sourceSession.title} fork"
                            val copyMessages = arguments.optionalBoolean("copyMessages", defaultValue = true)
                            val copySummary = arguments.optionalBoolean("copySummary", defaultValue = true)
                            val forkedSession = sessionRepository.createSession(title = forkTitle)
                            val copyResult =
                                if (copyMessages) {
                                    messageRepository.copyMessagesToSession(
                                        sourceSessionId = sourceSession.id,
                                        targetSessionId = forkedSession.id,
                                    )
                                } else {
                                    MessageRepository.CopyResult(
                                        sourceMessageCount = messageRepository.getMessageCount(sourceSession.id),
                                        copiedMessageCount = 0,
                                        messageIdMap = emptyMap(),
                                    )
                                }
                            val copiedCompactionBoundaryId =
                                sourceSession.compactedUntilMessageId
                                    ?.let { boundaryId -> copyResult.messageIdMap[boundaryId] }
                            if (copySummary && (sourceSession.summaryText != null || copiedCompactionBoundaryId != null)) {
                                sessionRepository.updateSummaryState(
                                    id = forkedSession.id,
                                    summaryText = sourceSession.summaryText,
                                    compactedUntilMessageId = copiedCompactionBoundaryId,
                                )
                            }
                            val storedFork = sessionRepository.getSession(forkedSession.id) ?: forkedSession
                            ToolExecutionResult.success(
                                summary = "Forked session \"${sourceSession.title}\" into \"${storedFork.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sourceSessionId", sourceSession.id)
                                        put("sourceTitle", sourceSession.title)
                                        put("sourceArchived", sourceSession.archived)
                                        put("sessionId", storedFork.id)
                                        put("title", storedFork.title)
                                        put("isMain", storedFork.isMain)
                                        put("archived", storedFork.archived)
                                        put("copyMessages", copyMessages)
                                        put("copySummary", copySummary)
                                        put("sourceMessageCount", copyResult.sourceMessageCount)
                                        put("messageCount", messageRepository.getMessageCount(storedFork.id))
                                        put("copiedMessageCount", copyResult.copiedMessageCount)
                                        put("summaryCopied", copySummary && sourceSession.summaryText != null)
                                        put("sourceCompactedUntilMessageId", sourceSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("compactedUntilMessageId", storedFork.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("compactionBoundaryCopied", copiedCompactionBoundaryId != null)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.merge",
                                    aliases =
                                        listOf(
                                            "session.merge",
                                            "sessions.merge_into",
                                            "session.merge_into",
                                            "sessions.combine",
                                            "session.combine",
                                        ),
                                    description = "Merge one session's transcript into another existing session.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sourceSessionId",
                                                description = "Session id to merge from. Defaults to the active session when targetSessionId is explicit.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "fromSessionId",
                                                description = "Alias for sourceSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "targetSessionId",
                                                description = "Session id to merge into. Defaults to the active session when sourceSessionId is explicit.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "intoSessionId",
                                                description = "Alias for targetSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "copyMessages",
                                                description = "Set false to merge metadata only. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "copySummary",
                                                description = "Set true to copy source summary into an empty target summary slot. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "replaceSummary",
                                                description = "Set true with copySummary to overwrite an existing target summary. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "archiveSource",
                                                description = "Set true to archive the source session after merging. Requires confirm=CONFIRM.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM when archiveSource is true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val explicitSourceSessionId =
                                arguments.optionalText("sourceSessionId")
                                    ?: arguments.optionalText("fromSessionId")
                            val explicitTargetSessionId =
                                arguments.optionalText("targetSessionId")
                                    ?: arguments.optionalText("intoSessionId")
                            val sourceSessionId = explicitSourceSessionId ?: context.sessionId
                            val targetSessionId = explicitTargetSessionId ?: context.sessionId
                            if (sourceSessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "sessions.merge requires a sourceSessionId or active source session.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                            put("field", "sourceSessionId")
                                        },
                                )
                            }
                            if (targetSessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "sessions.merge requires a targetSessionId or active target session.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                            put("field", "targetSessionId")
                                        },
                                )
                            }
                            if (sourceSessionId == targetSessionId) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "sessions.merge source and target sessions must differ.",
                                    errorCode = "INVALID_TARGET_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_TARGET_SESSION")
                                            put("sourceSessionId", sourceSessionId)
                                            put("targetSessionId", targetSessionId)
                                        },
                                )
                            }
                            val sourceSession =
                                sessionRepository.getSession(sourceSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Source session $sourceSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", sourceSessionId)
                                                put("field", "sourceSessionId")
                                            },
                                    )
                            val targetSession =
                                sessionRepository.getSession(targetSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Target session $targetSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", targetSessionId)
                                                put("field", "targetSessionId")
                                            },
                                    )
                            val archiveSource = arguments.optionalBoolean("archiveSource")
                            if (archiveSource && sourceSession.isMain) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "The main session cannot be archived after merge.",
                                    errorCode = "MAIN_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MAIN_SESSION")
                                            put("sourceSessionId", sourceSession.id)
                                        },
                                )
                            }
                            if (archiveSource && arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm source archival with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("sourceSessionId", sourceSession.id)
                                            put("targetSessionId", targetSession.id)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val copyMessages = arguments.optionalBoolean("copyMessages", defaultValue = true)
                            val copyResult =
                                if (copyMessages) {
                                    messageRepository.copyMessagesToSession(
                                        sourceSessionId = sourceSession.id,
                                        targetSessionId = targetSession.id,
                                    )
                                } else {
                                    MessageRepository.CopyResult(
                                        sourceMessageCount = messageRepository.getMessageCount(sourceSession.id),
                                        copiedMessageCount = 0,
                                        messageIdMap = emptyMap(),
                                    )
                                }
                            val copySummary = arguments.optionalBoolean("copySummary")
                            val replaceSummary = arguments.optionalBoolean("replaceSummary")
                            val copiedCompactionBoundaryId =
                                sourceSession.compactedUntilMessageId
                                    ?.let { boundaryId -> copyResult.messageIdMap[boundaryId] }
                            val targetSummaryEmpty = targetSession.summaryText == null && targetSession.compactedUntilMessageId == null
                            val summaryCopied =
                                copySummary &&
                                    (sourceSession.summaryText != null || copiedCompactionBoundaryId != null) &&
                                    (replaceSummary || targetSummaryEmpty)
                            if (summaryCopied) {
                                sessionRepository.updateSummaryState(
                                    id = targetSession.id,
                                    summaryText = sourceSession.summaryText,
                                    compactedUntilMessageId = copiedCompactionBoundaryId,
                                )
                            }
                            if (archiveSource) {
                                sessionRepository.archiveSession(sourceSession.id)
                            }
                            val updatedSourceSession = sessionRepository.getSession(sourceSession.id) ?: sourceSession
                            val updatedTargetSession = sessionRepository.getSession(targetSession.id) ?: targetSession
                            ToolExecutionResult.success(
                                summary = "Merged session \"${sourceSession.title}\" into \"${updatedTargetSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("sourceSessionId", updatedSourceSession.id)
                                        put("sourceTitle", updatedSourceSession.title)
                                        put("sourceArchived", updatedSourceSession.archived)
                                        put("targetSessionId", updatedTargetSession.id)
                                        put("targetTitle", updatedTargetSession.title)
                                        put("targetArchived", updatedTargetSession.archived)
                                        put("copyMessages", copyMessages)
                                        put("sourceMessageCount", copyResult.sourceMessageCount)
                                        put("copiedMessageCount", copyResult.copiedMessageCount)
                                        put("targetMessageCount", messageRepository.getMessageCount(updatedTargetSession.id))
                                        put("copySummary", copySummary)
                                        put("replaceSummary", replaceSummary)
                                        put("summaryCopied", summaryCopied)
                                        put("targetHadSummary", !targetSummaryEmpty)
                                        put("sourceSummaryLength", sourceSession.summaryText?.length ?: 0)
                                        put("targetSummaryLength", updatedTargetSession.summaryText?.length ?: 0)
                                        put("sourceCompactedUntilMessageId", sourceSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("targetCompactedUntilMessageId", updatedTargetSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("compactionBoundaryCopied", copiedCompactionBoundaryId != null)
                                        put("archiveSource", archiveSource)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "sessions.compare",
                                    aliases =
                                        listOf(
                                            "session.compare",
                                            "sessions.diff",
                                            "session.diff",
                                            "sessions.compare_transcripts",
                                            "session.compare_transcripts",
                                        ),
                                    description = "Compare two chat sessions by transcript stats and bounded recent messages.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "leftSessionId",
                                                description = "First session id. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "sourceSessionId",
                                                description = "Alias for leftSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "rightSessionId",
                                                description = "Second session id. Defaults to the active session only when leftSessionId is explicit.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "targetSessionId",
                                                description = "Alias for rightSessionId.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum recent message snippets per side. Defaults to 3.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val explicitLeftSessionId =
                                arguments.optionalText("leftSessionId")
                                    ?: arguments.optionalText("sourceSessionId")
                            val explicitRightSessionId =
                                arguments.optionalText("rightSessionId")
                                    ?: arguments.optionalText("targetSessionId")
                            val leftSessionId = explicitLeftSessionId ?: context.sessionId
                            val rightSessionId =
                                explicitRightSessionId
                                    ?: context.sessionId?.takeIf { activeSessionId -> activeSessionId != leftSessionId }
                            if (leftSessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "sessions.compare requires a leftSessionId or active left session.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                            put("field", "leftSessionId")
                                        },
                                )
                            }
                            if (rightSessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "sessions.compare requires a rightSessionId or second active/default session.",
                                    errorCode = "MISSING_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "MISSING_SESSION")
                                            put("leftSessionId", leftSessionId)
                                            put("field", "rightSessionId")
                                        },
                                )
                            }
                            if (leftSessionId == rightSessionId) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "sessions.compare requires two different sessions.",
                                    errorCode = "INVALID_TARGET_SESSION",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "INVALID_TARGET_SESSION")
                                            put("leftSessionId", leftSessionId)
                                            put("rightSessionId", rightSessionId)
                                        },
                                )
                            }
                            val leftSession =
                                sessionRepository.getSession(leftSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Left session $leftSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", leftSessionId)
                                                put("field", "leftSessionId")
                                            },
                                    )
                            val rightSession =
                                sessionRepository.getSession(rightSessionId)
                                    ?: return@Entry ToolExecutionResult.failure(
                                        summary = "Right session $rightSessionId was not found.",
                                        errorCode = "MISSING_SESSION",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "MISSING_SESSION")
                                                put("sessionId", rightSessionId)
                                                put("field", "rightSessionId")
                                            },
                                    )
                            val limit =
                                arguments
                                    .optionalInt("limit", SESSION_COMPARE_DEFAULT_RECENT_LIMIT)
                                    .coerceIn(0, MESSAGE_RECENT_DEFAULT_LIMIT)
                            val leftStats = messageRepository.getMessageStats(leftSession.id)
                            val rightStats = messageRepository.getMessageStats(rightSession.id)
                            val leftRecent =
                                messageRepository.getRecentMessagesChronological(
                                    sessionId = leftSession.id,
                                    limit = limit,
                                )
                            val rightRecent =
                                messageRepository.getRecentMessagesChronological(
                                    sessionId = rightSession.id,
                                    limit = limit,
                                )
                            ToolExecutionResult.success(
                                summary = "Compared \"${leftSession.title}\" with \"${rightSession.title}\".",
                                payload =
                                    buildJsonObject {
                                        put("leftSessionId", leftSession.id)
                                        put("rightSessionId", rightSession.id)
                                        put("recentLimit", limit)
                                        put("messageCountDeltaRightMinusLeft", rightStats.totalMessageCount - leftStats.totalMessageCount)
                                        put("contentCharDeltaRightMinusLeft", rightStats.totalContentCharCount - leftStats.totalContentCharCount)
                                        put(
                                            "left",
                                            leftSession.toSessionComparePayload(
                                                stats = leftStats,
                                                recentMessages = leftRecent,
                                            ),
                                        )
                                        put(
                                            "right",
                                            rightSession.toSessionComparePayload(
                                                stats = rightStats,
                                                recentMessages = rightRecent,
                                            ),
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
                                    name = "sessions.uncompact",
                                    aliases =
                                        listOf(
                                            "session.uncompact",
                                            "sessions.decompact",
                                            "session.decompact",
                                            "sessions.expand",
                                            "session.expand",
                                        ),
                                    description = "Clear a session compaction boundary so older messages become visible again.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Session id to uncompact. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "clearSummary",
                                                description = "Set true to also delete the stored summary. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Required as CONFIRM when clearSummary is true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            if (sessionId.isNullOrBlank()) {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "No active session is available to uncompact.",
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
                            val clearSummary = arguments.optionalBoolean("clearSummary")
                            if (clearSummary && arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "Confirm clearSummary with confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("sessionId", sessionId)
                                            put("field", "confirm")
                                        },
                                )
                            }
                            if (clearSummary) {
                                sessionRepository.updateSummaryState(
                                    id = sessionId,
                                    summaryText = null,
                                    compactedUntilMessageId = null,
                                )
                            } else {
                                sessionRepository.clearCompactionBoundary(sessionId)
                            }
                            val updatedSession = sessionRepository.getSession(sessionId) ?: existingSession
                            ToolExecutionResult.success(
                                summary =
                                    if (existingSession.compactedUntilMessageId == null && !clearSummary) {
                                        "Session \"${updatedSession.title}\" was already expanded."
                                    } else {
                                        "Expanded session \"${updatedSession.title}\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("sessionId", updatedSession.id)
                                        put("title", updatedSession.title)
                                        put("archived", updatedSession.archived)
                                        put("clearSummary", clearSummary)
                                        put("previousCompacted", existingSession.compactedUntilMessageId != null)
                                        put("previousCompactedUntilMessageId", existingSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("previousSummaryLength", existingSession.summaryText?.length ?: 0)
                                        put("compacted", updatedSession.compactedUntilMessageId != null)
                                        put("compactedUntilMessageId", updatedSession.compactedUntilMessageId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("summaryLength", updatedSession.summaryText?.length ?: 0)
                                    },
                            )
                        },
                    )
    }
