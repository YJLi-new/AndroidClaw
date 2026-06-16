package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
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
import ai.androidclaw.data.repository.MESSAGE_REFERENCE_ID_MAX_CHARS
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.TaskRepository
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.TaskSchedule
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillConfigField
import ai.androidclaw.runtime.skills.SkillConfigurationSnapshot
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

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
                                    name = "skills.secret.clear",
                                    aliases =
                                        listOf(
                                            "skill.secret.clear",
                                            "skills.secrets.clear",
                                            "skill.secrets.clear",
                                            "skills.secret.delete",
                                            "skill.secret.delete",
                                        ),
                                    description = "Clear one saved skill secret without reading or returning the secret value.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Skill id, key, or display name.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "envName",
                                                required = false,
                                                description = "Declared secret environment variable to clear.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "confirm",
                                                description = "Must equal CONFIRM.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.secret.clear",
                                        summary = "skills.secret.clear requires a non-empty skillId.",
                                    )
                            val envName =
                                arguments.optionalText("envName")
                                    ?: arguments.optionalText("secretName")
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.secret.clear",
                                        summary = "skills.secret.clear requires a non-empty envName.",
                                        field = "envName",
                                    )
                            if (arguments.optionalText("confirm") != "CONFIRM") {
                                return@Entry ToolExecutionResult.failure(
                                    summary = "skills.secret.clear requires confirm=CONFIRM.",
                                    errorCode = "CONFIRMATION_REQUIRED",
                                    payload =
                                        buildJsonObject {
                                            put("errorCode", "CONFIRMATION_REQUIRED")
                                            put("toolName", "skills.secret.clear")
                                            put("field", "confirm")
                                        },
                                )
                            }
                            val skills = bundledSkillsProvider()
                            val skill =
                                skills.findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.secret.clear", skillId = identifier)
                            if (!skill.secretStatuses.containsKey(envName)) {
                                return@Entry skillSecretNotFoundResult(
                                    toolName = "skills.secret.clear",
                                    skillId = skill.id,
                                    envName = envName,
                                )
                            }
                            val updatedConfiguration = skillSecretClearer(skill, envName)
                            ToolExecutionResult.success(
                                summary = "Cleared saved secret $envName for skill ${skill.displayName}.",
                                payload =
                                    buildJsonObject {
                                        put("skill", skill.toSkillSearchPayload())
                                        put("envName", envName)
                                        put("cleared", true)
                                        put("configuration", updatedConfiguration.toSkillConfigurationPayload())
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.config.update",
                                    aliases =
                                        listOf(
                                            "skill.config.update",
                                            "skills.configuration.update",
                                            "skill.configuration.update",
                                            "skills.config.set",
                                            "skill.config.set",
                                        ),
                                    description = "Set or clear one non-secret config value for a skill.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Skill id, key, or display name.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "configPath",
                                                required = false,
                                                description = "Declared config path to update. Also accepts path.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "value",
                                                description = "New non-secret config value. Omit when clear=true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "clear",
                                                description = "Set true to clear the config value.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.config.update",
                                        summary = "skills.config.update requires a non-empty skillId.",
                                    )
                            val configPath =
                                arguments.optionalText("configPath")
                                    ?: arguments.optionalText("path")
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.config.update",
                                        summary = "skills.config.update requires a non-empty configPath.",
                                        field = "configPath",
                                    )
                            val skills = bundledSkillsProvider()
                            val skill =
                                skills.findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.config.update", skillId = identifier)
                            if (!skill.configStatuses.containsKey(configPath)) {
                                return@Entry skillConfigNotFoundResult(
                                    toolName = "skills.config.update",
                                    skillId = skill.id,
                                    configPath = configPath,
                                )
                            }
                            val clear = arguments.optionalBoolean("clear", defaultValue = false)
                            val value =
                                if (clear) {
                                    null
                                } else {
                                    arguments.optionalText("value")
                                        ?: return@Entry invalidSkillArguments(
                                            toolName = "skills.config.update",
                                            summary = "skills.config.update requires a non-empty value unless clear=true.",
                                            field = "value",
                                        )
                                }
                            val updatedConfiguration =
                                skillConfigurationUpdater(
                                    skill,
                                    configPath,
                                    value,
                                )
                            ToolExecutionResult.success(
                                summary =
                                    if (clear) {
                                        "Cleared config $configPath for skill ${skill.displayName}."
                                    } else {
                                        "Updated config $configPath for skill ${skill.displayName}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("skill", skill.toSkillSearchPayload())
                                        put("configPath", configPath)
                                        put("cleared", clear)
                                        put("configuration", updatedConfiguration.toSkillConfigurationPayload())
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.config.get",
                                    aliases =
                                        listOf(
                                            "skill.config.get",
                                            "skills.configuration.get",
                                            "skill.configuration.get",
                                            "skills.config",
                                            "skill.config",
                                        ),
                                    description = "Return non-secret configuration status for one skill.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Skill id, key, or display name.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.config.get",
                                        summary = "skills.config.get requires a non-empty skillId.",
                                    )
                            val skills = bundledSkillsProvider()
                            val skill =
                                skills.findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.config.get", skillId = identifier)
                            val configuration = skillConfigurationReader(skill)
                            ToolExecutionResult.success(
                                summary = "Loaded configuration status for skill ${skill.displayName}.",
                                payload =
                                    buildJsonObject {
                                        put("skill", skill.toSkillSearchPayload())
                                        put("configuration", configuration.toSkillConfigurationPayload())
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.refresh",
                                    aliases = listOf("skill.refresh", "skills.rescan", "skill.rescan"),
                                    description = "Force reload bundled, local, and workspace skill inventory.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "sessionId",
                                                description = "Workspace session id to include. Defaults to the active session.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "forceRefresh",
                                                description = "Set false to reuse caches when available. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { context, arguments ->
                            val sessionId = arguments.optionalText("sessionId") ?: context.sessionId
                            val forceRefresh = arguments.optionalBoolean("forceRefresh", defaultValue = true)
                            val skills =
                                skillInventoryRefresher(
                                    sessionId,
                                    forceRefresh,
                                )
                            ToolExecutionResult.success(
                                summary = "Reloaded ${skills.size} skill(s).",
                                payload =
                                    buildJsonObject {
                                        put("skillCount", skills.size)
                                        put("sessionId", sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                        put("forceRefresh", forceRefresh)
                                        put(
                                            "skills",
                                            buildJsonArray {
                                                skills.forEach { skill ->
                                                    add(skill.toSkillSearchPayload())
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
                                    name = "skills.stats",
                                    aliases = listOf("skill.stats"),
                                    description = "Return aggregate skill inventory statistics without loading SKILL.md instructions.",
                                ),
                        ) { _, _ ->
                            val skills = bundledSkillsProvider()
                            ToolExecutionResult.success(
                                summary = "Loaded stats for ${skills.size} skill(s).",
                                payload = skills.toSkillStatsPayload(),
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.get",
                                    aliases = listOf("skill.get"),
                                    description = "Return detailed metadata and instructions for one bundled skill.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Skill id, key, or display name.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeInstructions",
                                                description = "true to include bounded SKILL.md instructions. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.get",
                                        summary = "skills.get requires a non-empty skillId.",
                                    )
                            val skills = bundledSkillsProvider()
                            val skill =
                                skills.findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.get", skillId = identifier)
                            val includeInstructions = arguments.optionalBoolean("includeInstructions", defaultValue = true)
                            ToolExecutionResult.success(
                                summary = "Loaded skill ${skill.displayName}.",
                                payload =
                                    buildJsonObject {
                                        put("skill", skill.toSkillDetailPayload(includeInstructions = includeInstructions))
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.search",
                                    aliases = listOf("skill.search"),
                                    description = "Search skill names, descriptions, and instructions.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "query",
                                                required = true,
                                                description = "Skill text to search for.",
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
                                        summary = "skills.search requires a non-empty query.",
                                        errorCode = "INVALID_ARGUMENTS",
                                        payload =
                                            buildJsonObject {
                                                put("errorCode", "INVALID_ARGUMENTS")
                                                put("toolName", "skills.search")
                                                put("field", "query")
                                            },
                                    )
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_SEARCH_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_SEARCH_MAX_LIMIT)
                            val matches =
                                bundledSkillsProvider()
                                    .filter { skill -> skill.matchesSkillQuery(query) }
                                    .take(limit)
                            ToolExecutionResult.success(
                                summary =
                                    if (matches.isEmpty()) {
                                        "No skills matched \"$query\"."
                                    } else {
                                        "Found ${matches.size} skill(s) matching \"$query\"."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("query", query)
                                        put("resultCount", matches.size)
                                        put(
                                            "skills",
                                            buildJsonArray {
                                                matches.forEach { skill ->
                                                    add(skill.toSkillSearchPayload())
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
                                skillToggleDescriptor(
                                    name = "skills.enable",
                                    aliases = listOf("skill.enable"),
                                    description = "Enable a skill by id, key, or display name.",
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.enable",
                                        summary = "skills.enable requires a non-empty skillId.",
                                    )
                            val skill =
                                bundledSkillsProvider().findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.enable", skillId = identifier)
                            skillEnabledUpdater(skill.id, true)
                            val reloadedSkill =
                                bundledSkillsProvider().findByIdentifier(skill.id)
                                    ?: skill.copy(enabled = true)
                            ToolExecutionResult.success(
                                summary = "Enabled skill ${reloadedSkill.displayName}.",
                                payload =
                                    buildJsonObject {
                                        put("skill", reloadedSkill.toSkillDetailPayload(includeInstructions = false))
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                skillToggleDescriptor(
                                    name = "skills.disable",
                                    aliases = listOf("skill.disable"),
                                    description = "Disable a skill by id, key, or display name.",
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.disable",
                                        summary = "skills.disable requires a non-empty skillId.",
                                    )
                            val skill =
                                bundledSkillsProvider().findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.disable", skillId = identifier)
                            skillEnabledUpdater(skill.id, false)
                            val reloadedSkill =
                                bundledSkillsProvider().findByIdentifier(skill.id)
                                    ?: skill.copy(enabled = false)
                            ToolExecutionResult.success(
                                summary = "Disabled skill ${reloadedSkill.displayName}.",
                                payload =
                                    buildJsonObject {
                                        put("skill", reloadedSkill.toSkillDetailPayload(includeInstructions = false))
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

private fun providerToolEntries(
    settingsDataStore: SettingsDataStore,
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.list",
                    aliases = listOf("provider.list"),
                    description = "List available model providers and current non-secret endpoint settings.",
                ),
        ) { _, _ ->
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Found ${ProviderType.entries.size} provider(s).",
                payload =
                    buildJsonObject {
                        put("currentProviderId", settings.providerType.providerId)
                        put("currentProviderDisplayName", settings.providerType.displayName)
                        put("providerCount", ProviderType.entries.size)
                        put(
                            "providers",
                            buildJsonArray {
                                ProviderType.entries.forEach { providerType ->
                                    add(providerType.toProviderPayload(settings))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.current",
                    aliases = listOf("provider.current", "providers.status", "provider.status"),
                    description = "Return the selected model provider and its current non-secret endpoint settings.",
                ),
        ) { _, _ ->
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Current provider is ${settings.providerType.displayName}.",
                payload =
                    buildJsonObject {
                        put("provider", settings.providerType.toProviderPayload(settings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.select",
                    aliases = listOf("provider.select", "providers.use", "provider.use"),
                    description = "Select the active model provider by id, storage value, or display name.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "providers.select requires a non-empty providerId.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.select")
                                put("field", "providerId")
                            },
                    )
            val providerType =
                ProviderType.entries.firstOrNull { providerType ->
                    providerType.matchesProviderIdentifier(identifier)
                } ?: return@Entry ToolExecutionResult.failure(
                    summary = "Provider $identifier was not found.",
                    errorCode = "PROVIDER_NOT_FOUND",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_FOUND")
                            put("toolName", "providers.select")
                            put("providerId", identifier)
                        },
                )
            settingsDataStore.setProviderType(providerType)
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Selected provider ${providerType.displayName}.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(settings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.configure",
                    aliases = listOf("provider.configure", "providers.update", "provider.update"),
                    description = "Update non-secret endpoint settings for a configurable model provider.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "baseUrl",
                                description = "Provider base URL.",
                            ),
                            ToolArgumentSpec(
                                name = "modelId",
                                description = "Provider model id.",
                            ),
                            ToolArgumentSpec(
                                name = "timeoutSeconds",
                                description = "Provider timeout in seconds.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.configure")
                                put("providerId", identifier)
                            },
                    )
                }
            if (!providerType.requiresRemoteSettings) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Provider ${providerType.displayName} does not have remote endpoint settings.",
                    errorCode = "PROVIDER_NOT_CONFIGURABLE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_CONFIGURABLE")
                            put("toolName", "providers.configure")
                            put("providerId", providerType.providerId)
                        },
                )
            }
            val hasPatch =
                arguments.optionalText("baseUrl") != null ||
                    arguments.optionalText("modelId") != null ||
                    arguments.optionalText("timeoutSeconds") != null
            if (!hasPatch) {
                return@Entry ToolExecutionResult.failure(
                    summary = "providers.configure requires baseUrl, modelId, or timeoutSeconds.",
                    errorCode = "INVALID_ARGUMENTS",
                    payload =
                        buildJsonObject {
                            put("errorCode", "INVALID_ARGUMENTS")
                            put("toolName", "providers.configure")
                            put("field", "baseUrl|modelId|timeoutSeconds")
                        },
                )
            }
            val existingEndpoint = settings.endpointSettings(providerType)
            val timeoutSeconds =
                try {
                    arguments.optionalProviderTimeoutSeconds() ?: existingEndpoint.timeoutSeconds
                } catch (error: IllegalArgumentException) {
                    return@Entry ToolExecutionResult.failure(
                        summary = error.message ?: "providers.configure received an invalid timeoutSeconds.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.configure")
                                put("field", "timeoutSeconds")
                            },
                    )
                }
            val updatedSettings =
                settings.withEndpointSettings(
                    providerType = providerType,
                    settings =
                        ProviderEndpointSettings(
                            baseUrl = arguments.optionalText("baseUrl") ?: existingEndpoint.baseUrl,
                            modelId = arguments.optionalText("modelId") ?: existingEndpoint.modelId,
                            timeoutSeconds = timeoutSeconds,
                        ),
                )
            settingsDataStore.saveProviderSettings(updatedSettings)
            val reloadedSettings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Updated provider ${providerType.displayName} settings.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(reloadedSettings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.reset",
                    aliases = listOf("provider.reset", "providers.defaults", "provider.defaults"),
                    description = "Reset a configurable provider's non-secret endpoint settings to defaults.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.reset")
                                put("providerId", identifier)
                            },
                    )
                }
            if (!providerType.requiresRemoteSettings) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Provider ${providerType.displayName} does not have remote endpoint settings.",
                    errorCode = "PROVIDER_NOT_CONFIGURABLE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_CONFIGURABLE")
                            put("toolName", "providers.reset")
                            put("providerId", providerType.providerId)
                        },
                )
            }
            settingsDataStore.saveProviderSettings(
                settings.withEndpointSettings(
                    providerType = providerType,
                    settings = providerType.defaultEndpointSettings(),
                ),
            )
            val reloadedSettings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Reset provider ${providerType.displayName} settings to defaults.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(reloadedSettings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.auth.status",
                    aliases = listOf("provider.auth.status", "providers.auth", "provider.auth"),
                    description = "Report non-secret authentication status for one or all providers.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Omit to list all providers.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providers =
                if (identifier == null) {
                    ProviderType.entries
                } else {
                    listOf(
                        ProviderType.entries.firstOrNull { providerType ->
                            providerType.matchesProviderIdentifier(identifier)
                        } ?: return@Entry ToolExecutionResult.failure(
                            summary = "Provider $identifier was not found.",
                            errorCode = "PROVIDER_NOT_FOUND",
                            payload =
                                buildJsonObject {
                                    put("errorCode", "PROVIDER_NOT_FOUND")
                                    put("toolName", "providers.auth.status")
                                    put("providerId", identifier)
                                },
                        ),
                    )
                }
            val authPayloads =
                providers.map { providerType ->
                    providerType.toProviderAuthPayload(
                        settings = settings,
                        providerSecretStore = providerSecretStore,
                        clock = clock,
                    )
                }
            ToolExecutionResult.success(
                summary =
                    if (identifier == null) {
                        "Loaded authentication status for ${providers.size} provider(s)."
                    } else {
                        "Loaded authentication status for ${providers.single().displayName}."
                    },
                payload =
                    buildJsonObject {
                        put("currentProviderId", settings.providerType.providerId)
                        put("secretStatusAvailable", providerSecretStore != null)
                        put(
                            "providers",
                            buildJsonArray {
                                authPayloads.forEach { payload ->
                                    add(payload)
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.stats",
                    aliases = listOf("provider.stats"),
                    description = "Summarize provider inventory, endpoint customization, and non-secret auth status.",
                ),
        ) { _, _ ->
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Summarized ${ProviderType.entries.size} provider(s).",
                payload =
                    settings.toProviderStatsPayload(
                        providerSecretStore = providerSecretStore,
                        clock = clock,
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.get",
                    aliases = listOf("provider.get"),
                    description = "Return one provider by id, storage value, or display name.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "providers.get requires a non-empty providerId.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.get")
                                put("field", "providerId")
                            },
                    )
            val providerType =
                ProviderType.entries.firstOrNull { providerType ->
                    providerType.matchesProviderIdentifier(identifier)
                } ?: return@Entry ToolExecutionResult.failure(
                    summary = "Provider $identifier was not found.",
                    errorCode = "PROVIDER_NOT_FOUND",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_FOUND")
                            put("toolName", "providers.get")
                            put("providerId", identifier)
                        },
                )
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Loaded provider ${providerType.displayName}.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(settings))
                    },
            )
        },
    )

private fun toolDiscoveryEntries(toolRegistryProvider: () -> ToolRegistry): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.stats",
                    aliases = listOf("tool.stats"),
                    description = "Summarize typed native tool registry metadata without returning schemas.",
                ),
        ) { _, _ ->
            val tools = toolRegistryProvider().descriptors()
            ToolExecutionResult.success(
                summary = "Summarized ${tools.size} tool(s).",
                payload = tools.toToolStatsPayload(),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.list",
                    aliases = listOf("tool.list"),
                    description = "List typed native tools with current availability and argument metadata.",
                ),
        ) { _, _ ->
            val tools = toolRegistryProvider().descriptors()
            ToolExecutionResult.success(
                summary = "Found ${tools.size} tool(s).",
                payload =
                    buildJsonObject {
                        put("toolCount", tools.size)
                        put(
                            "tools",
                            buildJsonArray {
                                tools.forEach { tool ->
                                    add(tool.toToolDescriptorPayload(includeInputSchema = false))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.get",
                    aliases = listOf("tool.get"),
                    description = "Return one typed native tool descriptor by canonical name or alias.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.get",
                        summary = "tools.get requires a non-empty toolName.",
                        field = "toolName",
                    )
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            ToolExecutionResult.success(
                summary = "Loaded tool ${tool.name}.",
                payload =
                    buildJsonObject {
                        put("tool", tool.toToolDescriptorPayload(includeInputSchema = true))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.resolve",
                    aliases = listOf("tool.resolve", "tools.alias", "tool.alias"),
                    description = "Resolve a requested tool name or alias to its canonical descriptor.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias to resolve.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.resolve",
                        summary = "tools.resolve requires a non-empty toolName.",
                        field = "toolName",
                    )
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            ToolExecutionResult.success(
                summary =
                    if (tool.name == requestedToolName) {
                        "Resolved canonical tool ${tool.name}."
                    } else {
                        "Resolved alias $requestedToolName to ${tool.name}."
                    },
                payload =
                    buildJsonObject {
                        put("requestedName", requestedToolName)
                        put("canonicalName", tool.name)
                        put("isAlias", requestedToolName != tool.name)
                        put("aliasCount", tool.aliases.size)
                        put(
                            "matchedAlias",
                            if (requestedToolName != tool.name && requestedToolName in tool.aliases) {
                                JsonPrimitive(requestedToolName)
                            } else {
                                JsonNull
                            },
                        )
                        put("availabilityStatus", tool.availability.status.name)
                        put("availabilityReason", tool.availability.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("description", tool.description)
                        put(
                            "aliases",
                            buildJsonArray {
                                tool.aliases.forEach { alias -> add(JsonPrimitive(alias)) }
                            },
                        )
                        put(
                            "arguments",
                            buildJsonArray {
                                tool.arguments.forEach { argument ->
                                    add(
                                        buildJsonObject {
                                            put("name", argument.name)
                                            put("required", argument.required)
                                            put("description", argument.description)
                                        },
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
                    name = "tools.validate",
                    aliases =
                        listOf(
                            "tool.validate",
                            "tools.check",
                            "tool.check",
                            "tools.dry_run",
                            "tool.dry_run",
                        ),
                    description = "Dry-run a tool invocation by validating target, arguments, and availability without executing it.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias to validate.",
                            ),
                            ToolArgumentSpec(
                                name = "arguments",
                                description = "JSON object containing candidate arguments for the target tool.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.validate",
                        summary = "tools.validate requires a non-empty toolName.",
                        field = "toolName",
                    )
            val candidateArguments =
                when (val nestedArguments = arguments["arguments"]) {
                    null ->
                        buildJsonObject {
                            arguments.forEach { (field, value) ->
                                if (field !in TOOL_VALIDATE_RESERVED_ARGUMENT_FIELDS) {
                                    put(field, value)
                                }
                            }
                        }
                    is JsonObject -> nestedArguments
                    else ->
                        return@Entry invalidToolDiscoveryArguments(
                            toolName = "tools.validate",
                            summary = "tools.validate requires arguments to be a JSON object when provided.",
                            field = "arguments",
                        )
                }
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            val declaredArguments = tool.arguments.map { argument -> argument.name }.toSet()
            val providedArguments = candidateArguments.keys.sorted()
            val missingRequiredArguments =
                tool.arguments
                    .filter { argument -> argument.required && !candidateArguments.hasProvidedToolArgument(argument.name) }
                    .map { argument -> argument.name }
            val unknownArguments =
                providedArguments.filterNot { argumentName -> argumentName in declaredArguments }
            val validArguments = missingRequiredArguments.isEmpty()
            val availableNow = tool.availability.status == ToolAvailabilityStatus.Available
            val readyToExecute = validArguments && availableNow
            ToolExecutionResult.success(
                summary =
                    when {
                        readyToExecute -> "Tool ${tool.name} would pass registry validation and availability checks."
                        !validArguments -> "Tool ${tool.name} is missing required arguments."
                        else -> "Tool ${tool.name} is not currently available."
                    },
                payload =
                    buildJsonObject {
                        put("requestedName", requestedToolName)
                        put("canonicalName", tool.name)
                        put("isAlias", requestedToolName != tool.name)
                        put("validArguments", validArguments)
                        put("availableNow", availableNow)
                        put("readyToExecute", readyToExecute)
                        put("wouldStartExecution", readyToExecute)
                        put("semanticValidationIncluded", false)
                        put("availabilityStatus", tool.availability.status.name)
                        put("availabilityReason", tool.availability.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("declaredArgumentCount", tool.arguments.size)
                        put("requiredArgumentCount", tool.arguments.count { argument -> argument.required })
                        put("providedArgumentCount", providedArguments.size)
                        put("providedArguments", providedArguments.toToolStringArrayPayload())
                        put("missingRequiredArguments", missingRequiredArguments.toToolStringArrayPayload())
                        put("unknownArguments", unknownArguments.toToolStringArrayPayload())
                        put(
                            "argumentRequirements",
                            buildJsonArray {
                                tool.arguments.forEach { argument ->
                                    add(
                                        buildJsonObject {
                                            put("name", argument.name)
                                            put("required", argument.required)
                                            put("provided", argument.name in candidateArguments)
                                            put("description", argument.description)
                                        },
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
                    name = "tools.arguments",
                    aliases =
                        listOf(
                            "tool.arguments",
                            "tools.by_argument",
                            "tool.by_argument",
                            "tools.arg",
                            "tool.arg",
                        ),
                    description = "Summarize argument names or list tools that declare one argument.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "argumentName",
                                required = false,
                                description = "Optional argument name to filter by. Alias: name.",
                            ),
                            ToolArgumentSpec(
                                name = "requiredOnly",
                                description = "Set true to include only tools where the matched argument is required.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedArgumentName = arguments.optionalText("argumentName") ?: arguments.optionalText("name")
            val requiredOnly = arguments.optionalBoolean("requiredOnly")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_ARGUMENTS_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_ARGUMENTS_MAX_LIMIT)
            if (requestedArgumentName == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized declared arguments across ${tools.size} tool(s).",
                    payload =
                        tools.toToolArgumentStatsPayload(
                            limit = limit,
                            requiredOnly = requiredOnly,
                        ),
                )
            }

            val matchingTools =
                tools.mapNotNull { tool ->
                    val matchingArguments =
                        tool.arguments.filter { argument ->
                            argument.name.equals(requestedArgumentName, ignoreCase = true) &&
                                (!requiredOnly || argument.required)
                        }
                    if (matchingArguments.isEmpty()) {
                        null
                    } else {
                        tool to matchingArguments
                    }
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools declare argument $requestedArgumentName."
                    } else {
                        "Found ${limitedMatches.size} tool(s) declaring argument $requestedArgumentName."
                    },
                payload =
                    buildJsonObject {
                        put("argumentName", requestedArgumentName)
                        put("requiredOnly", requiredOnly)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { (tool, matchingArguments) ->
                                    add(tool.toToolArgumentMatchPayload(matchingArguments))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.availability",
                    aliases =
                        listOf(
                            "tool.availability",
                            "tools.status",
                            "tool.status",
                            "tools.readiness",
                            "tool.readiness",
                        ),
                    description = "Summarize tool availability or list tools by one availability status.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "status",
                                required = false,
                                description =
                                    "Optional availability status: available, unavailable, permission_required, " +
                                        "foreground_required, or disabled_by_config.",
                            ),
                            ToolArgumentSpec(
                                name = "foregroundRequiredOnly",
                                description = "Set true to include only foreground-required tools.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedStatusText = arguments.optionalText("status")
            val requestedStatus =
                requestedStatusText?.toToolAvailabilityStatusOrNull()
                    ?: if (requestedStatusText == null) {
                        null
                    } else {
                        return@Entry invalidToolDiscoveryArguments(
                            toolName = "tools.availability",
                            summary = "tools.availability received an unknown availability status.",
                            field = "status",
                        )
                    }
            val foregroundRequiredOnly = arguments.optionalBoolean("foregroundRequiredOnly")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_AVAILABILITY_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_AVAILABILITY_MAX_LIMIT)

            if (requestedStatus == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized availability across ${tools.size} tool(s).",
                    payload =
                        tools.toToolAvailabilityStatsPayload(
                            limit = limit,
                            foregroundRequiredOnly = foregroundRequiredOnly,
                        ),
                )
            }

            val matchingTools =
                tools.filter { tool ->
                    tool.availability.status == requestedStatus &&
                        (!foregroundRequiredOnly || tool.foregroundRequired)
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools currently have availability status ${requestedStatus.name}."
                    } else {
                        "Found ${limitedMatches.size} tool(s) with availability status ${requestedStatus.name}."
                    },
                payload =
                    buildJsonObject {
                        put("availabilityStatus", requestedStatus.name)
                        put("foregroundRequiredOnly", foregroundRequiredOnly)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { tool ->
                                    add(tool.toToolAvailabilityMatchPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.permissions",
                    aliases =
                        listOf(
                            "tool.permissions",
                            "tools.permission",
                            "tool.permission",
                            "tools.by_permission",
                            "tool.by_permission",
                        ),
                    description = "Summarize Android permission requirements or list tools requiring one permission.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "permission",
                                required = false,
                                description = "Optional permission name, suffix, or display name to filter by. Alias: name.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedPermission = arguments.optionalText("permission") ?: arguments.optionalText("name")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_PERMISSIONS_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_PERMISSIONS_MAX_LIMIT)
            if (requestedPermission == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized permission requirements across ${tools.size} tool(s).",
                    payload = tools.toToolPermissionDiscoveryPayload(limit),
                )
            }

            val matchingTools =
                tools.mapNotNull { tool ->
                    val matchingPermissions =
                        tool.requiredPermissions.filter { permission ->
                            permission.matchesPermissionQuery(requestedPermission)
                        }
                    if (matchingPermissions.isEmpty()) {
                        null
                    } else {
                        tool to matchingPermissions
                    }
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools require permission $requestedPermission."
                    } else {
                        "Found ${limitedMatches.size} tool(s) requiring permission $requestedPermission."
                    },
                payload =
                    buildJsonObject {
                        put("permission", requestedPermission)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { (tool, matchingPermissions) ->
                                    add(tool.toToolPermissionMatchPayload(matchingPermissions))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.namespaces",
                    aliases =
                        listOf(
                            "tool.namespaces",
                            "tools.namespace",
                            "tool.namespace",
                            "tools.groups",
                            "tool.groups",
                        ),
                    description = "Summarize canonical tool namespaces or list tools in one namespace.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "namespace",
                                required = false,
                                description = "Optional canonical namespace prefix before the first dot. Alias: name.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedNamespace = arguments.optionalText("namespace") ?: arguments.optionalText("name")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_NAMESPACES_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_NAMESPACES_MAX_LIMIT)
            if (requestedNamespace == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized ${tools.size} tool(s) by canonical namespace.",
                    payload = tools.toToolNamespaceDiscoveryPayload(limit),
                )
            }

            val matchingTools =
                tools.filter { tool ->
                    tool.toolNamespace().equals(requestedNamespace, ignoreCase = true)
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools found in namespace $requestedNamespace."
                    } else {
                        "Found ${limitedMatches.size} tool(s) in namespace ${matchingTools.first().toolNamespace()}."
                    },
                payload =
                    buildJsonObject {
                        put("namespace", requestedNamespace)
                        put("canonicalNamespace", matchingTools.firstOrNull()?.toolNamespace()?.let(::JsonPrimitive) ?: JsonNull)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put("availabilityStats", matchingTools.toToolAvailabilityStatsByStatusPayload())
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { tool ->
                                    add(tool.toToolNamespaceMatchPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.search",
                    aliases = listOf("tool.search"),
                    description = "Search typed native tools by name, alias, description, permission, or argument metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Tool text to search for.",
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
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.search",
                        summary = "tools.search requires a non-empty query.",
                        field = "query",
                    )
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_SEARCH_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_SEARCH_MAX_LIMIT)
            val matches =
                toolRegistryProvider()
                    .descriptors()
                    .filter { tool -> tool.matchesToolQuery(query) }
                    .take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matches.isEmpty()) {
                        "No tools matched \"$query\"."
                    } else {
                        "Found ${matches.size} tool(s) matching \"$query\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put("resultCount", matches.size)
                        put(
                            "tools",
                            buildJsonArray {
                                matches.forEach { tool ->
                                    add(tool.toToolDescriptorPayload(includeInputSchema = false))
                                }
                            },
                        )
                    },
            )
        },
    )

private fun eventToolEntries(
    eventLogRepository: EventLogRepository,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.recent",
                    aliases = listOf("event.recent", "logs.recent", "log.recent"),
                    description = "Return bounded recent runtime event logs for local diagnostics.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum event count. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedLimit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = EVENT_LOG_DEFAULT_LIMIT,
                )
            val limit = requestedLimit.coerceIn(1, EVENT_LOG_MAX_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.recent received an unknown category.",
                            field = "category",
                            received = rawCategory,
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.recent received an unknown level.",
                            field = "level",
                            received = rawLevel,
                        )
                }
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val events =
                eventLogRepository
                    .observeRecent(limit = EVENT_LOG_SCAN_LIMIT)
                    .first()
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .take(limit)
                    .toList()
            ToolExecutionResult.success(
                summary =
                    if (events.isEmpty()) {
                        "No matching recent events found."
                    } else {
                        "Loaded ${events.size} recent event(s)."
                    },
                payload =
                    buildJsonObject {
                        put("eventCount", events.size)
                        put("recentFirst", true)
                        put("includeDetails", includeDetails)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put(
                            "events",
                            buildJsonArray {
                                events.forEach { event ->
                                    add(event.toEventLogPayload(includeDetails = includeDetails))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.get",
                    aliases = listOf("event.get", "logs.get", "log.get"),
                    description = "Return one runtime event log by id for local diagnostics.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "eventId",
                                required = true,
                                description = "Event log identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val eventId =
                arguments.optionalText("eventId")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.get requires a non-empty eventId.",
                        field = "eventId",
                        toolName = "events.get",
                    )
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val event =
                eventLogRepository.get(eventId)
                    ?: return@Entry eventNotFoundResult(eventId)
            ToolExecutionResult.success(
                summary = "Loaded event ${event.id}.",
                payload =
                    buildJsonObject {
                        put("event", event.toEventLogPayload(includeDetails = includeDetails))
                        put("includeDetails", includeDetails)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.search",
                    aliases = listOf("event.search", "logs.search", "log.search"),
                    description = "Search bounded recent runtime event logs for local diagnostics.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Text to search across event ids, categories, levels, messages, and details.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum event count. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val query =
                arguments.optionalText("query")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.search requires a non-empty query.",
                        field = "query",
                        toolName = "events.search",
                    )
            val requestedLimit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = EVENT_LOG_DEFAULT_LIMIT,
                )
            val limit = requestedLimit.coerceIn(1, EVENT_LOG_MAX_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.search received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.search",
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.search received an unknown level.",
                            field = "level",
                            received = rawLevel,
                            toolName = "events.search",
                        )
                }
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val events =
                eventLogRepository
                    .observeRecent(limit = EVENT_LOG_SCAN_LIMIT)
                    .first()
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .filter { event -> event.matchesEventQuery(query) }
                    .take(limit)
                    .toList()
            ToolExecutionResult.success(
                summary =
                    if (events.isEmpty()) {
                        "No events matched \"$query\"."
                    } else {
                        "Found ${events.size} event(s) matching \"$query\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put("eventCount", events.size)
                        put("recentFirst", true)
                        put("includeDetails", includeDetails)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put(
                            "events",
                            buildJsonArray {
                                events.forEach { event ->
                                    add(event.toEventLogPayload(includeDetails = includeDetails))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.stats",
                    aliases = listOf("event.stats", "logs.stats", "log.stats"),
                    description = "Return aggregate counts for recent runtime event logs without event details.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "scanLimit",
                                description = "Maximum recent event count to scan. Defaults to 200, max 500.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedScanLimit =
                arguments.optionalInt(
                    field = "scanLimit",
                    defaultValue = EVENT_LOG_SCAN_LIMIT,
                )
            val scanLimit = requestedScanLimit.coerceIn(1, EVENT_LOG_STATS_MAX_SCAN_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.stats received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.stats",
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.stats received an unknown level.",
                            field = "level",
                            received = rawLevel,
                            toolName = "events.stats",
                        )
                }
            val scannedEvents =
                eventLogRepository
                    .observeRecent(limit = scanLimit)
                    .first()
            val matchingEvents =
                scannedEvents
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .toList()
            ToolExecutionResult.success(
                summary =
                    if (matchingEvents.isEmpty()) {
                        "No matching recent events found in $scanLimit scanned event(s)."
                    } else {
                        "Summarized ${matchingEvents.size} matching event(s)."
                    },
                payload =
                    buildJsonObject {
                        put("scanLimit", scanLimit)
                        put("scannedEventCount", scannedEvents.size)
                        put("matchedEventCount", matchingEvents.size)
                        put("recentFirst", true)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put(
                            "newestEventAtIso",
                            matchingEvents
                                .firstOrNull()
                                ?.timestamp
                                ?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put(
                            "oldestEventAtIso",
                            matchingEvents
                                .lastOrNull()
                                ?.timestamp
                                ?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put("countsByCategory", matchingEvents.toEventCategoryCountsPayload())
                        put("countsByLevel", matchingEvents.toEventLevelCountsPayload())
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.trim",
                    aliases = listOf("event.trim", "logs.trim", "log.trim"),
                    description = "Delete local event logs older than an ISO-8601 cutoff after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "olderThanIso",
                                required = true,
                                description = "ISO-8601 cutoff. Events before this instant are deleted.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                required = true,
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val olderThanIso =
                arguments.optionalText("olderThanIso")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.trim requires a non-empty olderThanIso.",
                        field = "olderThanIso",
                        toolName = "events.trim",
                    )
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to trim old event logs.",
                    errorCode = "MISSING_TRIM_CONFIRMATION",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_TRIM_CONFIRMATION")
                            put("toolName", "events.trim")
                        },
                )
            }
            val cutoff =
                try {
                    Instant.parse(olderThanIso)
                } catch (_: DateTimeParseException) {
                    return@Entry invalidEventArguments(
                        summary = "events.trim received an invalid olderThanIso.",
                        field = "olderThanIso",
                        received = olderThanIso,
                        toolName = "events.trim",
                    )
                }
            val deletedCount = eventLogRepository.trimOlderThan(cutoff)
            ToolExecutionResult.success(
                summary = "Trimmed $deletedCount event log(s) older than $cutoff.",
                payload =
                    buildJsonObject {
                        put("olderThanIso", cutoff.toString())
                        put("deletedCount", deletedCount)
                    },
            )
        },
    )

private fun parseEventCategory(rawCategory: String): EventCategory? =
    when (rawCategory.trim().lowercase()) {
        "provider" -> EventCategory.Provider
        "tool" -> EventCategory.Tool
        "scheduler" -> EventCategory.Scheduler
        "skill" -> EventCategory.Skill
        "system" -> EventCategory.System
        "debug" -> EventCategory.Debug
        else -> null
    }

private fun parseEventLevel(rawLevel: String): EventLevel? =
    when (rawLevel.trim().lowercase()) {
        "info" -> EventLevel.Info
        "warn", "warning" -> EventLevel.Warn
        "error" -> EventLevel.Error
        else -> null
    }

private fun invalidEventArguments(
    summary: String,
    field: String,
    received: String? = null,
    toolName: String = "events.recent",
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "INVALID_ARGUMENTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", toolName)
                put("field", field)
                received?.let { value ->
                    put("received", value.take(EVENT_LOG_FILTER_MAX_CHARS))
                }
            },
    )

private fun eventNotFoundResult(eventId: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Event $eventId was not found.",
        errorCode = "EVENT_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "EVENT_NOT_FOUND")
                put("eventId", eventId)
            },
    )

private fun EventLogEntry.matchesEventQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    return buildList {
        add(id)
        add(category.name)
        add(level.name)
        add(message)
        details?.let(::add)
    }.any { value -> value.lowercase().contains(normalizedQuery) }
}

private fun List<EventLogEntry>.toEventCategoryCountsPayload(): JsonArray =
    buildJsonArray {
        listOf(
            EventCategory.Provider,
            EventCategory.Tool,
            EventCategory.Scheduler,
            EventCategory.Skill,
            EventCategory.System,
            EventCategory.Debug,
        ).forEach { category ->
            val count = count { event -> event.category == category }
            if (count > 0) {
                add(
                    buildJsonObject {
                        put("category", category.name)
                        put("count", count)
                    },
                )
            }
        }
    }

private fun List<EventLogEntry>.toEventLevelCountsPayload(): JsonArray =
    buildJsonArray {
        listOf(
            EventLevel.Info,
            EventLevel.Warn,
            EventLevel.Error,
        ).forEach { level ->
            val count = count { event -> event.level == level }
            if (count > 0) {
                add(
                    buildJsonObject {
                        put("level", level.name)
                        put("count", count)
                    },
                )
            }
        }
    }

private fun EventLogEntry.toEventLogPayload(includeDetails: Boolean): JsonObject =
    buildJsonObject {
        put("id", id)
        put("timestampIso", timestamp.toString())
        put("category", category.name)
        put("level", level.name)
        put("message", message.take(EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS))
        put("messageTruncated", message.length > EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS)
        if (includeDetails) {
            val boundedDetails = details?.take(EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS)
            put("details", boundedDetails?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "detailsTruncated",
                details?.let { it.length > EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS } ?: false,
            )
        }
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
                    aliases = listOf("task.get"),
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
                    name = "tasks.preview",
                    aliases =
                        listOf(
                            "task.preview",
                            "tasks.schedule.preview",
                            "task.schedule.preview",
                            "automations.preview",
                            "automation.preview",
                        ),
                    description = "Preview an automation schedule without creating or updating a task.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "scheduleKind",
                                required = true,
                                description = "once | interval | cron",
                            ),
                            ToolArgumentSpec(
                                name = "atIso",
                                description = "ISO-8601 instant for once schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "anchorAtIso",
                                description = "ISO-8601 anchor instant for interval schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "repeatEveryMinutes",
                                description = "Positive interval minutes; must meet scheduler minimum.",
                            ),
                            ToolArgumentSpec(
                                name = "cronExpression",
                                description = "Five-field cron expression for cron schedules.",
                            ),
                            ToolArgumentSpec(
                                name = "timezone",
                                description = "IANA timezone id for cron schedules.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val now = clock.instant()
            val preview =
                try {
                    parseTaskSchedulePreview(
                        arguments = arguments,
                        capabilities = schedulerCoordinator.capabilities(),
                        now = now,
                    )
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.preview",
                        summary = error.message ?: "tasks.preview received invalid arguments.",
                    )
                }
            when (preview) {
                is TaskToolParseResult.Failure -> preview.result
                is TaskToolParseResult.Success ->
                    ToolExecutionResult.success(
                        summary =
                            if (preview.value.nextRunAt == null) {
                                "Previewed schedule; no next run was produced."
                            } else {
                                "Previewed schedule."
                            },
                        payload = preview.value.toPayload(now = now),
                    )
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.reschedule",
                    aliases =
                        listOf(
                            "task.reschedule",
                            "tasks.recompute_next",
                            "task.recompute_next",
                            "automations.reschedule",
                            "automation.reschedule",
                        ),
                    description = "Recompute an automation's next run from its schedule without executing it.",
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
                    toolName = "tasks.reschedule",
                    summary = "tasks.reschedule requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.reschedule", taskId = taskId)
            val rescheduledAt = clock.instant()
            val recalculatedNextRunAt = schedulerCoordinator.taskPlanner.nextScheduledRun(task, rescheduledAt)
            val updatedTask =
                task.copy(
                    nextRunAt = recalculatedNextRunAt,
                    failureCount = 0,
                    updatedAt = rescheduledAt,
                )
            taskRepository.updateTask(updatedTask)
            if (updatedTask.enabled) {
                schedulerCoordinator.scheduleTask(updatedTask.id)
            } else {
                schedulerCoordinator.cancelTask(updatedTask.id)
            }
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary =
                    if (reloadedTask.nextRunAt == null) {
                        "Rescheduled task ${reloadedTask.name}; no future run remains."
                    } else {
                        "Rescheduled task ${reloadedTask.name}."
                    },
                payload =
                    buildJsonObject {
                        put("rescheduledAtIso", rescheduledAt.toString())
                        put("previousNextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("nextRunAtIso", reloadedTask.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("failureCountCleared", task.failureCount != 0)
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
                ToolDescriptor(
                    name = "tasks.snooze",
                    aliases =
                        listOf(
                            "task.snooze",
                            "tasks.postpone",
                            "task.postpone",
                            "automations.snooze",
                            "automation.snooze",
                        ),
                    description = "Postpone one currently due automation without executing its prompt.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Due task identifier",
                            ),
                            ToolArgumentSpec(
                                name = "delayMinutes",
                                description = "Positive minutes to postpone. Defaults to 15, max 10080.",
                            ),
                            ToolArgumentSpec(
                                name = "untilIso",
                                description = "Optional ISO-8601 instant to postpone until instead of delayMinutes.",
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
                    toolName = "tasks.snooze",
                    summary = "tasks.snooze requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.snooze", taskId = taskId)
            val snoozedAt = clock.instant()
            val snoozedUntil =
                try {
                    arguments.parseTaskSnoozeUntil(now = snoozedAt)
                } catch (error: IllegalArgumentException) {
                    return@Entry invalidTaskArguments(
                        toolName = "tasks.snooze",
                        summary = error.message ?: "tasks.snooze received invalid arguments.",
                    )
                }
            val dueAt =
                task.nextRunAt
                    ?.takeIf { nextRunAt -> !nextRunAt.isAfter(snoozedAt) }
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task ${task.name} is not currently due.",
                        errorCode = "TASK_NOT_DUE",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_NOT_DUE")
                                put("toolName", "tasks.snooze")
                                put("taskId", task.id)
                                put("enabled", task.enabled)
                                put("nowIso", snoozedAt.toString())
                                put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            },
                    )
            if (!task.enabled) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Task ${task.name} is disabled and cannot be snoozed as a due automation.",
                    errorCode = "TASK_NOT_DUE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "TASK_NOT_DUE")
                            put("toolName", "tasks.snooze")
                            put("taskId", task.id)
                            put("enabled", false)
                            put("nowIso", snoozedAt.toString())
                            put("nextRunAtIso", dueAt.toString())
                        },
                )
            }
            val updatedTask =
                task.copy(
                    nextRunAt = snoozedUntil,
                    updatedAt = snoozedAt,
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.scheduleTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Snoozed due run for task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put("snoozedAtIso", snoozedAt.toString())
                        put("previousNextRunAtIso", dueAt.toString())
                        put("snoozedUntilIso", snoozedUntil.toString())
                        put("snoozeDelaySeconds", Duration.between(snoozedAt, snoozedUntil).seconds)
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
                    name = "tasks.stats",
                    aliases = listOf("task.stats", "automations.stats", "automation.stats"),
                    description = "Return aggregate scheduler and automation-run statistics without loading every task.",
                ),
        ) { _, _ ->
            val stats = taskRepository.getTaskStats(clock.instant())
            ToolExecutionResult.success(
                summary = "Loaded automation stats for ${stats.totalTaskCount} task(s) and ${stats.totalRunCount} run(s).",
                payload =
                    stats.toTaskStatsPayload(
                        minimumBackgroundIntervalMinutes =
                            schedulerCoordinator
                                .capabilities()
                                .minimumBackgroundInterval
                                .toMinutes(),
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.due",
                    aliases =
                        listOf(
                            "task.due",
                            "tasks.overdue",
                            "task.overdue",
                            "automations.due",
                            "automation.due",
                        ),
                    description = "List enabled automations that are currently due, ordered by scheduled run time.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum task count. Defaults to 20, max 50.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_DUE_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_DUE_MAX_LIMIT)
            val now = clock.instant()
            val tasks = taskRepository.getEnabledTasksDueBefore(instant = now, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No due enabled automations found."
                    } else {
                        "Loaded ${tasks.size} due enabled automation(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("returnedCount", tasks.size)
                        put("taskCount", tasks.size)
                        put("dueTaskCount", tasks.size)
                        put(
                            "oldestDueAtIso",
                            tasks.firstOrNull()?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put(
                            "newestDueAtIso",
                            tasks.lastOrNull()?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(task.toDueTaskPayload(now = now))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.skip",
                    aliases =
                        listOf(
                            "task.skip",
                            "tasks.skip_due",
                            "task.skip_due",
                            "automations.skip",
                            "automation.skip",
                        ),
                    description = "Skip one currently due automation run without executing its prompt.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "taskId",
                                required = true,
                                description = "Due task identifier",
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
                    toolName = "tasks.skip",
                    summary = "tasks.skip requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val task =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.skip", taskId = taskId)
            val skippedAt = clock.instant()
            val dueAt =
                task.nextRunAt
                    ?.takeIf { nextRunAt -> !nextRunAt.isAfter(skippedAt) }
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task ${task.name} is not currently due.",
                        errorCode = "TASK_NOT_DUE",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_NOT_DUE")
                                put("toolName", "tasks.skip")
                                put("taskId", task.id)
                                put("enabled", task.enabled)
                                put("nowIso", skippedAt.toString())
                                put("nextRunAtIso", task.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            },
                    )
            if (!task.enabled) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Task ${task.name} is disabled and cannot be skipped as a due automation.",
                    errorCode = "TASK_NOT_DUE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "TASK_NOT_DUE")
                            put("toolName", "tasks.skip")
                            put("taskId", task.id)
                            put("enabled", false)
                            put("nowIso", skippedAt.toString())
                            put("nextRunAtIso", dueAt.toString())
                        },
                )
            }
            val skippedRun =
                taskRepository
                    .recordRun(taskId = task.id, scheduledAt = dueAt)
                    .copy(
                        status = TaskRunStatus.Skipped,
                        startedAt = skippedAt,
                        finishedAt = skippedAt,
                        resultSummary = "Skipped by tasks.skip.",
                    )
            taskRepository.updateRun(skippedRun)
            val nextRunAt = schedulerCoordinator.taskPlanner.nextScheduledRun(task, skippedAt)
            val updatedTask =
                task.copy(
                    nextRunAt = nextRunAt,
                    lastRunAt = skippedAt,
                    failureCount = 0,
                    updatedAt = skippedAt,
                )
            taskRepository.updateTask(updatedTask)
            schedulerCoordinator.scheduleTask(updatedTask.id)
            val reloadedTask = taskRepository.getTask(updatedTask.id) ?: updatedTask
            ToolExecutionResult.success(
                summary = "Skipped due run for task ${reloadedTask.name}.",
                payload =
                    buildJsonObject {
                        put("skippedAtIso", skippedAt.toString())
                        put("previousNextRunAtIso", dueAt.toString())
                        put("nextRunAtIso", reloadedTask.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put("run", skippedRun.toTaskRunHistoryPayload())
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
                ToolDescriptor(
                    name = "tasks.next",
                    aliases =
                        listOf(
                            "task.next",
                            "tasks.upcoming",
                            "task.upcoming",
                            "automations.next",
                            "automation.next",
                        ),
                    description = "List upcoming enabled automations ordered by next scheduled run.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum task count. Defaults to 20, max 50.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TASK_UPCOMING_DEFAULT_LIMIT,
                    ).coerceIn(0, TASK_UPCOMING_MAX_LIMIT)
            val now = clock.instant()
            val tasks = taskRepository.getUpcomingEnabledTasks(limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (tasks.isEmpty()) {
                        "No upcoming enabled automations found."
                    } else {
                        "Loaded ${tasks.size} upcoming enabled automation(s)."
                    },
                payload =
                    buildJsonObject {
                        put("nowIso", now.toString())
                        put("returnedCount", tasks.size)
                        put("taskCount", tasks.size)
                        put("dueTaskCount", tasks.count { task -> task.nextRunAt?.isAfter(now) == false })
                        put("soonestRunAtIso", tasks.firstOrNull()?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put(
                            "tasks",
                            buildJsonArray {
                                tasks.forEach { task ->
                                    add(task.toUpcomingTaskPayload(now = now))
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
            descriptor =
                ToolDescriptor(
                    name = "tasks.run.get",
                    aliases = listOf("task.run.get", "taskrun.get"),
                    description = "Return one automation run by id with its parent task metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "runId",
                                required = true,
                                description = "Task run identifier",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val runId =
                arguments["runId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (runId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run.get",
                    summary = "tasks.run.get requires a non-empty runId.",
                    field = "runId",
                )
            }
            val run =
                taskRepository.getRun(runId)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task run $runId was not found.",
                        errorCode = "TASK_RUN_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_RUN_NOT_FOUND")
                                put("toolName", "tasks.run.get")
                                put("runId", runId)
                            },
                    )
            val task =
                taskRepository.getTask(run.taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.run.get", taskId = run.taskId)
            ToolExecutionResult.success(
                summary = "Loaded run ${run.id} for task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("taskId", task.id)
                        put("taskName", task.name)
                        put("run", run.toTaskRunHistoryPayload())
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tasks.run.retry",
                    aliases =
                        listOf(
                            "task.run.retry",
                            "tasks.retry_run",
                            "task.retry_run",
                            "automations.run.retry",
                            "automation.run.retry",
                        ),
                    description = "Queue a manual retry for a failed or skipped automation run.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "runId",
                                required = true,
                                description = "Failed or skipped automation run identifier",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val runId =
                arguments["runId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (runId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.run.retry",
                    summary = "tasks.run.retry requires a non-empty runId.",
                    field = "runId",
                )
            }
            val sourceRun =
                taskRepository.getRun(runId)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Task run $runId was not found.",
                        errorCode = "TASK_RUN_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TASK_RUN_NOT_FOUND")
                                put("toolName", "tasks.run.retry")
                                put("runId", runId)
                            },
                    )
            if (sourceRun.status != TaskRunStatus.Failure && sourceRun.status != TaskRunStatus.Skipped) {
                return@Entry ToolExecutionResult.failure(
                    summary =
                        "tasks.run.retry can only retry Failure or Skipped runs; " +
                            "${sourceRun.status.name} is not retryable.",
                    errorCode = "TASK_RUN_NOT_RETRYABLE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "TASK_RUN_NOT_RETRYABLE")
                            put("toolName", "tasks.run.retry")
                            put("runId", sourceRun.id)
                            put("status", sourceRun.status.name)
                        },
                )
            }
            val task =
                taskRepository.getTask(sourceRun.taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.run.retry", taskId = sourceRun.taskId)
            val queuedAt = clock.instant()
            schedulerCoordinator.runNow(task.id)
            val reloadedTask = taskRepository.getTask(task.id) ?: task
            ToolExecutionResult.success(
                summary = "Queued retry for ${sourceRun.status.name} run ${sourceRun.id} of task ${task.name}.",
                payload =
                    buildJsonObject {
                        put("retryOfRunId", sourceRun.id)
                        put("queuedAtIso", queuedAt.toString())
                        put("trigger", "manual_retry")
                        put("sourceRun", sourceRun.toTaskRunHistoryPayload())
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
                ToolDescriptor(
                    name = "tasks.runs.recent",
                    aliases =
                        listOf(
                            "task.runs.recent",
                            "tasks.recent_runs",
                            "task.recent_runs",
                            "automations.runs.recent",
                            "automation.runs.recent",
                        ),
                    description = "Return recent automation runs across all tasks.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRuns(limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (runs.isEmpty()) {
                        "No recent automation runs found."
                    } else {
                        "Loaded ${runs.size} recent automation run(s)."
                    },
                payload =
                    buildJsonObject {
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(
                                        run.toTaskRunWithTaskPayload(
                                            task = taskRepository.getTask(run.taskId),
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
                    name = "tasks.runs.status",
                    aliases =
                        listOf(
                            "task.runs.status",
                            "tasks.status_runs",
                            "task.status_runs",
                            "automations.runs.status",
                            "automation.runs.status",
                        ),
                    description = "Return recent automation runs across all tasks for one run status.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "status",
                                required = true,
                                description = "Pending, Running, Success, Failure, or Skipped.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val rawStatus =
                arguments.optionalText("status")
                    ?: return@Entry invalidTaskArguments(
                        toolName = "tasks.runs.status",
                        summary = "tasks.runs.status requires a non-empty status.",
                        field = "status",
                    )
            val status =
                TaskRunStatus.entries.firstOrNull { candidate ->
                    candidate.name.equals(rawStatus, ignoreCase = true)
                } ?: return@Entry invalidTaskArguments(
                    toolName = "tasks.runs.status",
                    summary = "tasks.runs.status received unsupported status: $rawStatus.",
                    field = "status",
                )
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRunsByStatus(status = status, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (runs.isEmpty()) {
                        "No recent ${status.name} automation runs found."
                    } else {
                        "Loaded ${runs.size} recent ${status.name} automation run(s)."
                    },
                payload =
                    buildJsonObject {
                        put("status", status.name)
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(
                                        run.toTaskRunWithTaskPayload(
                                            task = taskRepository.getTask(run.taskId),
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
                    name = "tasks.failures",
                    aliases =
                        listOf(
                            "task.failures",
                            "tasks.failed",
                            "task.failed",
                            "tasks.failed_runs",
                            "task.failed_runs",
                            "automations.failures",
                            "automation.failures",
                        ),
                    description = "Return recent failed automation runs across all tasks.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum run count. Defaults to 10.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = TASK_RUN_HISTORY_DEFAULT_LIMIT,
                )
            val runs = taskRepository.getRecentRunsByStatus(status = TaskRunStatus.Failure, limit = limit)
            ToolExecutionResult.success(
                summary =
                    if (runs.isEmpty()) {
                        "No recent failed automation runs found."
                    } else {
                        "Loaded ${runs.size} recent failed automation run(s)."
                    },
                payload =
                    buildJsonObject {
                        put("returnedCount", runs.size)
                        put("recentFirst", true)
                        put(
                            "runs",
                            buildJsonArray {
                                runs.forEach { run ->
                                    add(
                                        run.toTaskRunWithTaskPayload(
                                            task = taskRepository.getTask(run.taskId),
                                        ),
                                    )
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor = taskDuplicateDescriptor(),
        ) { _, arguments ->
            val taskId =
                arguments["taskId"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
            if (taskId.isBlank()) {
                return@Entry invalidTaskArguments(
                    toolName = "tasks.duplicate",
                    summary = "tasks.duplicate requires a non-empty taskId.",
                    field = "taskId",
                )
            }
            val sourceTask =
                taskRepository.getTask(taskId)
                    ?: return@Entry taskNotFoundResult(toolName = "tasks.duplicate", taskId = taskId)
            val copyName = arguments.optionalText("name") ?: "Copy of ${sourceTask.name}"
            val enabled = arguments.optionalBoolean("enabled", defaultValue = false)
            val createdTask =
                taskRepository.createTask(
                    name = copyName,
                    prompt = sourceTask.prompt,
                    schedule = sourceTask.schedule,
                    executionMode = sourceTask.executionMode,
                    targetSessionId = sourceTask.targetSessionId,
                    precise = sourceTask.precise,
                    maxRetries = sourceTask.maxRetries,
                )
            val finalTask =
                if (enabled) {
                    createdTask
                } else {
                    createdTask.copy(
                        enabled = false,
                        updatedAt = clock.instant(),
                    )
                }
            if (finalTask != createdTask) {
                taskRepository.updateTask(finalTask)
            }
            if (finalTask.enabled) {
                schedulerCoordinator.scheduleTask(finalTask.id)
            }
            val reloadedTask = taskRepository.getTask(finalTask.id) ?: finalTask
            ToolExecutionResult.success(
                summary =
                    if (reloadedTask.enabled) {
                        "Duplicated and enabled task ${sourceTask.name} as ${reloadedTask.name}."
                    } else {
                        "Duplicated task ${sourceTask.name} as disabled copy ${reloadedTask.name}."
                    },
                payload =
                    buildJsonObject {
                        put("sourceTaskId", sourceTask.id)
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
        aliases = listOf("task.create"),
        description = "Create a scheduled automation using explicit schedule fields.",
        arguments = taskMutationArguments(requiredTaskId = false),
    )

private fun taskUpdateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.update",
        aliases = listOf("task.update"),
        description = "Patch an existing task without replacing unspecified fields.",
        arguments = taskMutationArguments(requiredTaskId = true),
    )

private fun taskDuplicateDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = "tasks.duplicate",
        aliases = listOf("task.duplicate", "tasks.copy", "task.copy"),
        description = "Duplicate an existing scheduled automation, disabled by default.",
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "taskId",
                    required = true,
                    description = "Task identifier to copy",
                ),
                ToolArgumentSpec(
                    name = "name",
                    description = "Name for the copy. Defaults to Copy of the source task name.",
                ),
                ToolArgumentSpec(
                    name = "enabled",
                    description = "true to enable and schedule the copy. Defaults to false.",
                ),
            ),
    )

private fun taskToggleDescriptor(
    name: String,
    description: String,
): ToolDescriptor =
    ToolDescriptor(
        name = name,
        aliases = listOf(name.replaceFirst("tasks.", "task.")),
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
private const val EVENT_LOG_DEFAULT_LIMIT = 20
private const val EVENT_LOG_MAX_LIMIT = 50
private const val EVENT_LOG_SCAN_LIMIT = 200
private const val EVENT_LOG_STATS_MAX_SCAN_LIMIT = 500
private const val EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS = 500
private const val EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS = 1_000
private const val EVENT_LOG_FILTER_MAX_CHARS = 80
private const val MESSAGE_CONTEXT_DEFAULT_RADIUS = 3
private const val MESSAGE_RECENT_DEFAULT_LIMIT = 20
private const val MESSAGE_SEARCH_DEFAULT_LIMIT = 20
private const val MESSAGE_SEARCH_SNIPPET_MAX_CHARS = 500
private const val SESSION_ACTIVITY_SNIPPET_MAX_CHARS = 300
private const val SESSION_SEARCH_DEFAULT_LIMIT = 20
private const val SESSION_SUMMARY_SNIPPET_MAX_CHARS = 500

private enum class MessagePageDirection(
    val payloadName: String,
) {
    Start("start"),
    Recent("recent"),
    Before("before"),
    After("after"),
}

private const val SKILL_INSTRUCTIONS_MAX_CHARS = 8_000
private const val SKILL_SEARCH_DEFAULT_LIMIT = 20
private const val SKILL_SEARCH_MAX_LIMIT = 50
private const val SKILL_SEARCH_SNIPPET_MAX_CHARS = 500
private const val TASK_DUE_DEFAULT_LIMIT = 20
private const val TASK_DUE_MAX_LIMIT = 50
private const val TASK_RUN_HISTORY_DEFAULT_LIMIT = 10
private const val TASK_SEARCH_DEFAULT_LIMIT = 20
private const val TASK_SNOOZE_DEFAULT_DELAY_MINUTES = 15L
private const val TASK_SNOOZE_MAX_DELAY_MINUTES = 10_080L
private const val TASK_UPCOMING_DEFAULT_LIMIT = 20
private const val TASK_UPCOMING_MAX_LIMIT = 50
private const val TOOL_ARGUMENTS_DEFAULT_LIMIT = 50
private const val TOOL_ARGUMENTS_MAX_LIMIT = 100
private const val TOOL_AVAILABILITY_DEFAULT_LIMIT = 50
private const val TOOL_AVAILABILITY_MAX_LIMIT = 100
private const val TOOL_PERMISSIONS_DEFAULT_LIMIT = 50
private const val TOOL_PERMISSIONS_MAX_LIMIT = 100
private const val TOOL_NAMESPACES_DEFAULT_LIMIT = 50
private const val TOOL_NAMESPACES_MAX_LIMIT = 100
private const val TOOL_SEARCH_DEFAULT_LIMIT = 20
private const val TOOL_SEARCH_MAX_LIMIT = 100
private const val TOOL_NOTIFICATION_CHANNEL_ID = "androidclaw.tools"
private val TOOL_VALIDATE_RESERVED_ARGUMENT_FIELDS = setOf("toolName", "name", "arguments")

private fun ProviderType.matchesProviderIdentifier(identifier: String): Boolean =
    providerId.equals(identifier, ignoreCase = true) ||
        storageValue.equals(identifier, ignoreCase = true) ||
        displayName.equals(identifier, ignoreCase = true) ||
        name.equals(identifier, ignoreCase = true)

private fun ProviderType.toProviderPayload(settings: ProviderSettingsSnapshot): JsonObject =
    buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("selected", settings.providerType == this@toProviderPayload)
        put(
            "endpointSettings",
            if (requiresRemoteSettings) {
                val endpointSettings = settings.endpointSettings(this@toProviderPayload)
                buildJsonObject {
                    put("baseUrl", endpointSettings.baseUrl)
                    put("modelId", endpointSettings.modelId)
                    put("timeoutSeconds", endpointSettings.timeoutSeconds)
                }
            } else {
                JsonNull
            },
        )
    }

private data class ProviderAuthState(
    val providerType: ProviderType,
    val status: String,
    val apiKeyConfigured: Boolean?,
    val oauthConfigured: Boolean?,
    val oauthExpired: Boolean?,
    val oauthProfileConfigured: Boolean?,
)

private suspend fun ProviderSettingsSnapshot.toProviderStatsPayload(
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): JsonObject {
    val providers = ProviderType.entries
    val remoteProviders = providers.filter { provider -> provider.requiresRemoteSettings }
    val authStates =
        providers.map { provider ->
            provider.toProviderAuthState(
                providerSecretStore = providerSecretStore,
                clock = clock,
            )
        }
    return buildJsonObject {
        put("providerCount", providers.size)
        put("currentProviderId", providerType.providerId)
        put("currentProviderDisplayName", providerType.displayName)
        put("currentProtocolFamily", providerType.protocolFamily.name)
        put("currentAuthMode", providerType.authMode.name)
        put("selectedRequiresCredential", providerType.requiresApiKey || providerType.usesOpenAiCodexOAuth)
        put("secretStatusAvailable", providerSecretStore != null)
        put("remoteProviderCount", remoteProviders.size)
        put("localProviderCount", providers.count { provider -> !provider.requiresRemoteSettings })
        put("requiresCredentialProviderCount", providers.count { provider -> provider.requiresApiKey || provider.usesOpenAiCodexOAuth })
        put("apiKeyProviderCount", providers.count { provider -> provider.requiresApiKey })
        put("openAiCodexOAuthProviderCount", providers.count { provider -> provider.usesOpenAiCodexOAuth })
        put(
            "protocolFamilyStats",
            providerTypeCountPayloads(
                nameField = "protocolFamily",
                selector = { provider -> provider.protocolFamily.name },
            ),
        )
        put(
            "authModeStats",
            providerTypeCountPayloads(
                nameField = "authMode",
                selector = { provider -> provider.authMode.name },
            ),
        )
        put(
            "authStatusStats",
            authStates.toProviderAuthStatusStatsPayload(),
        )
        put(
            "endpointStats",
            remoteProviders.toProviderEndpointStatsPayload(settings = this@toProviderStatsPayload),
        )
        put(
            "apiKeyStats",
            authStates.toProviderApiKeyStatsPayload(),
        )
        put(
            "oauthStats",
            authStates.toProviderOAuthStatsPayload(),
        )
    }
}

private suspend fun ProviderType.toProviderAuthState(
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): ProviderAuthState {
    val apiKeyConfigured =
        if (providerSecretStore == null || !requiresApiKey) {
            null
        } else {
            providerSecretStore.readApiKey(this) != null
        }
    val oauthCredential =
        if (providerSecretStore == null || !usesOpenAiCodexOAuth) {
            null
        } else {
            providerSecretStore.readOAuthCredential(this)
        }
    val oauthConfigured =
        when {
            providerSecretStore == null || !usesOpenAiCodexOAuth -> null
            else -> oauthCredential != null
        }
    val status =
        when {
            authMode == ProviderAuthMode.None -> "NotRequired"
            providerSecretStore == null -> "Unknown"
            requiresApiKey && apiKeyConfigured == true -> "Configured"
            usesOpenAiCodexOAuth && oauthConfigured == true -> "Configured"
            else -> "Missing"
        }
    val expiresAt = oauthCredential?.expiresAtEpochMillis?.let(Instant::ofEpochMilli)
    val oauthProfileConfigured =
        oauthCredential?.let { credential ->
            !credential.email.isNullOrBlank() ||
                !credential.profileName.isNullOrBlank() ||
                !credential.chatGptAccountId.isNullOrBlank()
        }
    return ProviderAuthState(
        providerType = this,
        status = status,
        apiKeyConfigured = apiKeyConfigured,
        oauthConfigured = oauthConfigured,
        oauthExpired = expiresAt?.isBefore(clock.instant()),
        oauthProfileConfigured = oauthProfileConfigured,
    )
}

private fun providerTypeCountPayloads(
    nameField: String,
    selector: (ProviderType) -> String,
): JsonArray =
    buildJsonArray {
        ProviderType.entries
            .groupingBy(selector)
            .eachCount()
            .toList()
            .sortedBy { (name, _) -> name }
            .forEach { (name, count) ->
                add(namedCountPayload(nameField = nameField, name = name, countField = "providerCount", count = count))
            }
    }

private fun List<ProviderAuthState>.toProviderAuthStatusStatsPayload(): JsonArray =
    buildJsonArray {
        groupingBy { state -> state.status }
            .eachCount()
            .toList()
            .sortedBy { (status, _) -> status }
            .forEach { (status, count) ->
                add(namedCountPayload(nameField = "status", name = status, countField = "providerCount", count = count))
            }
    }

private fun List<ProviderType>.toProviderEndpointStatsPayload(settings: ProviderSettingsSnapshot): JsonObject =
    buildJsonObject {
        put("remoteProviderCount", size)
        put("customBaseUrlProviderCount", count { provider -> settings.endpointSettings(provider).baseUrl != provider.defaultBaseUrl })
        put("customModelIdProviderCount", count { provider -> settings.endpointSettings(provider).modelId != provider.defaultModelId })
        put(
            "customTimeoutProviderCount",
            count { provider ->
                settings.endpointSettings(provider).timeoutSeconds != provider.defaultEndpointSettings().timeoutSeconds
            },
        )
        put("blankModelIdProviderCount", count { provider -> settings.endpointSettings(provider).modelId.isBlank() })
    }

private fun List<ProviderAuthState>.toProviderApiKeyStatsPayload(): JsonObject {
    val apiKeyStates = filter { state -> state.providerType.requiresApiKey }
    return buildJsonObject {
        put("apiKeyProviderCount", apiKeyStates.size)
        put("apiKeyConfiguredProviderCount", apiKeyStates.count { state -> state.apiKeyConfigured == true })
        put("apiKeyMissingProviderCount", apiKeyStates.count { state -> state.status == "Missing" })
        put("apiKeyUnknownProviderCount", apiKeyStates.count { state -> state.status == "Unknown" })
    }
}

private fun List<ProviderAuthState>.toProviderOAuthStatsPayload(): JsonObject {
    val oauthStates = filter { state -> state.providerType.usesOpenAiCodexOAuth }
    return buildJsonObject {
        put("oauthProviderCount", oauthStates.size)
        put("oauthConfiguredProviderCount", oauthStates.count { state -> state.oauthConfigured == true })
        put("oauthMissingProviderCount", oauthStates.count { state -> state.status == "Missing" })
        put("oauthUnknownProviderCount", oauthStates.count { state -> state.status == "Unknown" })
        put("oauthExpiredProviderCount", oauthStates.count { state -> state.oauthExpired == true })
        put(
            "oauthProfileConfiguredProviderCount",
            oauthStates.count { state -> state.oauthProfileConfigured == true },
        )
    }
}

private suspend fun ProviderType.toProviderAuthPayload(
    settings: ProviderSettingsSnapshot,
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): JsonObject {
    val apiKeyConfigured =
        if (providerSecretStore == null || !requiresApiKey) {
            null
        } else {
            providerSecretStore.readApiKey(this) != null
        }
    val oauthCredential =
        if (providerSecretStore == null || !usesOpenAiCodexOAuth) {
            null
        } else {
            providerSecretStore.readOAuthCredential(this)
        }
    val oauthConfigured =
        when {
            providerSecretStore == null || !usesOpenAiCodexOAuth -> null
            else -> oauthCredential != null
        }
    val configured =
        when {
            providerSecretStore == null -> null
            authMode == ai.androidclaw.data.ProviderAuthMode.None -> true
            requiresApiKey -> apiKeyConfigured == true
            usesOpenAiCodexOAuth -> oauthConfigured == true
            else -> false
        }
    val status =
        when (configured) {
            null -> "Unknown"
            true -> if (authMode == ai.androidclaw.data.ProviderAuthMode.None) "NotRequired" else "Configured"
            false -> "Missing"
        }
    val expiresAt = oauthCredential?.expiresAtEpochMillis?.let(Instant::ofEpochMilli)
    val oauthProfileConfigured =
        oauthCredential?.let { credential ->
            !credential.email.isNullOrBlank() ||
                !credential.profileName.isNullOrBlank() ||
                !credential.chatGptAccountId.isNullOrBlank()
        }
    return buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("authMode", authMode.name)
        put("selected", settings.providerType == this@toProviderAuthPayload)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("secretStatusAvailable", providerSecretStore != null)
        put("configured", configured?.let(::JsonPrimitive) ?: JsonNull)
        put("status", status)
        put("apiKeyConfigured", apiKeyConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthConfigured", oauthConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthExpiresAtIso", expiresAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("oauthExpired", expiresAt?.isBefore(clock.instant())?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthProfileConfigured", oauthProfileConfigured?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun JsonObject.optionalProviderTimeoutSeconds(): Int? {
    val value = optionalText("timeoutSeconds") ?: return null
    return value.toIntOrNull()
        ?: throw IllegalArgumentException("providers.configure received a non-numeric timeoutSeconds.")
}

private fun invalidToolDiscoveryArguments(
    toolName: String,
    summary: String,
    field: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "INVALID_ARGUMENTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", toolName)
                put("field", field)
            },
    )

private fun JsonObject.hasProvidedToolArgument(argumentName: String): Boolean {
    val value = this[argumentName] ?: return false
    return value !is JsonPrimitive || value.content.isNotBlank()
}

private fun List<String>.toToolStringArrayPayload(): JsonArray =
    buildJsonArray {
        forEach { value ->
            add(JsonPrimitive(value))
        }
    }

private fun List<ToolDescriptor>.toToolStatsPayload(): JsonObject =
    buildJsonObject {
        put("toolCount", size)
        put("totalToolCount", size)
        put("availableToolCount", count { tool -> tool.availability.status == ToolAvailabilityStatus.Available })
        put("foregroundRequiredToolCount", count { tool -> tool.foregroundRequired })
        put("toolsWithRequiredPermissionsCount", count { tool -> tool.requiredPermissions.isNotEmpty() })
        put("totalRequiredPermissionCount", sumOf { tool -> tool.requiredPermissions.size })
        put("toolsWithAliasesCount", count { tool -> tool.aliases.isNotEmpty() })
        put("aliasCount", sumOf { tool -> tool.aliases.size })
        put("toolsWithArgumentsCount", count { tool -> tool.arguments.isNotEmpty() })
        put("totalArgumentCount", sumOf { tool -> tool.arguments.size })
        put("requiredArgumentCount", sumOf { tool -> tool.arguments.count { argument -> argument.required } })
        put("inputSchemaIncluded", false)
        put("availabilityStats", toToolAvailabilityStatsPayload())
        put("permissionStats", toToolPermissionStatsPayload())
    }

private fun List<ToolDescriptor>.toToolAvailabilityStatsPayload(): JsonArray {
    val countsByStatus = groupingBy { tool -> tool.availability.status }.eachCount()
    return buildJsonArray {
        ToolAvailabilityStatus.entries.forEach { status ->
            countsByStatus[status]?.let { count ->
                add(
                    buildJsonObject {
                        put("status", status.name)
                        put("toolCount", count)
                    },
                )
            }
        }
    }
}

private fun List<ToolDescriptor>.toToolPermissionStatsPayload(): JsonArray {
    val stats =
        flatMap { tool ->
            tool.requiredPermissions.map { permission ->
                tool.name to permission
            }
        }.groupBy { (_, permission) ->
            permission.permission to permission.displayName
        }.toList()
            .sortedWith(
                compareBy(
                    { (permissionKey, _) -> permissionKey.first },
                    { (permissionKey, _) -> permissionKey.second },
                ),
            )
    return buildJsonArray {
        stats.forEach { (permissionKey, entries) ->
            add(
                buildJsonObject {
                    put("permission", permissionKey.first)
                    put("displayName", permissionKey.second)
                    put("toolCount", entries.map { (toolName, _) -> toolName }.distinct().size)
                    put("requirementCount", entries.size)
                },
            )
        }
    }
}

private fun List<ToolDescriptor>.toToolArgumentStatsPayload(
    limit: Int,
    requiredOnly: Boolean,
): JsonObject {
    val stats =
        flatMap { tool ->
            tool.arguments
                .filter { argument -> !requiredOnly || argument.required }
                .map { argument -> tool to argument }
        }.groupBy { (_, argument) -> argument.name }
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, List<Pair<ToolDescriptor, ToolArgumentSpec>>>> { (_, entries) ->
                    entries.map { (tool, _) -> tool.name }.distinct().size
                }.thenBy { (argumentName, _) -> argumentName },
            )
    val limitedStats = stats.take(limit)
    return buildJsonObject {
        put("argumentName", JsonNull)
        put("requiredOnly", requiredOnly)
        put("limit", limit)
        put("uniqueArgumentCount", stats.size)
        put("resultCount", limitedStats.size)
        if (stats.size > limitedStats.size) {
            put("omittedCount", stats.size - limitedStats.size)
        }
        put(
            "arguments",
            buildJsonArray {
                limitedStats.forEach { (argumentName, entries) ->
                    val toolNames = entries.map { (tool, _) -> tool.name }.distinct().sorted()
                    val requiredCount = entries.count { (_, argument) -> argument.required }
                    val optionalCount = entries.size - requiredCount
                    add(
                        buildJsonObject {
                            put("name", argumentName)
                            put("toolCount", toolNames.size)
                            put("requiredCount", requiredCount)
                            put("optionalCount", optionalCount)
                            put(
                                "sampleTools",
                                buildJsonArray {
                                    toolNames.take(5).forEach { toolName ->
                                        add(JsonPrimitive(toolName))
                                    }
                                },
                            )
                            if (toolNames.size > 5) {
                                put("sampleToolsOmitted", toolNames.size - 5)
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun ToolDescriptor.matchesToolQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    val values =
        buildList {
            add(name)
            add(description)
            addAll(aliases)
            add(availability.status.name)
            availability.reason?.let(::add)
            requiredPermissions.forEach { permission ->
                add(permission.permission)
                add(permission.displayName)
            }
            arguments.forEach { argument ->
                add(argument.name)
                add(argument.description)
            }
        }
    return values.any { value -> value.lowercase().contains(normalizedQuery) }
}

private fun ToolDescriptor.toToolArgumentMatchPayload(matchingArguments: List<ToolArgumentSpec>): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "matchingArguments",
            buildJsonArray {
                matchingArguments.forEach { argument ->
                    add(argument.toToolArgumentPayload())
                }
            },
        )
    }

private fun ToolArgumentSpec.toToolArgumentPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("required", required)
        put("description", description)
    }

private fun List<ToolDescriptor>.toToolAvailabilityStatsPayload(
    limit: Int,
    foregroundRequiredOnly: Boolean,
): JsonObject {
    val filteredTools =
        if (foregroundRequiredOnly) {
            filter { tool -> tool.foregroundRequired }
        } else {
            this
        }
    val toolsByStatus = filteredTools.groupBy { tool -> tool.availability.status }
    return buildJsonObject {
        put("availabilityStatus", JsonNull)
        put("foregroundRequiredOnly", foregroundRequiredOnly)
        put("limit", limit)
        put("toolCount", filteredTools.size)
        put("statusCount", toolsByStatus.size)
        put(
            "statuses",
            buildJsonArray {
                ToolAvailabilityStatus.entries.forEach { status ->
                    val statusTools = toolsByStatus[status].orEmpty()
                    if (statusTools.isNotEmpty()) {
                        val toolNames = statusTools.map { tool -> tool.name }.sorted()
                        add(
                            buildJsonObject {
                                put("status", status.name)
                                put("toolCount", toolNames.size)
                                put("foregroundRequiredToolCount", statusTools.count { tool -> tool.foregroundRequired })
                                put(
                                    "sampleTools",
                                    buildJsonArray {
                                        toolNames.take(limit).forEach { toolName ->
                                            add(JsonPrimitive(toolName))
                                        }
                                    },
                                )
                                if (toolNames.size > limit) {
                                    put("sampleToolsOmitted", toolNames.size - limit)
                                }
                            },
                        )
                    }
                }
            },
        )
    }
}

private fun ToolDescriptor.toToolAvailabilityMatchPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(
                        buildJsonObject {
                            put("permission", permission.permission)
                            put("displayName", permission.displayName)
                        },
                    )
                }
            },
        )
    }

private fun List<ToolDescriptor>.toToolPermissionDiscoveryPayload(limit: Int): JsonObject {
    val permissionGroups =
        flatMap { tool ->
            tool.requiredPermissions.map { permission -> tool to permission }
        }.groupBy { (_, permission) -> permission.permission to permission.displayName }
            .toList()
            .sortedWith(
                compareBy<Pair<Pair<String, String>, List<Pair<ToolDescriptor, ToolPermissionRequirement>>>> { entry ->
                    entry.first.first
                }.thenBy { entry -> entry.first.second },
            )
    val limitedGroups = permissionGroups.take(limit)
    return buildJsonObject {
        put("permission", JsonNull)
        put("limit", limit)
        put("toolCount", count { tool -> tool.requiredPermissions.isNotEmpty() })
        put("uniquePermissionCount", permissionGroups.size)
        put("requirementCount", sumOf { tool -> tool.requiredPermissions.size })
        put("resultCount", limitedGroups.size)
        if (permissionGroups.size > limitedGroups.size) {
            put("omittedCount", permissionGroups.size - limitedGroups.size)
        }
        put(
            "permissions",
            buildJsonArray {
                limitedGroups.forEach { (permissionKey, entries) ->
                    val toolNames = entries.map { (tool, _) -> tool.name }.distinct().sorted()
                    add(
                        buildJsonObject {
                            put("permission", permissionKey.first)
                            put("displayName", permissionKey.second)
                            put("toolCount", toolNames.size)
                            put("requirementCount", entries.size)
                            put("availabilityStats", entries.map { (tool, _) -> tool }.toToolAvailabilityStatsByStatusPayload())
                            put(
                                "sampleTools",
                                buildJsonArray {
                                    toolNames.take(5).forEach { toolName ->
                                        add(JsonPrimitive(toolName))
                                    }
                                },
                            )
                            if (toolNames.size > 5) {
                                put("sampleToolsOmitted", toolNames.size - 5)
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun List<ToolDescriptor>.toToolAvailabilityStatsByStatusPayload(): JsonArray {
    val countsByStatus = groupingBy { tool -> tool.availability.status }.eachCount()
    return buildJsonArray {
        ToolAvailabilityStatus.entries.forEach { status ->
            countsByStatus[status]?.let { count ->
                add(
                    buildJsonObject {
                        put("status", status.name)
                        put("toolCount", count)
                    },
                )
            }
        }
    }
}

private fun ToolDescriptor.toToolPermissionMatchPayload(
    matchingPermissions: List<ToolPermissionRequirement>,
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("requiredPermissionCount", requiredPermissions.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "matchingPermissions",
            buildJsonArray {
                matchingPermissions.forEach { permission ->
                    add(permission.toToolPermissionPayload())
                }
            },
        )
    }

private fun ToolPermissionRequirement.toToolPermissionPayload(): JsonObject =
    buildJsonObject {
        put("permission", permission)
        put("displayName", displayName)
    }

private fun ToolPermissionRequirement.matchesPermissionQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    return permission.lowercase().contains(normalizedQuery) ||
        displayName.lowercase().contains(normalizedQuery)
}

private fun List<ToolDescriptor>.toToolNamespaceDiscoveryPayload(limit: Int): JsonObject {
    val namespaceGroups =
        groupBy { tool -> tool.toolNamespace() }
            .toList()
            .sortedBy { (namespace, _) -> namespace }
    val limitedGroups = namespaceGroups.take(limit)
    return buildJsonObject {
        put("namespace", JsonNull)
        put("limit", limit)
        put("toolCount", size)
        put("namespaceCount", namespaceGroups.size)
        put("resultCount", limitedGroups.size)
        if (namespaceGroups.size > limitedGroups.size) {
            put("omittedCount", namespaceGroups.size - limitedGroups.size)
        }
        put(
            "namespaces",
            buildJsonArray {
                limitedGroups.forEach { (namespace, namespaceTools) ->
                    val toolNames = namespaceTools.map { tool -> tool.name }.sorted()
                    add(
                        buildJsonObject {
                            put("namespace", namespace)
                            put("toolCount", namespaceTools.size)
                            put("aliasCount", namespaceTools.sumOf { tool -> tool.aliases.size })
                            put("argumentCount", namespaceTools.sumOf { tool -> tool.arguments.size })
                            put(
                                "requiredArgumentCount",
                                namespaceTools.sumOf { tool -> tool.arguments.count { argument -> argument.required } },
                            )
                            put("requiredPermissionCount", namespaceTools.sumOf { tool -> tool.requiredPermissions.size })
                            put("availabilityStats", namespaceTools.toToolAvailabilityStatsByStatusPayload())
                            put(
                                "sampleTools",
                                buildJsonArray {
                                    toolNames.take(8).forEach { toolName ->
                                        add(JsonPrimitive(toolName))
                                    }
                                },
                            )
                            if (toolNames.size > 8) {
                                put("sampleToolsOmitted", toolNames.size - 8)
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun ToolDescriptor.toToolNamespaceMatchPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("namespace", toolNamespace())
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("aliasCount", aliases.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put("requiredPermissionCount", requiredPermissions.size)
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
    }

private fun ToolDescriptor.toolNamespace(): String = name.substringBefore(".", name)

private fun ToolDescriptor.toToolDescriptorPayload(includeInputSchema: Boolean): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put("foregroundRequired", foregroundRequired)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(
                        buildJsonObject {
                            put("permission", permission.permission)
                            put("displayName", permission.displayName)
                        },
                    )
                }
            },
        )
        put(
            "arguments",
            buildJsonArray {
                arguments.forEach { argument ->
                    add(
                        buildJsonObject {
                            put("name", argument.name)
                            put("required", argument.required)
                            put("description", argument.description)
                        },
                    )
                }
            },
        )
        put("inputSchema", if (includeInputSchema) inputSchema else JsonNull)
    }

private fun skillToggleDescriptor(
    name: String,
    aliases: List<String>,
    description: String,
): ToolDescriptor =
    ToolDescriptor(
        name = name,
        aliases = aliases,
        description = description,
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "skillId",
                    required = false,
                    description = "Skill id, key, or display name.",
                ),
            ),
    )

private fun JsonObject.skillIdentifier(): String? = optionalText("skillId") ?: optionalText("id") ?: optionalText("name")

private fun List<SkillSnapshot>.findByIdentifier(identifier: String): SkillSnapshot? =
    firstOrNull { candidate ->
        candidate.id.equals(identifier, ignoreCase = true) ||
            candidate.skillKey.equals(identifier, ignoreCase = true) ||
            candidate.displayName.equals(identifier, ignoreCase = true)
    }

private fun invalidSkillArguments(
    toolName: String,
    summary: String,
    field: String = "skillId",
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "INVALID_ARGUMENTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", toolName)
                put("field", field)
            },
    )

private fun skillNotFoundResult(
    toolName: String,
    skillId: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Skill $skillId was not found.",
        errorCode = "SKILL_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "SKILL_NOT_FOUND")
                put("toolName", toolName)
                put("skillId", skillId)
            },
    )

private fun skillConfigNotFoundResult(
    toolName: String,
    skillId: String,
    configPath: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Config path $configPath was not declared for skill $skillId.",
        errorCode = "SKILL_CONFIG_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "SKILL_CONFIG_NOT_FOUND")
                put("toolName", toolName)
                put("skillId", skillId)
                put("configPath", configPath)
            },
    )

private fun skillSecretNotFoundResult(
    toolName: String,
    skillId: String,
    envName: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Secret $envName was not declared for skill $skillId.",
        errorCode = "SKILL_SECRET_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "SKILL_SECRET_NOT_FOUND")
                put("toolName", toolName)
                put("skillId", skillId)
                put("envName", envName)
            },
    )

private fun SkillSnapshot.matchesSkillQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    return listOf(
        id,
        skillKey,
        displayName,
        frontmatter?.description.orEmpty(),
        frontmatter?.homepage.orEmpty(),
        frontmatter?.commandTool.orEmpty(),
        instructionsMd,
    ).any { value -> value.lowercase().contains(normalizedQuery) }
}

private fun SkillSnapshot.toSkillSearchPayload(): JsonObject {
    val instructionSnippet =
        if (instructionsMd.length <= SKILL_SEARCH_SNIPPET_MAX_CHARS) {
            instructionsMd
        } else {
            instructionsMd.take(SKILL_SEARCH_SNIPPET_MAX_CHARS)
        }
    return buildJsonObject {
        put("id", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("eligibilityStatus", eligibility.status.name)
        put("description", frontmatter?.description?.let(::JsonPrimitive) ?: JsonNull)
        put("commandDispatch", frontmatter?.commandDispatch?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("commandTool", frontmatter?.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("instructionsSnippet", instructionSnippet)
        put("instructionsLength", instructionsMd.length)
        put("instructionsTruncated", instructionSnippet.length < instructionsMd.length)
    }
}

private fun SkillSnapshot.toDefaultConfigurationSnapshot(): SkillConfigurationSnapshot =
    SkillConfigurationSnapshot(
        skillId = id,
        skillKey = skillKey,
        displayName = displayName,
        secretFields =
            secretStatuses.map { (envName, configured) ->
                SkillSecretField(
                    envName = envName,
                    configured = configured,
                )
            },
        configFields =
            configStatuses.map { (path, configured) ->
                SkillConfigField(
                    path = path,
                    value = if (configured) "" else null,
                )
            },
    )

private fun SkillConfigurationSnapshot.withUpdatedConfigField(
    configPath: String,
    value: String?,
): SkillConfigurationSnapshot =
    copy(
        configFields =
            configFields.map { field ->
                if (field.path == configPath) {
                    field.copy(value = value)
                } else {
                    field
                }
            },
    )

private fun SkillConfigurationSnapshot.withClearedSecretField(envName: String): SkillConfigurationSnapshot =
    copy(
        secretFields =
            secretFields.map { field ->
                if (field.envName == envName) {
                    field.copy(configured = false)
                } else {
                    field
                }
            },
    )

private fun SkillConfigurationSnapshot.toSkillConfigurationPayload(): JsonObject =
    buildJsonObject {
        put("skillId", skillId?.let(::JsonPrimitive) ?: JsonNull)
        put("skillKey", skillKey)
        put("displayName", displayName?.let(::JsonPrimitive) ?: JsonNull)
        put("secretFieldCount", secretFields.size)
        put("configuredSecretFieldCount", secretFields.count { field -> field.configured })
        put("configFieldCount", configFields.size)
        put("configuredConfigFieldCount", configFields.count { field -> field.value != null })
        put("recoveryMessage", recoveryMessage?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "secretFields",
            buildJsonArray {
                secretFields.forEach { field ->
                    add(
                        buildJsonObject {
                            put("envName", field.envName)
                            put("configured", field.configured)
                        },
                    )
                }
            },
        )
        put(
            "configFields",
            buildJsonArray {
                configFields.forEach { field ->
                    val value = field.value
                    add(
                        buildJsonObject {
                            put("path", field.path)
                            put("configured", value != null)
                            put("value", value?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                }
            },
        )
    }

private fun List<SkillSnapshot>.toSkillStatsPayload(): JsonObject {
    val totalSecretFieldCount = sumOf { skill -> skill.secretStatuses.size }
    val missingSecretFieldCount = sumOf { skill -> skill.secretStatuses.count { (_, configured) -> !configured } }
    val totalConfigFieldCount = sumOf { skill -> skill.configStatuses.size }
    val missingConfigFieldCount = sumOf { skill -> skill.configStatuses.count { (_, configured) -> !configured } }
    val modelReadySkillCount =
        count { skill ->
            val frontmatter = skill.frontmatter ?: return@count false
            skill.enabled &&
                skill.resolutionState == SkillResolutionState.Effective &&
                skill.eligibility.status == SkillEligibilityStatus.Eligible &&
                frontmatter.commandDispatch == SkillCommandDispatch.Model &&
                !frontmatter.disableModelInvocation
        }
    return buildJsonObject {
        put("skillCount", size)
        put("enabledSkillCount", count { skill -> skill.enabled })
        put("disabledSkillCount", count { skill -> !skill.enabled })
        put("eligibleSkillCount", count { skill -> skill.eligibility.status == SkillEligibilityStatus.Eligible })
        put("ineligibleSkillCount", count { skill -> skill.eligibility.status != SkillEligibilityStatus.Eligible })
        put("modelReadySkillCount", modelReadySkillCount)
        put("toolDispatchSkillCount", count { skill -> skill.frontmatter?.commandDispatch == SkillCommandDispatch.Tool })
        put("missingFrontmatterCount", count { skill -> skill.frontmatter == null })
        put("parseErrorCount", count { skill -> skill.parseError != null })
        put("skillsWithSecretFieldsCount", count { skill -> skill.secretStatuses.isNotEmpty() })
        put("totalSecretFieldCount", totalSecretFieldCount)
        put("missingSecretFieldCount", missingSecretFieldCount)
        put("skillsWithConfigFieldsCount", count { skill -> skill.configStatuses.isNotEmpty() })
        put("totalConfigFieldCount", totalConfigFieldCount)
        put("missingConfigFieldCount", missingConfigFieldCount)
        put(
            "sourceTypeStats",
            buildJsonArray {
                groupedCounts { skill -> skill.sourceType.name }
                    .forEach { (sourceType, count) ->
                        add(namedCountPayload(nameField = "sourceType", name = sourceType, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "eligibilityStats",
            buildJsonArray {
                groupedCounts { skill -> skill.eligibility.status.name }
                    .forEach { (status, count) ->
                        add(namedCountPayload(nameField = "eligibilityStatus", name = status, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "commandDispatchStats",
            buildJsonArray {
                groupedCounts { skill -> skill.frontmatter?.commandDispatch?.name ?: "MissingFrontmatter" }
                    .forEach { (dispatch, count) ->
                        add(namedCountPayload(nameField = "commandDispatch", name = dispatch, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "resolutionStateStats",
            buildJsonArray {
                groupedCounts { skill -> skill.resolutionState.name }
                    .forEach { (state, count) ->
                        add(namedCountPayload(nameField = "resolutionState", name = state, countField = "skillCount", count = count))
                    }
            },
        )
    }
}

private fun List<SkillSnapshot>.groupedCounts(selector: (SkillSnapshot) -> String): List<Pair<String, Int>> =
    groupingBy(selector)
        .eachCount()
        .toList()
        .sortedBy { (name, _) -> name }

private fun namedCountPayload(
    nameField: String,
    name: String,
    countField: String,
    count: Int,
): JsonObject =
    buildJsonObject {
        put(nameField, name)
        put(countField, count)
    }

private fun SkillSnapshot.toSkillDetailPayload(includeInstructions: Boolean): JsonObject {
    val instructionsSnippet =
        if (instructionsMd.length <= SKILL_INSTRUCTIONS_MAX_CHARS) {
            instructionsMd
        } else {
            instructionsMd.take(SKILL_INSTRUCTIONS_MAX_CHARS)
        }
    return buildJsonObject {
        put("id", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("baseDir", baseDir)
        put("resolutionState", resolutionState.name)
        put("shadowedBy", shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", eligibility.status.name)
        put(
            "eligibilityReasons",
            buildJsonArray {
                eligibility.reasons.forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "secretStatuses",
            buildJsonArray {
                secretStatuses.forEach { (envName, configured) ->
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
                configStatuses.forEach { (path, configured) ->
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("configured", configured)
                        },
                    )
                }
            },
        )
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "frontmatter",
            frontmatter?.let { metadata ->
                buildJsonObject {
                    put("name", metadata.name)
                    put("description", metadata.description)
                    put("homepage", metadata.homepage?.let(::JsonPrimitive) ?: JsonNull)
                    put("userInvocable", metadata.userInvocable)
                    put("disableModelInvocation", metadata.disableModelInvocation)
                    put("commandDispatch", metadata.commandDispatch.name)
                    put("commandTool", metadata.commandTool?.let(::JsonPrimitive) ?: JsonNull)
                    put("commandArgMode", metadata.commandArgMode)
                    put("metadata", metadata.metadata ?: JsonNull)
                    put(
                        "unknownFields",
                        buildJsonObject {
                            metadata.unknownFields.forEach { (field, value) ->
                                put(field, value)
                            }
                        },
                    )
                }
            } ?: JsonNull,
        )
        put("instructionsIncluded", includeInstructions)
        put("instructionsLength", instructionsMd.length)
        put("instructionsTruncated", instructionsSnippet.length < instructionsMd.length)
        put("instructionsMd", if (includeInstructions) JsonPrimitive(instructionsSnippet) else JsonNull)
    }
}

private fun String.toMessageSearchSnippet(): String =
    if (length <= MESSAGE_SEARCH_SNIPPET_MAX_CHARS) {
        this
    } else {
        take(MESSAGE_SEARCH_SNIPPET_MAX_CHARS)
    }

private fun ChatMessage.toMessageContextPayload(
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

private fun ChatMessage.toMessageReferencePayload(session: Session?): JsonObject {
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

private fun ChatMessage.toMessagePagePayload(): JsonObject {
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

private fun MessageRepository.RoleMessageStats.toMessageRoleStatsPayload(): JsonObject =
    buildJsonObject {
        put("role", role.name)
        put("messageCount", messageCount)
        put("contentCharCount", contentCharCount)
        put("oldestMessageAtIso", oldestMessageAt.toString())
        put("newestMessageAtIso", newestMessageAt.toString())
    }

private fun Session.toSessionSummaryPayload(): JsonObject {
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

private fun SessionRepository.SessionActivity.toSessionActivityPayload(): JsonObject {
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

private fun SessionRepository.SessionStats.toSessionStatsPayload(): JsonObject =
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

private fun TaskRepository.TaskStats.toTaskStatsPayload(minimumBackgroundIntervalMinutes: Long): JsonObject =
    buildJsonObject {
        put("supportsOnce", true)
        put("supportsInterval", true)
        put("supportsCron", true)
        put("minimumBackgroundIntervalMinutes", minimumBackgroundIntervalMinutes)
        put("taskCount", totalTaskCount)
        put("enabledTaskCount", enabledTaskCount)
        put("disabledTaskCount", disabledTaskCount)
        put("scheduledTaskCount", scheduledTaskCount)
        put("dueTaskCount", dueTaskCount)
        put("nextEnabledRunAtIso", nextEnabledRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestTaskUpdatedAtIso", newestTaskUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("runCount", totalRunCount)
        put("oldestRunScheduledAtIso", oldestRunScheduledAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestRunScheduledAtIso", newestRunScheduledAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put(
            "scheduleKindStats",
            buildJsonArray {
                scheduleKindStats.forEach { stats ->
                    add(
                        buildJsonObject {
                            put("scheduleKind", stats.scheduleKind)
                            put("taskCount", stats.taskCount)
                        },
                    )
                }
            },
        )
        put(
            "executionModeStats",
            buildJsonArray {
                executionModeStats.forEach { stats ->
                    add(
                        buildJsonObject {
                            put("executionMode", stats.executionMode.name)
                            put("taskCount", stats.taskCount)
                        },
                    )
                }
            },
        )
        put(
            "runStatusStats",
            buildJsonArray {
                runStatusStats.forEach { stats ->
                    add(
                        buildJsonObject {
                            put("status", stats.status.name)
                            put("runCount", stats.runCount)
                            put("oldestScheduledAtIso", stats.oldestScheduledAt.toString())
                            put("newestScheduledAtIso", stats.newestScheduledAt.toString())
                        },
                    )
                }
            },
        )
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

private fun TaskRun.toTaskRunWithTaskPayload(task: Task?): JsonObject =
    buildJsonObject {
        put("run", toTaskRunHistoryPayload())
        put("taskId", taskId)
        put("taskAvailable", task != null)
        put("taskName", task?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("taskEnabled", task?.enabled?.let(::JsonPrimitive) ?: JsonNull)
        put("taskNextRunAtIso", task?.nextRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("taskFailureCount", task?.failureCount?.let(::JsonPrimitive) ?: JsonNull)
        put("taskMaxRetries", task?.maxRetries?.let(::JsonPrimitive) ?: JsonNull)
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

private fun Task.toUpcomingTaskPayload(now: Instant): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    val nextRun = nextRunAt
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRun?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("due", nextRun?.isAfter(now) == false)
        put("secondsUntilRun", nextRun?.let { Duration.between(now, it).seconds }?.let(::JsonPrimitive) ?: JsonNull)
        put("promptSnippet", promptSnippet)
        put("promptLength", prompt.length)
        put("promptTruncated", promptSnippet.length < prompt.length)
    }
}

private fun Task.toDueTaskPayload(now: Instant): JsonObject {
    val promptSnippet = prompt.toMessageSearchSnippet()
    val nextRun = nextRunAt
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("scheduleKind", schedule.toTaskSearchKind())
        put("executionMode", executionMode.name)
        put("targetSessionId", targetSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("nextRunAtIso", nextRun?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("lastRunAtIso", lastRunAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("due", nextRun?.isAfter(now) == false)
        put(
            "secondsOverdue",
            nextRun
                ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
                ?.let(::JsonPrimitive)
                ?: JsonNull,
        )
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

private fun kotlinx.serialization.json.JsonObject.optionalRawText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { value -> value.isNotBlank() }
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

private fun JsonObject.optionalMessageReferenceId(field: String): String? =
    optionalText(field)
        ?.take(MESSAGE_REFERENCE_ID_MAX_CHARS)
        ?.ifBlank { null }

private fun JsonObject.optionalMessageRole(field: String): MessageRole? =
    when (optionalText(field)?.lowercase()?.replace("-", "_")) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        "tool_call", "toolcall", "tool" -> MessageRole.ToolCall
        "tool_result", "toolresult" -> MessageRole.ToolResult
        "system" -> MessageRole.System
        else -> null
    }

private fun String.toToolAvailabilityStatusOrNull(): ToolAvailabilityStatus? =
    when (lowercase().replace("-", "_").replace(" ", "_")) {
        "available" -> ToolAvailabilityStatus.Available
        "unavailable" -> ToolAvailabilityStatus.Unavailable
        "permission_required", "permissionrequired", "permission" -> ToolAvailabilityStatus.PermissionRequired
        "foreground_required", "foregroundrequired", "foreground" -> ToolAvailabilityStatus.ForegroundRequired
        "disabled_by_config", "disabledbyconfig", "disabled" -> ToolAvailabilityStatus.DisabledByConfig
        else -> null
    }

private fun String.toMessagePageDirectionOrNull(): MessagePageDirection? =
    when (lowercase().replace("-", "_")) {
        "start", "first", "oldest", "from_start" -> MessagePageDirection.Start
        "recent", "latest", "last", "end" -> MessagePageDirection.Recent
        "before" -> MessagePageDirection.Before
        "after" -> MessagePageDirection.After
        else -> null
    }

private fun JsonObject.parseTaskSnoozeUntil(now: Instant): Instant {
    val untilText = optionalText("untilIso")
    val delayText = optionalText("delayMinutes")
    if (untilText != null && delayText != null) {
        throw IllegalArgumentException("tasks.snooze accepts either untilIso or delayMinutes, not both.")
    }
    if (untilText != null) {
        val until =
            try {
                Instant.parse(untilText)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("tasks.snooze requires untilIso to be an ISO-8601 instant.", error)
            }
        require(until.isAfter(now)) { "tasks.snooze requires untilIso to be after now." }
        requireTaskSnoozeDelay(Duration.between(now, until))
        return until
    }
    val delayMinutes =
        delayText
            ?.toLongOrNull()
            ?: if (delayText == null) {
                TASK_SNOOZE_DEFAULT_DELAY_MINUTES
            } else {
                throw IllegalArgumentException("tasks.snooze received a non-numeric delayMinutes.")
            }
    require(delayMinutes > 0L) { "tasks.snooze requires delayMinutes > 0." }
    require(delayMinutes <= TASK_SNOOZE_MAX_DELAY_MINUTES) {
        "tasks.snooze requires delayMinutes <= $TASK_SNOOZE_MAX_DELAY_MINUTES."
    }
    return now.plus(Duration.ofMinutes(delayMinutes))
}

private fun requireTaskSnoozeDelay(delay: Duration) {
    require(!delay.isZero && !delay.isNegative) { "tasks.snooze requires a future snooze time." }
    require(delay <= Duration.ofMinutes(TASK_SNOOZE_MAX_DELAY_MINUTES)) {
        "tasks.snooze requires snooze delay <= $TASK_SNOOZE_MAX_DELAY_MINUTES minutes."
    }
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
