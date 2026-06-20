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

internal fun runtimeToolEntries(
    settingsDataStore: SettingsDataStore,
    sessionRepository: SessionRepository,
    taskRepository: TaskRepository,
    schedulerCoordinator: SchedulerCoordinator,
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
    providerSecretStore: ProviderSecretStore?,
    memoryRepository: MemoryRepository?,
    eventLogRepository: EventLogRepository?,
    toolRegistryProvider: () -> ToolRegistry,
    clock: Clock,
): List<ToolRegistry.Entry> =
    buildList {
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
                                                toolRegistryProvider().descriptors().forEach { tool ->
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
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "runtime.handoff",
                                    aliases =
                                        listOf(
                                            "runtime.snapshot",
                                            "androidclaw.handoff",
                                            "androidclaw.snapshot",
                                            "system.handoff",
                                            "system.snapshot",
                                        ),
                                    description =
                                        "Return a compact AndroidClaw runtime handoff with cross-contract counts and bounded context.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "recentSessionLimit",
                                                description = "Maximum recent session activity entries. Defaults to 5, max 10.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "upcomingTaskLimit",
                                                description = "Maximum upcoming automation entries. Defaults to 5, max 10.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val recentSessionLimit =
                                arguments
                                    .optionalInt(
                                        field = "recentSessionLimit",
                                        defaultValue = RUNTIME_HANDOFF_DEFAULT_SECTION_LIMIT,
                                    ).coerceIn(0, RUNTIME_HANDOFF_MAX_SECTION_LIMIT)
                            val upcomingTaskLimit =
                                arguments
                                    .optionalInt(
                                        field = "upcomingTaskLimit",
                                        defaultValue = RUNTIME_HANDOFF_DEFAULT_SECTION_LIMIT,
                                    ).coerceIn(0, RUNTIME_HANDOFF_MAX_SECTION_LIMIT)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val now = clock.instant()
                            val settings = settingsDataStore.settings.first()
                            val selectedProviderAuthState =
                                settings.providerType.toProviderAuthState(
                                    providerSecretStore = providerSecretStore,
                                    clock = clock,
                                )
                            val providerStats =
                                settings.toProviderStatsPayload(
                                    providerSecretStore = providerSecretStore,
                                    clock = clock,
                                )
                            val sessionStats = sessionRepository.getSessionStats()
                            val recentSessions =
                                sessionRepository.listSessionActivity(
                                    limit = recentSessionLimit,
                                    includeArchived = false,
                                )
                            val taskStats = taskRepository.getTaskStats(now)
                            val upcomingTasks = taskRepository.getUpcomingEnabledTasks(upcomingTaskLimit)
                            val memorySection =
                                memoryRepository.toRuntimeMemorySectionPayload(
                                    settingsDataStore = settingsDataStore,
                                )
                            val eventSection =
                                eventLogRepository.toRuntimeEventSectionPayload()
                            val skills = bundledSkillsProvider()
                            val tools = toolRegistryProvider().descriptors()
                            val handoffMarkdown =
                                if (includeMarkdown) {
                                    buildRuntimeHandoffMarkdown(
                                        generatedAt = now,
                                        context = context,
                                        currentProvider = settings.providerType,
                                        providerAuthState = selectedProviderAuthState,
                                        sessionStats = sessionStats,
                                        recentSessions = recentSessions,
                                        recentSessionLimit = recentSessionLimit,
                                        taskStats = taskStats,
                                        upcomingTasks = upcomingTasks,
                                        upcomingTaskLimit = upcomingTaskLimit,
                                        memorySection = memorySection,
                                        eventSection = eventSection,
                                        skillCount = skills.size,
                                        enabledSkillCount = skills.count { skill -> skill.enabled },
                                        toolCount = tools.size,
                                        availableToolCount = tools.count { tool -> tool.availability.status == ToolAvailabilityStatus.Available },
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary = "Prepared AndroidClaw runtime handoff snapshot.",
                                payload =
                                    buildJsonObject {
                                        put("generatedAtIso", now.toString())
                                        put("requestedSessionId", context.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("requestedTaskRunId", context.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("origin", context.origin.name)
                                        put("runMode", context.runMode?.name?.let(::JsonPrimitive) ?: JsonNull)
                                        put("recentSessionLimit", recentSessionLimit)
                                        put("upcomingTaskLimit", upcomingTaskLimit)
                                        put("includeMarkdown", includeMarkdown)
                                        put("heavyContentIncluded", false)
                                        put("secretValuesIncluded", false)
                                        put("provider", settings.providerType.toProviderHandoffPayload(settings, selectedProviderAuthState))
                                        put("providerStats", providerStats)
                                        put("sessionStats", sessionStats.toSessionStatsPayload())
                                        put(
                                            "recentSessions",
                                            buildJsonArray {
                                                recentSessions.forEach { activity ->
                                                    add(activity.toSessionActivityPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "taskStats",
                                            taskStats.toTaskStatsPayload(
                                                minimumBackgroundIntervalMinutes =
                                                    schedulerCoordinator
                                                        .capabilities()
                                                        .minimumBackgroundInterval
                                                        .toMinutes(),
                                            ),
                                        )
                                        put(
                                            "upcomingTasks",
                                            buildJsonArray {
                                                upcomingTasks.forEach { task ->
                                                    add(task.toTaskSearchPayload())
                                                }
                                            },
                                        )
                                        put("memory", memorySection)
                                        put("events", eventSection)
                                        put("skillStats", skills.toSkillStatsPayload())
                                        put("toolStats", tools.toToolStatsPayload())
                                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "runtime.export",
                                    aliases =
                                        listOf(
                                            "runtime.manifest",
                                            "androidclaw.export",
                                            "androidclaw.manifest",
                                            "system.export",
                                            "system.manifest",
                                        ),
                                    description =
                                        "Return a versioned AndroidClaw runtime manifest with cross-contract capability metadata.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit exportMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val now = clock.instant()
                            val settings = settingsDataStore.settings.first()
                            val selectedProviderAuthState =
                                settings.providerType.toProviderAuthState(
                                    providerSecretStore = providerSecretStore,
                                    clock = clock,
                                )
                            val providerStats =
                                settings.toProviderStatsPayload(
                                    providerSecretStore = providerSecretStore,
                                    clock = clock,
                                )
                            val sessionStats = sessionRepository.getSessionStats()
                            val taskStats = taskRepository.getTaskStats(now)
                            val memorySection =
                                memoryRepository.toRuntimeMemorySectionPayload(
                                    settingsDataStore = settingsDataStore,
                                )
                            val eventSection = eventLogRepository.toRuntimeEventSectionPayload()
                            val skills = bundledSkillsProvider()
                            val tools = toolRegistryProvider().descriptors()
                            val schedulerCapabilities = schedulerCoordinator.capabilities()
                            val omissions = runtimeExportOmissionsPayload()
                            val exportMarkdown =
                                if (includeMarkdown) {
                                    buildRuntimeExportMarkdown(
                                        generatedAt = now,
                                        currentProvider = settings.providerType,
                                        providerAuthState = selectedProviderAuthState,
                                        sessionStats = sessionStats,
                                        taskStats = taskStats,
                                        memorySection = memorySection,
                                        eventSection = eventSection,
                                        skillCount = skills.size,
                                        enabledSkillCount = skills.count { skill -> skill.enabled },
                                        toolCount = tools.size,
                                        availableToolCount =
                                            tools.count { tool ->
                                                tool.availability.status == ToolAvailabilityStatus.Available
                                            },
                                        minimumBackgroundIntervalMinutes =
                                            schedulerCapabilities
                                                .minimumBackgroundInterval
                                                .toMinutes(),
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary = "Exported AndroidClaw runtime manifest with versioned contract metadata.",
                                payload =
                                    buildJsonObject {
                                        put("exportFormat", RUNTIME_EXPORT_FORMAT)
                                        put("exportVersion", RUNTIME_EXPORT_VERSION)
                                        put("format", RUNTIME_EXPORT_FORMAT)
                                        put("version", RUNTIME_EXPORT_VERSION)
                                        put("generatedAtIso", now.toString())
                                        put("requestedSessionId", context.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("requestedTaskRunId", context.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("origin", context.origin.name)
                                        put("runMode", context.runMode?.name?.let(::JsonPrimitive) ?: JsonNull)
                                        put("includeMarkdown", includeMarkdown)
                                        put("secretValuesIncluded", false)
                                        put("apiKeyValuesIncluded", false)
                                        put("oauthTokenValuesIncluded", false)
                                        put("messageBodiesIncluded", false)
                                        put("taskPromptBodiesIncluded", false)
                                        put("skillInstructionBodiesIncluded", false)
                                        put("memoryTextIncluded", false)
                                        put("eventDetailsIncluded", false)
                                        put(
                                            "app",
                                            buildJsonObject {
                                                put("name", "AndroidClaw")
                                                put("runtimeModel", "AndroidNativeHost")
                                                put("singleApkTarget", true)
                                                put("phoneIsHost", true)
                                                put("remoteFirstCompanion", false)
                                                put("desktopHostRequired", false)
                                                put("nodeRuntimeIncluded", false)
                                                put("dockerRuntimeIncluded", false)
                                                put("chromiumRuntimeIncluded", false)
                                                put("baseProductionModuleCount", 1)
                                            },
                                        )
                                        put(
                                            "contractOrder",
                                            buildJsonArray {
                                                add(JsonPrimitive("sessions"))
                                                add(JsonPrimitive("tools"))
                                                add(JsonPrimitive("skills"))
                                                add(JsonPrimitive("automations"))
                                            },
                                        )
                                        put(
                                            "contracts",
                                            buildJsonObject {
                                                put(
                                                    "sessions",
                                                    buildJsonObject {
                                                        put("implemented", true)
                                                        put("persistentHistory", true)
                                                        put("lightweightSummaries", true)
                                                        put("messageBodiesIncluded", false)
                                                        put("providerMetaIncluded", false)
                                                        put("stats", sessionStats.toSessionStatsPayload())
                                                    },
                                                )
                                                put(
                                                    "tools",
                                                    buildJsonObject {
                                                        put("implemented", true)
                                                        put("typedNativeTools", true)
                                                        put("capabilityMetadataAvailable", true)
                                                        put("descriptorsIncluded", false)
                                                        put("inputSchemasIncluded", false)
                                                        put("executionResultsIncluded", false)
                                                        put("stats", tools.toToolStatsPayload())
                                                    },
                                                )
                                                put(
                                                    "skills",
                                                    buildJsonObject {
                                                        put("implemented", true)
                                                        put("skillMdParsing", true)
                                                        put("frontmatterSupported", true)
                                                        put("enableDisableSupported", true)
                                                        put("importSupported", true)
                                                        put("commandDispatchSupported", true)
                                                        put("instructionBodiesIncluded", false)
                                                        put("rawFrontmatterIncluded", false)
                                                        put("secretValuesIncluded", false)
                                                        put("stats", skills.toSkillStatsPayload())
                                                    },
                                                )
                                                put(
                                                    "automations",
                                                    buildJsonObject {
                                                        put("implemented", true)
                                                        put("supportsOnce", true)
                                                        put("supportsInterval", true)
                                                        put("supportsCron", true)
                                                        put("supportsMainSessionMode", true)
                                                        put("supportsIsolatedSessionMode", true)
                                                        put("promptBodiesIncluded", false)
                                                        put("runHistoryIncluded", false)
                                                        put(
                                                            "stats",
                                                            taskStats.toTaskStatsPayload(
                                                                minimumBackgroundIntervalMinutes =
                                                                    schedulerCapabilities
                                                                        .minimumBackgroundInterval
                                                                        .toMinutes(),
                                                            ),
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                        put("provider", settings.providerType.toProviderHandoffPayload(settings, selectedProviderAuthState))
                                        put("providerStats", providerStats)
                                        put(
                                            "scheduler",
                                            buildJsonObject {
                                                put("ready", true)
                                                put(
                                                    "minimumBackgroundIntervalMinutes",
                                                    schedulerCapabilities.minimumBackgroundInterval.toMinutes(),
                                                )
                                                put("supportsExactAlarms", schedulerCapabilities.supportsExactAlarms)
                                                put(
                                                    "supportedKinds",
                                                    buildJsonArray {
                                                        schedulerCapabilities.supportedKinds.forEach { kind ->
                                                            add(JsonPrimitive(kind))
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                        put("memory", memorySection)
                                        put("events", eventSection)
                                        put("omissions", omissions)
                                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "runtime.portability",
                                    aliases =
                                        listOf(
                                            "runtime.backup.plan",
                                            "runtime.restore.plan",
                                            "androidclaw.portability",
                                            "androidclaw.backup.plan",
                                            "system.portability",
                                        ),
                                    description =
                                        "Return a safe AndroidClaw backup and restore playbook across portable runtime contracts.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit portabilityMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val now = clock.instant()
                            val settings = settingsDataStore.settings.first()
                            val sessionStats = sessionRepository.getSessionStats()
                            val taskStats = taskRepository.getTaskStats(now)
                            val memorySection =
                                memoryRepository.toRuntimeMemorySectionPayload(
                                    settingsDataStore = settingsDataStore,
                                )
                            val eventSection = eventLogRepository.toRuntimeEventSectionPayload()
                            val skills = bundledSkillsProvider()
                            val tools = toolRegistryProvider().descriptors()
                            val components = runtimePortabilityComponentsPayload()
                            val exportOrder = runtimePortabilityExportOrderPayload()
                            val restoreOrder = runtimePortabilityRestoreOrderPayload()
                            val portabilityMarkdown =
                                if (includeMarkdown) {
                                    buildRuntimePortabilityMarkdown(
                                        generatedAt = now,
                                        providerType = settings.providerType,
                                        sessionStats = sessionStats,
                                        taskStats = taskStats,
                                        memorySection = memorySection,
                                        eventSection = eventSection,
                                        skillCount = skills.size,
                                        enabledSkillCount = skills.count { skill -> skill.enabled },
                                        toolCount = tools.size,
                                        availableToolCount =
                                            tools.count { tool ->
                                                tool.availability.status == ToolAvailabilityStatus.Available
                                            },
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary = "Prepared AndroidClaw portability playbook across backup and restore tools.",
                                payload =
                                    buildJsonObject {
                                        put("generatedAtIso", now.toString())
                                        put("includeMarkdown", includeMarkdown)
                                        put("backupSupported", true)
                                        put("restoreSupported", true)
                                        put("componentCount", RUNTIME_PORTABILITY_COMPONENT_COUNT)
                                        put("secretValuesIncluded", false)
                                        put("apiKeyValuesIncluded", false)
                                        put("oauthTokenValuesIncluded", false)
                                        put("messageBodiesIncluded", false)
                                        put("taskPromptBodiesIncluded", false)
                                        put("skillInstructionBodiesIncluded", false)
                                        put("memoryTextIncluded", false)
                                        put("eventDetailsIncluded", false)
                                        put("componentPayloadsIncluded", false)
                                        put("desktopRuntimeRequired", false)
                                        put("desktopRuntimeStateIncluded", false)
                                        put("phoneIsHost", true)
                                        put("currentProviderId", settings.providerType.providerId)
                                        put(
                                            "counts",
                                            buildJsonObject {
                                                put("sessionCount", sessionStats.totalSessionCount)
                                                put("activeSessionCount", sessionStats.activeSessionCount)
                                                put("archivedSessionCount", sessionStats.archivedSessionCount)
                                                put("taskCount", taskStats.totalTaskCount)
                                                put("enabledTaskCount", taskStats.enabledTaskCount)
                                                put("taskRunCount", taskStats.totalRunCount)
                                                put("skillCount", skills.size)
                                                put("enabledSkillCount", skills.count { skill -> skill.enabled })
                                                put("toolCount", tools.size)
                                                put(
                                                    "availableToolCount",
                                                    tools.count { tool -> tool.availability.status == ToolAvailabilityStatus.Available },
                                                )
                                                put("memoryAvailable", memorySection["available"] ?: JsonNull)
                                                put("memoryEnabled", memorySection["enabled"] ?: JsonNull)
                                                put("activeMemoryCount", memorySection["activeMemoryCount"] ?: JsonNull)
                                                put("eventLogAvailable", eventSection["available"] ?: JsonNull)
                                                put("eventCount", eventSection["eventCount"] ?: JsonNull)
                                            },
                                        )
                                        put("recommendedExportOrder", exportOrder)
                                        put("recommendedRestoreOrder", restoreOrder)
                                        put("components", components)
                                        put(
                                            "notes",
                                            buildJsonArray {
                                                add(JsonPrimitive("Export runtime.portability or runtime.export first to record the app capability surface."))
                                                add(JsonPrimitive("Export sessions before per-session transcripts so imported messages can target restored session shells."))
                                                add(JsonPrimitive("Import providers and skills before automations that may depend on provider settings or skill dispatch."))
                                                add(JsonPrimitive("Import event diagnostics last because they are troubleshooting context, not runtime prerequisites."))
                                                add(JsonPrimitive("Component import tools require confirm=CONFIRM unless dryRun=true."))
                                                add(JsonPrimitive("Credential values, OAuth tokens, provider metadata, and large content bodies are intentionally omitted by this playbook."))
                                            },
                                        )
                                        put("portabilityMarkdown", portabilityMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "runtime.portability.audit",
                                    aliases =
                                        listOf(
                                            "runtime.backup.audit",
                                            "runtime.restore.audit",
                                            "androidclaw.portability.audit",
                                            "androidclaw.backup.audit",
                                            "system.portability.audit",
                                            "system.backup.audit",
                                        ),
                                    description =
                                        "Audit supplied AndroidClaw export payloads before restore without echoing component bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "exports",
                                                description =
                                                    "Array of export payloads, one export object, or an object keyed by component.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "backup",
                                                description = "Optional object containing an exports field to audit.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit auditMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val sources =
                                when (val parsedSources = arguments.runtimePortabilityAuditSources()) {
                                    is RuntimePortabilityAuditSourcesParseResult.Failure -> return@Entry parsedSources.result
                                    is RuntimePortabilityAuditSourcesParseResult.Success -> parsedSources.sources
                                }
                            val componentSpecs = runtimePortabilityComponentSpecs()
                            val componentByFormat = componentSpecs.associateBy { component -> component.exportFormat }
                            val entries =
                                sources.map { source ->
                                    source.toRuntimePortabilityAuditEntry(componentByFormat = componentByFormat)
                                }
                            val recognizedEntries = entries.filter { entry -> entry.component != null }
                            val unknownEntries = entries.filter { entry -> entry.component == null }
                            val duplicateGroups =
                                recognizedEntries
                                    .groupBy { entry -> requireNotNull(entry.component).key }
                                    .filterValues { componentEntries -> componentEntries.size > 1 }
                            val presentComponentKeys =
                                recognizedEntries
                                    .mapNotNull { entry -> entry.component?.key }
                                    .toSet()
                            val missingComponents = componentSpecs.filter { component -> component.key !in presentComponentKeys }
                            val missingRestoreComponents =
                                componentSpecs.filter { component ->
                                    component.restoreSupported && component.key !in presentComponentKeys
                                }
                            val recommendedRestoreComponents =
                                componentSpecs
                                    .filter { component ->
                                        component.restoreSupported && component.key in presentComponentKeys
                                    }.sortedBy { component -> component.restoreStep ?: Int.MAX_VALUE }
                            val duplicateComponentCount = duplicateGroups.size
                            val status =
                                if (
                                    unknownEntries.isEmpty() &&
                                    missingComponents.isEmpty() &&
                                    duplicateGroups.isEmpty()
                                ) {
                                    "OK"
                                } else {
                                    "WARN"
                                }
                            val auditMarkdown =
                                if (includeMarkdown) {
                                    buildRuntimePortabilityAuditMarkdown(
                                        status = status,
                                        receivedExportCount = sources.size,
                                        recognizedExportCount = recognizedEntries.size,
                                        missingComponents = missingComponents,
                                        duplicateGroups = duplicateGroups,
                                        unknownEntries = unknownEntries,
                                        recommendedRestoreComponents = recommendedRestoreComponents,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (status == "OK") {
                                        "Portability audit found all expected AndroidClaw export components."
                                    } else {
                                        "Portability audit found ${missingComponents.size} missing, " +
                                            "$duplicateComponentCount duplicate, and ${unknownEntries.size} unknown component issue(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("auditFormat", RUNTIME_PORTABILITY_AUDIT_FORMAT)
                                        put("auditVersion", RUNTIME_PORTABILITY_AUDIT_VERSION)
                                        put("status", status)
                                        put("includeMarkdown", includeMarkdown)
                                        put("componentCount", componentSpecs.size)
                                        put("receivedExportCount", sources.size)
                                        put("recognizedExportCount", recognizedEntries.size)
                                        put("unknownExportCount", unknownEntries.size)
                                        put("missingComponentCount", missingComponents.size)
                                        put("duplicateComponentCount", duplicateComponentCount)
                                        put("completeBackup", status == "OK")
                                        put(
                                            "restoreReady",
                                            missingRestoreComponents.isEmpty() &&
                                                unknownEntries.isEmpty() &&
                                                duplicateGroups.isEmpty(),
                                        )
                                        put("secretValuesIncluded", false)
                                        put("apiKeyValuesIncluded", false)
                                        put("oauthTokenValuesIncluded", false)
                                        put("componentPayloadsIncluded", false)
                                        put("exportPayloadsIncluded", false)
                                        put(
                                            "presentComponents",
                                            buildJsonArray {
                                                recognizedEntries.forEach { entry ->
                                                    add(entry.toRuntimePortabilityAuditPresentPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "missingComponents",
                                            buildJsonArray {
                                                missingComponents.forEach { component ->
                                                    add(component.toRuntimePortabilityAuditComponentPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "duplicateComponents",
                                            buildJsonArray {
                                                duplicateGroups.forEach { (componentKey, componentEntries) ->
                                                    add(componentEntries.toRuntimePortabilityDuplicatePayload(componentKey))
                                                }
                                            },
                                        )
                                        put(
                                            "unknownExports",
                                            buildJsonArray {
                                                unknownEntries.forEach { entry ->
                                                    add(entry.toRuntimePortabilityUnknownPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "recommendedMissingExports",
                                            buildJsonArray {
                                                missingComponents.forEach { component ->
                                                    add(
                                                        runtimePortabilityStepPayload(
                                                            step = component.exportStep,
                                                            toolName = component.exportTool,
                                                            format = component.exportFormat,
                                                            scope = component.scope,
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        put(
                                            "recommendedRestoreOrder",
                                            buildJsonArray {
                                                recommendedRestoreComponents.forEach { component ->
                                                    add(
                                                        runtimePortabilityStepPayload(
                                                            step = requireNotNull(component.restoreStep),
                                                            toolName = requireNotNull(component.importTool),
                                                            format = requireNotNull(component.importFormat),
                                                            scope = component.scope,
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        put("auditMarkdown", auditMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "runtime.doctor",
                                    aliases =
                                        listOf(
                                            "health.doctor",
                                            "runtime.check",
                                            "androidclaw.doctor",
                                            "system.doctor",
                                        ),
                                    description = "Return actionable runtime readiness diagnostics without mutating state or exposing secrets.",
                                ),
                        ) { context, _ ->
                            val now = clock.instant()
                            val settings = settingsDataStore.settings.first()
                            val selectedProviderAuthState =
                                settings.providerType.toProviderAuthState(
                                    providerSecretStore = providerSecretStore,
                                    clock = clock,
                                )
                            val sessionStats = sessionRepository.getSessionStats()
                            val taskStats = taskRepository.getTaskStats(now)
                            val memorySettings = settingsDataStore.memorySettingsSnapshot()
                            val skills = bundledSkillsProvider()
                            val tools = toolRegistryProvider().descriptors()
                            val schedulerCapabilities = schedulerCoordinator.capabilities()
                            val issues =
                                buildRuntimeDoctorIssues(
                                    settings = settings,
                                    providerAuthState = selectedProviderAuthState,
                                    sessionStats = sessionStats,
                                    taskStats = taskStats,
                                    memoryEnabled = memorySettings.enabled,
                                    memoryRepositoryAvailable = memoryRepository != null,
                                    eventLogRepositoryAvailable = eventLogRepository != null,
                                    skills = skills,
                                    tools = tools,
                                )
                            val status = issues.toRuntimeDoctorStatus()
                            ToolExecutionResult.success(
                                summary =
                                    when (status) {
                                        "OK" -> "Runtime doctor found no readiness issues."
                                        "WARN" -> "Runtime doctor found ${issues.size} non-blocking issue(s)."
                                        else -> "Runtime doctor found ${issues.count { issue -> issue.severity == "Error" }} blocking issue(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("generatedAtIso", now.toString())
                                        put("status", status)
                                        put("issueCount", issues.size)
                                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                                        put("secretValuesIncluded", false)
                                        put("requestedSessionId", context.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                        put(
                                            "issues",
                                            buildJsonArray {
                                                issues.forEach { issue ->
                                                    add(issue.toRuntimeDoctorPayload())
                                                }
                                            },
                                        )
                                        put(
                                            "checks",
                                            buildJsonObject {
                                                put(
                                                    "provider",
                                                    buildJsonObject {
                                                        put("providerId", settings.providerType.providerId)
                                                        put("displayName", settings.providerType.displayName)
                                                        put("authStatus", selectedProviderAuthState.status)
                                                        put("requiresCredential", settings.providerType.requiresApiKey || settings.providerType.usesOpenAiCodexOAuth)
                                                        put("requiresRemoteSettings", settings.providerType.requiresRemoteSettings)
                                                        put(
                                                            "endpointSettings",
                                                            if (settings.providerType.requiresRemoteSettings) {
                                                                val endpointSettings = settings.endpointSettings(settings.providerType)
                                                                buildJsonObject {
                                                                    put("baseUrlConfigured", endpointSettings.baseUrl.isNotBlank())
                                                                    put("modelIdConfigured", endpointSettings.modelId.isNotBlank())
                                                                    put("timeoutSeconds", endpointSettings.timeoutSeconds)
                                                                }
                                                            } else {
                                                                JsonNull
                                                            },
                                                        )
                                                    },
                                                )
                                                put("sessions", sessionStats.toRuntimeDoctorSessionCheckPayload())
                                                put("automations", taskStats.toRuntimeDoctorTaskCheckPayload())
                                                put(
                                                    "memory",
                                                    buildJsonObject {
                                                        put("enabled", memorySettings.enabled)
                                                        put("repositoryAvailable", memoryRepository != null)
                                                        put("ownerUserIdIncluded", false)
                                                    },
                                                )
                                                put(
                                                    "skills",
                                                    buildJsonObject {
                                                        put("skillCount", skills.size)
                                                        put("enabledSkillCount", skills.count { skill -> skill.enabled })
                                                        put("parseErrorCount", skills.count { skill -> skill.parseError != null })
                                                        put("ineligibleSkillCount", skills.count { skill -> skill.eligibility.status != SkillEligibilityStatus.Eligible })
                                                    },
                                                )
                                                put(
                                                    "tools",
                                                    buildJsonObject {
                                                        put("toolCount", tools.size)
                                                        put("availableToolCount", tools.count { tool -> tool.availability.status == ToolAvailabilityStatus.Available })
                                                        put("unavailableToolCount", tools.count { tool -> tool.availability.status == ToolAvailabilityStatus.Unavailable })
                                                        put("permissionRequiredToolCount", tools.count { tool -> tool.availability.status == ToolAvailabilityStatus.PermissionRequired })
                                                        put("foregroundRequiredToolCount", tools.count { tool -> tool.foregroundRequired })
                                                    },
                                                )
                                                put(
                                                    "scheduler",
                                                    buildJsonObject {
                                                        put("minimumBackgroundIntervalMinutes", schedulerCapabilities.minimumBackgroundInterval.toMinutes())
                                                        put("supportsExactAlarms", schedulerCapabilities.supportsExactAlarms)
                                                        put(
                                                            "supportedKinds",
                                                            buildJsonArray {
                                                                schedulerCapabilities.supportedKinds.forEach { kind ->
                                                                    add(JsonPrimitive(kind))
                                                                }
                                                            },
                                                        )
                                                    },
                                                )
                                                put(
                                                    "events",
                                                    buildJsonObject {
                                                        put("repositoryAvailable", eventLogRepository != null)
                                                    },
                                                )
                                            },
                                        )
                                    },
                            )
                        },
                    )
    }
