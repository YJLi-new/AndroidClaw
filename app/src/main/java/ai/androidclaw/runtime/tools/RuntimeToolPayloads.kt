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

internal const val RUNTIME_EXPORT_FORMAT = "androidclaw.runtime.export.v1"
internal const val RUNTIME_EXPORT_VERSION = 1
internal const val RUNTIME_HANDOFF_DEFAULT_SECTION_LIMIT = 5
internal const val RUNTIME_HANDOFF_MAX_SECTION_LIMIT = 10
internal const val RUNTIME_PORTABILITY_AUDIT_FORMAT = "androidclaw.runtime.portability.audit.v1"
internal const val RUNTIME_PORTABILITY_AUDIT_VERSION = 1
internal const val RUNTIME_PORTABILITY_COMPONENT_COUNT = 9
internal const val RUNTIME_PORTABILITY_FORMAT_MAX_CHARS = 120
internal data class RuntimePortabilityComponentSpec(
    val key: String,
    val contract: String,
    val exportTool: String,
    val exportFormat: String,
    val importTool: String?,
    val importFormat: String?,
    val restoreSupported: Boolean,
    val scope: String,
    val notes: String,
    val exportStep: Int,
    val restoreStep: Int?,
)

internal data class RuntimePortabilityAuditSource(
    val sourceIndex: Int,
    val sourceKey: String?,
    val value: JsonElement,
)

internal data class RuntimePortabilityAuditEntry(
    val source: RuntimePortabilityAuditSource,
    val format: String?,
    val version: String?,
    val component: RuntimePortabilityComponentSpec?,
    val reason: String?,
)

internal sealed interface RuntimePortabilityAuditSourcesParseResult {
    data class Success(
        val sources: List<RuntimePortabilityAuditSource>,
    ) : RuntimePortabilityAuditSourcesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : RuntimePortabilityAuditSourcesParseResult
}

internal data class RuntimeDoctorIssue(
    val id: String,
    val severity: String,
    val area: String,
    val summary: String,
    val action: String,
)

internal data class SkillDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val skillId: String,
    val skillKey: String,
    val displayName: String,
    val sourceType: String,
    val enabled: Boolean,
    val resolutionState: String,
    val eligibilityStatus: String,
    val summary: String,
    val action: String,
    val parseError: String? = null,
    val missingSecretNames: List<String> = emptyList(),
    val omittedMissingSecretNameCount: Int = 0,
    val missingConfigPaths: List<String> = emptyList(),
    val omittedMissingConfigPathCount: Int = 0,
)

internal data class SkillImportCandidate(
    val sourceIndex: Int,
    val sourceSkillId: String?,
    val sourceSkillKey: String,
    val sourceEnabled: Boolean,
    val frontmatter: SkillFrontmatter,
    val instructionsMd: String,
    val configValues: Map<String, String?>,
) {
    val entry: SkillPackageImportEntry =
        SkillPackageImportEntry(
            sourceIndex = sourceIndex,
            frontmatter = frontmatter,
            instructionsMd = instructionsMd,
            sourceEnabled = sourceEnabled,
            configValues = configValues,
        )
}

internal data class SkillImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

internal sealed interface SkillImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : SkillImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : SkillImportEntriesParseResult
}

internal sealed interface SkillImportCandidateParseResult {
    data class Candidate(
        val candidate: SkillImportCandidate,
    ) : SkillImportCandidateParseResult

    data class Skipped(
        val skipped: SkillImportSkippedEntry,
    ) : SkillImportCandidateParseResult
}

internal data class SessionDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val sessionId: String?,
    val title: String?,
    val isMain: Boolean?,
    val archived: Boolean?,
    val summary: String,
    val action: String,
    val detail: String? = null,
)

internal data class SessionImportCandidate(
    val sourceIndex: Int,
    val sourceSessionId: String?,
    val title: String,
    val sourceIsMain: Boolean,
    val sourceArchived: Boolean,
    val summaryText: String?,
    val sourceCompacted: Boolean,
    val sourceCompactedUntilMessageId: String?,
    val sourceCreatedAtIso: String?,
    val sourceUpdatedAtIso: String?,
    val sourceMessageCount: Long?,
)

internal data class SessionImportedItem(
    val candidate: SessionImportCandidate,
    val session: Session,
    val summaryImported: Boolean,
    val archivedPreserved: Boolean,
)

internal data class SessionImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

internal sealed interface SessionImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : SessionImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : SessionImportEntriesParseResult
}

internal sealed interface SessionImportCandidateParseResult {
    data class Candidate(
        val candidate: SessionImportCandidate,
    ) : SessionImportCandidateParseResult

    data class Skipped(
        val skipped: SessionImportSkippedEntry,
    ) : SessionImportCandidateParseResult
}

internal data class MessageDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val sessionId: String,
    val messageId: String?,
    val role: String?,
    val summary: String,
    val action: String,
    val detail: String? = null,
)

internal data class MessageImportCandidate(
    val sourceIndex: Int,
    val role: MessageRole,
    val content: String,
    val sourceMessageId: String?,
    val sourceCreatedAtIso: String?,
    val toolCallId: String?,
    val taskRunId: String?,
)

internal data class MessageImportedItem(
    val candidate: MessageImportCandidate,
    val message: ChatMessage,
)

internal data class MessageImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

internal sealed interface MessageImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : MessageImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MessageImportEntriesParseResult
}

internal sealed interface MessageImportCandidateParseResult {
    data class Candidate(
        val candidate: MessageImportCandidate,
    ) : MessageImportCandidateParseResult

    data class Skipped(
        val skipped: MessageImportSkippedEntry,
    ) : MessageImportCandidateParseResult
}

internal fun buildRuntimeDoctorIssues(
    settings: ProviderSettingsSnapshot,
    providerAuthState: ProviderAuthState,
    sessionStats: SessionRepository.SessionStats,
    taskStats: TaskRepository.TaskStats,
    memoryEnabled: Boolean,
    memoryRepositoryAvailable: Boolean,
    eventLogRepositoryAvailable: Boolean,
    skills: List<SkillSnapshot>,
    tools: List<ToolDescriptor>,
): List<RuntimeDoctorIssue> =
    buildList {
        val selectedProvider = settings.providerType
        when (providerAuthState.status) {
            "Missing" ->
                add(
                    RuntimeDoctorIssue(
                        id = "provider.auth.missing",
                        severity = "Error",
                        area = "provider",
                        summary = "Selected provider ${selectedProvider.displayName} is missing credentials.",
                        action = "Configure the provider credential or select a local/no-auth provider.",
                    ),
                )
            "Unknown" ->
                if (selectedProvider.requiresApiKey || selectedProvider.usesOpenAiCodexOAuth) {
                    add(
                        RuntimeDoctorIssue(
                            id = "provider.auth.unknown",
                            severity = "Warning",
                            area = "provider",
                            summary = "Selected provider ${selectedProvider.displayName} credential status is unknown.",
                            action = "Wire a ProviderSecretStore or verify credentials before remote calls.",
                        ),
                    )
                }
        }
        if (selectedProvider.requiresRemoteSettings) {
            val endpointSettings = settings.endpointSettings(selectedProvider)
            if (endpointSettings.baseUrl.isBlank()) {
                add(
                    RuntimeDoctorIssue(
                        id = "provider.endpoint.base_url_blank",
                        severity = "Error",
                        area = "provider",
                        summary = "Selected provider ${selectedProvider.displayName} has a blank base URL.",
                        action = "Run providers.configure with a non-empty baseUrl.",
                    ),
                )
            }
            if (endpointSettings.modelId.isBlank()) {
                add(
                    RuntimeDoctorIssue(
                        id = "provider.endpoint.model_id_blank",
                        severity = "Error",
                        area = "provider",
                        summary = "Selected provider ${selectedProvider.displayName} has a blank model id.",
                        action = "Run providers.configure with a non-empty modelId.",
                    ),
                )
            }
            if (endpointSettings.timeoutSeconds <= 0) {
                add(
                    RuntimeDoctorIssue(
                        id = "provider.endpoint.timeout_invalid",
                        severity = "Error",
                        area = "provider",
                        summary = "Selected provider ${selectedProvider.displayName} has a non-positive timeout.",
                        action = "Run providers.configure with timeoutSeconds greater than zero.",
                    ),
                )
            }
        }
        when {
            sessionStats.mainSessionCount == 0L ->
                add(
                    RuntimeDoctorIssue(
                        id = "sessions.main.missing",
                        severity = "Warning",
                        area = "sessions",
                        summary = "No main session is currently persisted.",
                        action = "Open chat or create the main session before relying on main-session automations.",
                    ),
                )
            sessionStats.mainSessionCount > 1L ->
                add(
                    RuntimeDoctorIssue(
                        id = "sessions.main.duplicate",
                        severity = "Error",
                        area = "sessions",
                        summary = "More than one main session is persisted.",
                        action = "Keep exactly one main session and archive or repair duplicates.",
                    ),
                )
        }
        if (taskStats.dueTaskCount > 0L) {
            add(
                RuntimeDoctorIssue(
                    id = "automations.due_backlog",
                    severity = "Warning",
                    area = "automations",
                    summary = "${taskStats.dueTaskCount} enabled automation(s) are currently due.",
                    action = "Let WorkManager run due automations or inspect tasks.due.",
                ),
            )
        }
        if (memoryEnabled && !memoryRepositoryAvailable) {
            add(
                RuntimeDoctorIssue(
                    id = "memory.repository_unavailable",
                    severity = "Error",
                    area = "memory",
                    summary = "Memory is enabled but the memory repository is unavailable.",
                    action = "Wire MemoryRepository before enabling local memory.",
                ),
            )
        }
        val parseErrorCount = skills.count { skill -> skill.parseError != null }
        if (parseErrorCount > 0) {
            add(
                RuntimeDoctorIssue(
                    id = "skills.parse_errors",
                    severity = "Warning",
                    area = "skills",
                    summary = "$parseErrorCount skill(s) have parse errors.",
                    action = "Run skills.stats or skills.get for affected skills and fix their SKILL.md frontmatter.",
                ),
            )
        }
        val ineligibleSkillCount = skills.count { skill -> skill.eligibility.status != SkillEligibilityStatus.Eligible }
        if (ineligibleSkillCount > 0) {
            add(
                RuntimeDoctorIssue(
                    id = "skills.ineligible",
                    severity = "Warning",
                    area = "skills",
                    summary = "$ineligibleSkillCount skill(s) are not currently eligible.",
                    action = "Run skills.stats or skills.list to inspect missing tools, invalid skills, or bridge-only skills.",
                ),
            )
        }
        val availableToolCount = tools.count { tool -> tool.availability.status == ToolAvailabilityStatus.Available }
        if (tools.isEmpty() || availableToolCount == 0) {
            add(
                RuntimeDoctorIssue(
                    id = "tools.none_available",
                    severity = "Error",
                    area = "tools",
                    summary = "No typed native tools are currently available.",
                    action = "Inspect tool registry wiring and tool availability providers.",
                ),
            )
        }
        if (!eventLogRepositoryAvailable) {
            add(
                RuntimeDoctorIssue(
                    id = "events.repository_unavailable",
                    severity = "Warning",
                    area = "events",
                    summary = "Runtime event logging is unavailable.",
                    action = "Wire EventLogRepository to retain local diagnostics.",
                ),
            )
        }
    }

internal fun List<RuntimeDoctorIssue>.toRuntimeDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun RuntimeDoctorIssue.toRuntimeDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("area", area)
        put("summary", summary)
        put("action", action)
    }

internal fun SessionRepository.SessionStats.toRuntimeDoctorSessionCheckPayload(): JsonObject =
    buildJsonObject {
        put("sessionCount", totalSessionCount)
        put("activeSessionCount", activeSessionCount)
        put("archivedSessionCount", archivedSessionCount)
        put("mainSessionCount", mainSessionCount)
        put("summarizedSessionCount", summarizedSessionCount)
        put("compactedSessionCount", compactedSessionCount)
    }

internal fun TaskRepository.TaskStats.toRuntimeDoctorTaskCheckPayload(): JsonObject =
    buildJsonObject {
        put("taskCount", totalTaskCount)
        put("enabledTaskCount", enabledTaskCount)
        put("disabledTaskCount", disabledTaskCount)
        put("scheduledTaskCount", scheduledTaskCount)
        put("dueTaskCount", dueTaskCount)
        put("runCount", totalRunCount)
        put("nextEnabledRunAtIso", nextEnabledRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    }

internal suspend fun MemoryRepository?.toRuntimeMemorySectionPayload(settingsDataStore: SettingsDataStore): JsonObject {
    if (this == null) {
        return buildJsonObject {
            put("available", false)
            put("enabled", JsonNull)
            put("activeMemoryCount", JsonNull)
            put("deletedMemoryCount", JsonNull)
            put("totalMemoryCount", JsonNull)
            put("ownerUserIdIncluded", false)
        }
    }
    val settings = settingsDataStore.memorySettingsSnapshot()
    val stats = stats(settings.installUserId)
    return buildJsonObject {
        put("available", true)
        put("enabled", settings.enabled)
        put("activeMemoryCount", stats.activeMemoryCount)
        put("deletedMemoryCount", stats.deletedMemoryCount)
        put("totalMemoryCount", stats.totalMemoryCount)
        put("activeWithSourceSessionCount", stats.activeWithSourceSessionCount)
        put("oldestActiveCreatedAtIso", stats.oldestActiveCreatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestActiveUpdatedAtIso", stats.newestActiveUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("ownerUserIdIncluded", false)
        put(
            "sourceTypeStats",
            buildJsonArray {
                stats.sourceTypeStats.forEach { sourceTypeStats ->
                    add(
                        buildJsonObject {
                            put("sourceType", sourceTypeStats.sourceType)
                            put("memoryCount", sourceTypeStats.memoryCount)
                        },
                    )
                }
            },
        )
    }
}

internal suspend fun EventLogRepository?.toRuntimeEventSectionPayload(): JsonObject =
    buildJsonObject {
        put("available", this@toRuntimeEventSectionPayload != null)
        put("eventCount", this@toRuntimeEventSectionPayload?.count()?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun runtimeExportOmissionsPayload(): JsonObject =
    buildJsonObject {
        put("messageBodiesIncluded", false)
        put("messageProviderMetaIncluded", false)
        put("taskPromptBodiesIncluded", false)
        put("taskRunHistoryIncluded", false)
        put("skillInstructionBodiesIncluded", false)
        put("skillRawFrontmatterIncluded", false)
        put("skillBaseDirsIncluded", false)
        put("toolDescriptorsIncluded", false)
        put("toolInputSchemasIncluded", false)
        put("toolExecutionResultsIncluded", false)
        put("memoryTextIncluded", false)
        put("memoryOwnerUserIdIncluded", false)
        put("eventDetailsIncluded", false)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("providerCredentialValuesIncluded", false)
        put("desktopRuntimeStateIncluded", false)
    }

internal fun runtimePortabilityExportOrderPayload(): JsonArray =
    buildJsonArray {
        runtimePortabilityComponentSpecs()
            .sortedBy { component -> component.exportStep }
            .forEach { component ->
                add(
                    runtimePortabilityStepPayload(
                        step = component.exportStep,
                        toolName = component.exportTool,
                        format = component.exportFormat,
                        scope = component.scope,
                    ),
                )
            }
    }

internal fun runtimePortabilityRestoreOrderPayload(): JsonArray =
    buildJsonArray {
        runtimePortabilityComponentSpecs()
            .filter { component -> component.restoreStep != null }
            .sortedBy { component -> requireNotNull(component.restoreStep) }
            .forEach { component ->
                add(
                    runtimePortabilityStepPayload(
                        step = requireNotNull(component.restoreStep),
                        toolName = requireNotNull(component.importTool),
                        format = requireNotNull(component.importFormat),
                        scope = component.scope,
                    ),
                )
            }
    }

internal fun runtimePortabilityStepPayload(
    step: Int,
    toolName: String,
    format: String,
    scope: String,
): JsonObject =
    buildJsonObject {
        put("step", step)
        put("toolName", toolName)
        put("format", format)
        put("scope", scope)
    }

internal fun runtimePortabilityComponentSpecs(): List<RuntimePortabilityComponentSpec> =
    listOf(
        RuntimePortabilityComponentSpec(
            key = "runtime",
            contract = "Runtime",
            exportTool = "runtime.export",
            exportFormat = RUNTIME_EXPORT_FORMAT,
            importTool = null,
            importFormat = null,
            restoreSupported = false,
            scope = "Runtime manifest",
            notes = "Records host invariants and contract stats; restore is delegated to component import tools.",
            exportStep = 1,
            restoreStep = null,
        ),
        RuntimePortabilityComponentSpec(
            key = "providers",
            contract = "Providers/OAuth",
            exportTool = "providers.export",
            exportFormat = PROVIDER_EXPORT_FORMAT,
            importTool = "providers.import",
            importFormat = PROVIDER_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Provider endpoints",
            notes = "Endpoint settings are portable; API keys, OAuth tokens, and credential state are never included.",
            exportStep = 2,
            restoreStep = 1,
        ),
        RuntimePortabilityComponentSpec(
            key = "skills",
            contract = "Skills",
            exportTool = "skills.export",
            exportFormat = SKILL_EXPORT_FORMAT,
            importTool = "skills.import",
            importFormat = SKILL_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Skill definitions",
            notes = "Skill definitions and optional non-secret config are portable; secrets remain omitted.",
            exportStep = 3,
            restoreStep = 2,
        ),
        RuntimePortabilityComponentSpec(
            key = "tools",
            contract = "Tools",
            exportTool = "tools.export",
            exportFormat = TOOL_EXPORT_FORMAT,
            importTool = null,
            importFormat = null,
            restoreSupported = false,
            scope = "Typed tool catalog",
            notes = "Typed native tools are built into the APK; the catalog is export-only capability metadata.",
            exportStep = 4,
            restoreStep = null,
        ),
        RuntimePortabilityComponentSpec(
            key = "sessions",
            contract = "Sessions",
            exportTool = "sessions.export",
            exportFormat = SESSION_EXPORT_FORMAT,
            importTool = "sessions.import",
            importFormat = SESSION_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Session shells",
            notes = "Session shells and summaries are portable; transcript bodies are handled by messages export/import.",
            exportStep = 5,
            restoreStep = 3,
        ),
        RuntimePortabilityComponentSpec(
            key = "messages",
            contract = "Sessions",
            exportTool = "messages.export",
            exportFormat = MESSAGE_EXPORT_FORMAT,
            importTool = "messages.import",
            importFormat = MESSAGE_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Per-session transcripts",
            notes = "Run per session when full transcript windows are needed; provider metadata is omitted.",
            exportStep = 6,
            restoreStep = 4,
        ),
        RuntimePortabilityComponentSpec(
            key = "automations",
            contract = "Automations",
            exportTool = "tasks.export",
            exportFormat = TASK_EXPORT_FORMAT,
            importTool = "tasks.import",
            importFormat = TASK_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Scheduled automations",
            notes = "Schedules and execution modes are portable; run history and provider state are omitted.",
            exportStep = 7,
            restoreStep = 5,
        ),
        RuntimePortabilityComponentSpec(
            key = "memory",
            contract = "Memory",
            exportTool = "memory.export",
            exportFormat = MEMORY_EXPORT_FORMAT,
            importTool = "memory.import",
            importFormat = MEMORY_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Local memory",
            notes = "Local memory can be portable, but owner ids, source message bodies, and provider metadata are omitted.",
            exportStep = 8,
            restoreStep = 6,
        ),
        RuntimePortabilityComponentSpec(
            key = "events",
            contract = "Tools",
            exportTool = "events.export",
            exportFormat = EVENT_EXPORT_FORMAT,
            importTool = "events.import",
            importFormat = EVENT_IMPORT_FORMAT,
            restoreSupported = true,
            scope = "Event diagnostics",
            notes = "Bounded event diagnostics are portable; details are omitted unless explicitly requested.",
            exportStep = 9,
            restoreStep = 7,
        ),
    )

internal fun runtimePortabilityComponentsPayload(): JsonArray =
    buildJsonArray {
        runtimePortabilityComponentSpecs().forEach { component ->
            add(component.toRuntimePortabilityComponentPayload())
        }
    }

internal fun RuntimePortabilityComponentSpec.toRuntimePortabilityComponentPayload(): JsonObject =
    buildJsonObject {
        put("key", key)
        put("contract", contract)
        put("exportTool", exportTool)
        put("exportFormat", exportFormat)
        put("importTool", importTool?.let(::JsonPrimitive) ?: JsonNull)
        put("importFormat", importFormat?.let(::JsonPrimitive) ?: JsonNull)
        put("backupSupported", true)
        put("restoreSupported", restoreSupported)
        put("importRequiresConfirmation", restoreSupported)
        put("dryRunSupported", restoreSupported)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("messageBodiesIncluded", false)
        put("providerMetaIncluded", false)
        put("notes", notes)
    }

internal fun JsonObject.runtimePortabilityAuditSources(): RuntimePortabilityAuditSourcesParseResult {
    val exports =
        this["exports"]
            ?: (this["backup"] as? JsonObject)?.get("exports")
            ?: JsonArray(emptyList())
    return exports.toRuntimePortabilityAuditSources()
}

internal fun JsonElement.toRuntimePortabilityAuditSources(): RuntimePortabilityAuditSourcesParseResult =
    when (this) {
        is JsonArray ->
            RuntimePortabilityAuditSourcesParseResult.Success(
                mapIndexed { index, value ->
                    RuntimePortabilityAuditSource(
                        sourceIndex = index,
                        sourceKey = null,
                        value = value,
                    )
                },
            )
        is JsonObject ->
            if (containsKey("exportFormat") || containsKey("format")) {
                RuntimePortabilityAuditSourcesParseResult.Success(
                    listOf(
                        RuntimePortabilityAuditSource(
                            sourceIndex = 0,
                            sourceKey = null,
                            value = this,
                        ),
                    ),
                )
            } else {
                RuntimePortabilityAuditSourcesParseResult.Success(
                    entries.mapIndexed { index, entry ->
                        RuntimePortabilityAuditSource(
                            sourceIndex = index,
                            sourceKey = entry.key.take(RUNTIME_PORTABILITY_FORMAT_MAX_CHARS),
                            value = entry.value,
                        )
                    },
                )
            }
        else ->
            RuntimePortabilityAuditSourcesParseResult.Failure(
                invalidRuntimePortabilityAuditExportsResult(),
            )
    }

internal fun invalidRuntimePortabilityAuditExportsResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Portability audit exports must be an array, export object, or object keyed by component.",
        errorCode = "INVALID_RUNTIME_PORTABILITY_AUDIT_EXPORTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_RUNTIME_PORTABILITY_AUDIT_EXPORTS")
                put("field", "exports")
            },
    )

internal fun RuntimePortabilityAuditSource.toRuntimePortabilityAuditEntry(
    componentByFormat: Map<String, RuntimePortabilityComponentSpec>,
): RuntimePortabilityAuditEntry {
    val objectValue =
        value as? JsonObject ?: return RuntimePortabilityAuditEntry(
            source = this,
            format = null,
            version = null,
            component = null,
            reason = "entry_not_object",
        )
    val format =
        objectValue.optionalText("exportFormat")
            ?: objectValue.optionalText("format")
    if (format == null) {
        return RuntimePortabilityAuditEntry(
            source = this,
            format = null,
            version =
                objectValue.optionalText("exportVersion")
                    ?: objectValue.optionalText("version"),
            component = null,
            reason = "missing_format",
        )
    }
    val component = componentByFormat[format]
    return RuntimePortabilityAuditEntry(
        source = this,
        format = format,
        version =
            objectValue.optionalText("exportVersion")
                ?: objectValue.optionalText("version"),
        component = component,
        reason = if (component == null) "unknown_format" else null,
    )
}

internal fun RuntimePortabilityAuditEntry.toRuntimePortabilityAuditPresentPayload(): JsonObject {
    val component = requireNotNull(component)
    return buildJsonObject {
        put("sourceIndex", source.sourceIndex)
        put("sourceKey", source.sourceKey?.let(::JsonPrimitive) ?: JsonNull)
        put("key", component.key)
        put("contract", component.contract)
        put("exportTool", component.exportTool)
        put("exportFormat", component.exportFormat)
        put("sourceExportVersion", version?.take(RUNTIME_PORTABILITY_FORMAT_MAX_CHARS)?.let(::JsonPrimitive) ?: JsonNull)
        put("importTool", component.importTool?.let(::JsonPrimitive) ?: JsonNull)
        put("importFormat", component.importFormat?.let(::JsonPrimitive) ?: JsonNull)
        put("restoreSupported", component.restoreSupported)
        put("payloadIncluded", false)
    }
}

internal fun RuntimePortabilityComponentSpec.toRuntimePortabilityAuditComponentPayload(): JsonObject =
    buildJsonObject {
        put("key", key)
        put("contract", contract)
        put("exportTool", exportTool)
        put("exportFormat", exportFormat)
        put("importTool", importTool?.let(::JsonPrimitive) ?: JsonNull)
        put("importFormat", importFormat?.let(::JsonPrimitive) ?: JsonNull)
        put("restoreSupported", restoreSupported)
        put("scope", scope)
    }

internal fun List<RuntimePortabilityAuditEntry>.toRuntimePortabilityDuplicatePayload(
    componentKey: String,
): JsonObject {
    val component = requireNotNull(first().component)
    return buildJsonObject {
        put("key", componentKey)
        put("exportFormat", component.exportFormat)
        put("duplicateCount", size)
        put(
            "sources",
            buildJsonArray {
                this@toRuntimePortabilityDuplicatePayload.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("sourceIndex", entry.source.sourceIndex)
                            put("sourceKey", entry.source.sourceKey?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                }
            },
        )
    }
}

internal fun RuntimePortabilityAuditEntry.toRuntimePortabilityUnknownPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", source.sourceIndex)
        put("sourceKey", source.sourceKey?.let(::JsonPrimitive) ?: JsonNull)
        put("format", format?.take(RUNTIME_PORTABILITY_FORMAT_MAX_CHARS)?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceExportVersion", version?.take(RUNTIME_PORTABILITY_FORMAT_MAX_CHARS)?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", reason ?: "unknown_format")
        put("payloadIncluded", false)
    }

internal fun buildRuntimePortabilityAuditMarkdown(
    status: String,
    receivedExportCount: Int,
    recognizedExportCount: Int,
    missingComponents: List<RuntimePortabilityComponentSpec>,
    duplicateGroups: Map<String, List<RuntimePortabilityAuditEntry>>,
    unknownEntries: List<RuntimePortabilityAuditEntry>,
    recommendedRestoreComponents: List<RuntimePortabilityComponentSpec>,
): String =
    buildString {
        appendLine("# AndroidClaw portability audit")
        appendLine()
        appendLine("- Audit format: `$RUNTIME_PORTABILITY_AUDIT_FORMAT`")
        appendLine("- Status: $status")
        appendLine("- Received exports: $receivedExportCount")
        appendLine("- Recognized exports: $recognizedExportCount")
        appendLine("- Missing components: ${missingComponents.size}")
        appendLine("- Duplicate components: ${duplicateGroups.size}")
        appendLine("- Unknown exports: ${unknownEntries.size}")
        appendLine("- Component payloads included: false")
        appendLine("- Secret values included: false")
        appendLine()
        appendLine("## Missing export tools")
        if (missingComponents.isEmpty()) {
            appendLine("- None")
        } else {
            missingComponents
                .sortedBy { component -> component.exportStep }
                .forEach { component ->
                    appendLine("- `${component.exportTool}` (${component.exportFormat})")
                }
        }
        appendLine()
        appendLine("## Restore order for recognized restore-capable components")
        if (recommendedRestoreComponents.isEmpty()) {
            appendLine("- None")
        } else {
            recommendedRestoreComponents.forEach { component ->
                appendLine("${component.restoreStep}. `${component.importTool}` (${component.importFormat})")
            }
        }
    }

internal fun buildRuntimePortabilityMarkdown(
    generatedAt: Instant,
    providerType: ProviderType,
    sessionStats: SessionRepository.SessionStats,
    taskStats: TaskRepository.TaskStats,
    memorySection: JsonObject,
    eventSection: JsonObject,
    skillCount: Int,
    enabledSkillCount: Int,
    toolCount: Int,
    availableToolCount: Int,
): String =
    buildString {
        appendLine("# AndroidClaw portability playbook")
        appendLine()
        appendLine("- Generated: $generatedAt")
        appendLine("- Runtime model: Android-native single-APK host")
        appendLine("- Current provider: `${providerType.providerId}` (${providerType.displayName.toHandoffLine()})")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine("- Component payloads included: false")
        appendLine("- Desktop runtime required: false")
        appendLine()
        appendLine("## Current counts")
        appendLine("- Sessions: total=${sessionStats.totalSessionCount} active=${sessionStats.activeSessionCount} archived=${sessionStats.archivedSessionCount}")
        appendLine("- Automations: total=${taskStats.totalTaskCount} enabled=${taskStats.enabledTaskCount} runs=${taskStats.totalRunCount}")
        appendLine("- Skills: total=$skillCount enabled=$enabledSkillCount")
        appendLine("- Tools: total=$toolCount available=$availableToolCount")
        appendLine(
            "- Memory: available=${memorySection.optionalText("available") ?: "unknown"} " +
                "enabled=${memorySection.optionalText("enabled") ?: "unknown"} " +
                "active=${memorySection.optionalText("activeMemoryCount") ?: "unknown"}",
        )
        appendLine(
            "- Event diagnostics: available=${eventSection.optionalText("available") ?: "unknown"} " +
                "count=${eventSection.optionalText("eventCount") ?: "unknown"}",
        )
        appendLine()
        appendLine("## Recommended export order")
        appendLine("1. `runtime.export` - capture host invariants and contract stats.")
        appendLine("2. `providers.export` - capture non-secret provider endpoints.")
        appendLine("3. `skills.export` - capture skill definitions before automation restore.")
        appendLine("4. `tools.export` - capture typed tool capability catalog.")
        appendLine("5. `sessions.export` - capture session shells and summaries.")
        appendLine("6. `messages.export` - capture per-session transcript windows when needed.")
        appendLine("7. `tasks.export` - capture scheduled automations after target sessions are known.")
        appendLine("8. `memory.export` - capture local memory last because it may reference sessions/messages.")
        appendLine("9. `events.export` - capture bounded event diagnostics last for troubleshooting context.")
        appendLine()
        appendLine("## Recommended restore order")
        appendLine("1. `providers.import`")
        appendLine("2. `skills.import`")
        appendLine("3. `sessions.import`")
        appendLine("4. `messages.import`")
        appendLine("5. `tasks.import`")
        appendLine("6. `memory.import`")
        appendLine("7. `events.import`")
    }

internal fun buildRuntimeExportMarkdown(
    generatedAt: Instant,
    currentProvider: ProviderType,
    providerAuthState: ProviderAuthState,
    sessionStats: SessionRepository.SessionStats,
    taskStats: TaskRepository.TaskStats,
    memorySection: JsonObject,
    eventSection: JsonObject,
    skillCount: Int,
    enabledSkillCount: Int,
    toolCount: Int,
    availableToolCount: Int,
    minimumBackgroundIntervalMinutes: Long,
): String =
    buildString {
        appendLine("# AndroidClaw runtime export")
        appendLine()
        appendLine("- Export format: `$RUNTIME_EXPORT_FORMAT`")
        appendLine("- Export version: $RUNTIME_EXPORT_VERSION")
        appendLine("- Generated: $generatedAt")
        appendLine("- Runtime model: Android-native single-APK host")
        appendLine("- Phone is host: true")
        appendLine("- Desktop host required: false")
        appendLine("- Node runtime included: false")
        appendLine("- Docker runtime included: false")
        appendLine("- Chromium runtime included: false")
        appendLine("- Secret values included: false")
        appendLine("- Message, prompt, skill, memory, and event bodies included: false")
        appendLine()
        appendLine("## Compatibility contracts")
        appendLine(
            "- Sessions: total=${sessionStats.totalSessionCount} active=${sessionStats.activeSessionCount} " +
                "archived=${sessionStats.archivedSessionCount} summarized=${sessionStats.summarizedSessionCount} " +
                "compacted=${sessionStats.compactedSessionCount}",
        )
        appendLine("- Tools: total=$toolCount available=$availableToolCount descriptorsIncluded=false")
        appendLine("- Skills: total=$skillCount enabled=$enabledSkillCount instructionBodiesIncluded=false")
        appendLine(
            "- Automations: total=${taskStats.totalTaskCount} enabled=${taskStats.enabledTaskCount} " +
                "disabled=${taskStats.disabledTaskCount} due=${taskStats.dueTaskCount} " +
                "minimumBackgroundIntervalMinutes=$minimumBackgroundIntervalMinutes",
        )
        appendLine()
        appendLine("## Runtime sections")
        appendLine(
            "- Provider: `${currentProvider.providerId}` " +
                "${currentProvider.displayName.toHandoffLine()} auth=${providerAuthState.status}",
        )
        appendLine(
            "- Memory: available=${memorySection.optionalText("available") ?: "unknown"} " +
                "enabled=${memorySection.optionalText("enabled") ?: "unknown"} " +
                "active=${memorySection.optionalText("activeMemoryCount") ?: "unknown"} textIncluded=false",
        )
        appendLine(
            "- Event logs: available=${eventSection.optionalText("available") ?: "unknown"} " +
                "count=${eventSection.optionalText("eventCount") ?: "unknown"} detailsIncluded=false",
        )
    }

internal fun buildRuntimeHandoffMarkdown(
    generatedAt: Instant,
    context: ToolExecutionContext,
    currentProvider: ProviderType,
    providerAuthState: ProviderAuthState,
    sessionStats: SessionRepository.SessionStats,
    recentSessions: List<SessionRepository.SessionActivity>,
    recentSessionLimit: Int,
    taskStats: TaskRepository.TaskStats,
    upcomingTasks: List<Task>,
    upcomingTaskLimit: Int,
    memorySection: JsonObject,
    eventSection: JsonObject,
    skillCount: Int,
    enabledSkillCount: Int,
    toolCount: Int,
    availableToolCount: Int,
): String =
    buildString {
        appendLine("# AndroidClaw runtime handoff")
        appendLine()
        appendLine("- Generated: $generatedAt")
        appendLine("- Requested session id: ${context.sessionId ?: "none"}")
        appendLine("- Requested task run id: ${context.taskRunId ?: "none"}")
        appendLine("- Origin: ${context.origin.name}")
        appendLine("- Run mode: ${context.runMode?.name ?: "none"}")
        appendLine("- Heavy content included: false")
        appendLine("- Secret values included: false")
        appendLine()
        appendLine("## Runtime counts")
        appendLine("- Provider: `${currentProvider.providerId}` ${currentProvider.displayName.toHandoffLine()} auth=${providerAuthState.status}")
        appendLine(
            "- Sessions: total=${sessionStats.totalSessionCount} active=${sessionStats.activeSessionCount} " +
                "archived=${sessionStats.archivedSessionCount} summarized=${sessionStats.summarizedSessionCount} " +
                "compacted=${sessionStats.compactedSessionCount}",
        )
        appendLine(
            "- Automations: total=${taskStats.totalTaskCount} enabled=${taskStats.enabledTaskCount} " +
                "disabled=${taskStats.disabledTaskCount} due=${taskStats.dueTaskCount} runs=${taskStats.totalRunCount}",
        )
        appendLine(
            "- Memory: available=${memorySection.optionalText("available") ?: "unknown"} " +
                "enabled=${memorySection.optionalText("enabled") ?: "unknown"} " +
                "active=${memorySection.optionalText("activeMemoryCount") ?: "unknown"}",
        )
        appendLine("- Skills: total=$skillCount enabled=$enabledSkillCount")
        appendLine("- Tools: total=$toolCount available=$availableToolCount")
        appendLine(
            "- Event logs: available=${eventSection.optionalText("available") ?: "unknown"} " +
                "count=${eventSection.optionalText("eventCount") ?: "unknown"}",
        )
        appendLine()
        appendLine("## Recent sessions")
        appendLine("- Included: ${recentSessions.size} of up to $recentSessionLimit")
        if (recentSessions.isEmpty()) {
            appendLine("_No recent sessions included._")
        } else {
            recentSessions.forEach { activity ->
                appendLine(activity.toRuntimeHandoffMarkdownLine())
            }
        }
        appendLine()
        appendLine("## Upcoming automations")
        appendLine("- Included: ${upcomingTasks.size} of up to $upcomingTaskLimit")
        if (upcomingTasks.isEmpty()) {
            appendLine("_No upcoming enabled automations included._")
        } else {
            upcomingTasks.forEach { task ->
                appendLine(task.toRuntimeHandoffMarkdownLine())
            }
        }
    }

internal fun SessionRepository.SessionActivity.toRuntimeHandoffMarkdownLine(): String =
    buildString {
        append("- `")
        append(session.title.toHandoffLine())
        append("` id=`")
        append(session.id)
        append("` messages=")
        append(messageCount)
        append(" main=")
        append(session.isMain)
        append(" compacted=")
        append(session.compactedUntilMessageId != null)
        append(" updated=")
        append(session.updatedAt)
    }

internal fun Task.toRuntimeHandoffMarkdownLine(): String =
    buildString {
        append("- `")
        append(name.toHandoffLine())
        append("` id=`")
        append(id)
        append("` schedule=")
        append(schedule.toTaskSearchKind())
        append(" mode=")
        append(executionMode.name)
        append(" next=")
        append(nextRunAt ?: "none")
    }

