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

internal fun skillToolEntries(
    bundledSkillsProvider: suspend () -> List<SkillSnapshot>,
    skillEnabledUpdater: suspend (skillId: String, enabled: Boolean) -> Unit,
    skillInventoryRefresher: suspend (sessionId: String?, forceRefresh: Boolean) -> List<SkillSnapshot>,
    skillConfigurationReader: suspend (SkillSnapshot) -> SkillConfigurationSnapshot,
    skillConfigurationUpdater: suspend (SkillSnapshot, String, String?) -> SkillConfigurationSnapshot,
    skillSecretClearer: suspend (SkillSnapshot, String) -> SkillConfigurationSnapshot,
    skillPackageImporter: suspend (List<SkillPackageImportEntry>, Boolean, Boolean) -> SkillImportResult,
    clock: Clock,
): List<ToolRegistry.Entry> =
    buildList {
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
                                    name = "skills.setup",
                                    aliases =
                                        listOf(
                                            "skill.setup",
                                            "skills.quickstart",
                                            "skill.quickstart",
                                            "skills.ready",
                                            "skill.ready",
                                        ),
                                    description = "Return a read-only skill setup guide for enablement, eligibility, config, and secrets.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Skill id, key, or display name.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit setupMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.skillIdentifier()
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.setup",
                                        summary = "skills.setup requires a non-empty skillId.",
                                    )
                            val skills = bundledSkillsProvider()
                            val skill =
                                skills.findByIdentifier(identifier)
                                    ?: return@Entry skillNotFoundResult(toolName = "skills.setup", skillId = identifier)
                            val configuration = skillConfigurationReader(skill)
                            val requirements =
                                skill.toSkillSetupRequirements(
                                    configuration = configuration,
                                )
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val setupMarkdown =
                                if (includeMarkdown) {
                                    skill.toSkillSetupMarkdown(
                                        configuration = configuration,
                                        requirements = requirements,
                                        requestedSkillId = identifier,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (requirements.isEmpty()) {
                                        "Skill ${skill.displayName} is ready."
                                    } else {
                                        "Skill ${skill.displayName} needs ${requirements.size} setup step(s)."
                                    },
                                payload =
                                    skill.toSkillSetupPayload(
                                        configuration = configuration,
                                        requirements = requirements,
                                        requestedSkillId = identifier,
                                        includeMarkdown = includeMarkdown,
                                        setupMarkdown = setupMarkdown,
                                    ),
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.setup.matrix",
                                    aliases =
                                        listOf(
                                            "skill.setup.matrix",
                                            "skills.setup.all",
                                            "skill.setup.all",
                                            "skills.readiness",
                                            "skills.onboarding",
                                        ),
                                    description = "Return a bounded read-only setup readiness matrix for skills.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "includeDisabled",
                                                description = "Set false to omit disabled skills. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeRequirements",
                                                description = "Set false to omit per-skill requirement details. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit matrixMarkdown. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum skill count to include. Defaults to 20.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val skills = bundledSkillsProvider()
                            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
                            val includeRequirements = arguments.optionalBoolean("includeRequirements", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_SETUP_MATRIX_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_SETUP_MATRIX_MAX_LIMIT)
                            val candidates =
                                skills
                                    .filter { skill -> includeDisabled || skill.enabled }
                            val includedSkills = candidates.take(limit)
                            val entries =
                                includedSkills.map { skill ->
                                    val configuration = skillConfigurationReader(skill)
                                    SkillSetupReadinessEntry(
                                        skill = skill,
                                        configuration = configuration,
                                        requirements = skill.toSkillSetupRequirements(configuration = configuration),
                                    )
                                }
                            val matrixMarkdown =
                                if (includeMarkdown) {
                                    entries.toSkillSetupMatrixMarkdown(
                                        skillCount = skills.size,
                                        candidateSkillCount = candidates.size,
                                        limit = limit,
                                        includeDisabled = includeDisabled,
                                        includeRequirements = includeRequirements,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    "Prepared setup readiness matrix for ${entries.size} skill(s); " +
                                        "${entries.count { entry -> entry.readyForUse }} ready.",
                                payload =
                                    entries.toSkillSetupMatrixPayload(
                                        skillCount = skills.size,
                                        candidateSkillCount = candidates.size,
                                        limit = limit,
                                        includeDisabled = includeDisabled,
                                        includeRequirements = includeRequirements,
                                        includeMarkdown = includeMarkdown,
                                        matrixMarkdown = matrixMarkdown,
                                    ),
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
                                    name = "skills.commands",
                                    aliases =
                                        listOf(
                                            "skill.commands",
                                            "skills.slash",
                                            "skill.slash",
                                            "skills.command_index",
                                            "skill.command_index",
                                        ),
                                    description = "List skill slash-command dispatch metadata without SKILL.md instruction bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum command entries to include. Defaults to 20, max 100.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeDisabled",
                                                description = "Set true to include disabled skills. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeNonInvocable",
                                                description = "Set true to include skills not marked user-invocable. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit commandsMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_COMMANDS_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_COMMANDS_MAX_LIMIT)
                            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = false)
                            val includeNonInvocable = arguments.optionalBoolean("includeNonInvocable", defaultValue = false)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val skills = bundledSkillsProvider()
                            val commandSkills = skills.filter { skill -> skill.frontmatter != null }
                            val candidates =
                                commandSkills.filter { skill ->
                                    val frontmatter = requireNotNull(skill.frontmatter)
                                    (includeDisabled || skill.enabled) &&
                                        (includeNonInvocable || frontmatter.userInvocable)
                                }
                            val commandNameCounts =
                                candidates
                                    .mapNotNull { skill -> skill.frontmatter?.name }
                                    .groupingBy { commandName -> commandName }
                                    .eachCount()
                            val includedCommands = candidates.take(limit)
                            val commandsMarkdown =
                                if (includeMarkdown) {
                                    includedCommands.toSkillCommandsMarkdown(
                                        totalSkillCount = skills.size,
                                        declaredCommandCount = commandSkills.size,
                                        candidateCommandCount = candidates.size,
                                        limit = limit,
                                        includeDisabled = includeDisabled,
                                        includeNonInvocable = includeNonInvocable,
                                        commandNameCounts = commandNameCounts,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (includedCommands.isEmpty()) {
                                        "No skill commands matched the filters."
                                    } else {
                                        "Loaded ${includedCommands.size} skill command(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("skillCount", skills.size)
                                        put("declaredCommandCount", commandSkills.size)
                                        put("candidateCommandCount", candidates.size)
                                        put("includedCommandCount", includedCommands.size)
                                        put("omittedCommandCount", (candidates.size - includedCommands.size).coerceAtLeast(0))
                                        put("invocableCommandCount", candidates.count { skill -> skill.isSkillCommandInvocable() })
                                        put(
                                            "modelDispatchCommandCount",
                                            candidates.count { skill -> skill.frontmatter?.commandDispatch == SkillCommandDispatch.Model },
                                        )
                                        put(
                                            "toolDispatchCommandCount",
                                            candidates.count { skill -> skill.frontmatter?.commandDispatch == SkillCommandDispatch.Tool },
                                        )
                                        put("duplicateCommandNameCount", commandNameCounts.count { (_, count) -> count > 1 })
                                        put("duplicatedCommandCount", candidates.count { skill -> (commandNameCounts[skill.frontmatter?.name] ?: 0) > 1 })
                                        put("limit", limit)
                                        put("includeDisabled", includeDisabled)
                                        put("includeNonInvocable", includeNonInvocable)
                                        put("includeMarkdown", includeMarkdown)
                                        put("instructionsOmitted", true)
                                        put("secretValuesOmitted", true)
                                        put(
                                            "commands",
                                            buildJsonArray {
                                                includedCommands.forEach { skill ->
                                                    add(
                                                        skill.toSkillCommandPayload(
                                                            duplicateCommandCount =
                                                                commandNameCounts[skill.frontmatter?.name] ?: 1,
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        put("commandsMarkdown", commandsMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.command.example",
                                    aliases =
                                        listOf(
                                            "skill.command.example",
                                            "skills.slash.example",
                                            "skill.slash.example",
                                            "skills.command.sample",
                                            "skill.command.sample",
                                        ),
                                    description = "Return a safe slash-command invocation example without executing the command.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "commandName",
                                                required = false,
                                                description = "Command name, slash command, skill id, skill key, or display name.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Alternative exact skill id/key/display name.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit exampleMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier =
                                arguments.optionalText("commandName")
                                    ?: arguments.optionalText("command")
                                    ?: arguments.optionalText("skillId")
                                    ?: arguments.optionalText("id")
                                    ?: arguments.optionalText("name")
                                    ?: return@Entry invalidSkillArguments(
                                        toolName = "skills.command.example",
                                        summary = "skills.command.example requires a non-empty commandName or skillId.",
                                        field = "commandName",
                                    )
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val skills = bundledSkillsProvider()
                            val commandSkills = skills.filter { skill -> skill.frontmatter != null }
                            val normalizedIdentifier = identifier.removePrefix("/").trim()
                            val matches =
                                commandSkills.filter { skill ->
                                    val frontmatter = requireNotNull(skill.frontmatter)
                                    frontmatter.name.equals(normalizedIdentifier, ignoreCase = true) ||
                                        skill.id.equals(normalizedIdentifier, ignoreCase = true) ||
                                        skill.skillKey.equals(normalizedIdentifier, ignoreCase = true) ||
                                        skill.displayName.equals(normalizedIdentifier, ignoreCase = true)
                                }
                            val selectedSkill =
                                matches
                                    .sortedWith(
                                        compareByDescending<SkillSnapshot> { skill -> skill.isSkillCommandInvocable() }
                                            .thenBy { skill -> skill.displayName },
                                    ).firstOrNull()
                                    ?: return@Entry skillNotFoundResult(
                                        toolName = "skills.command.example",
                                        skillId = identifier,
                                    )
                            val frontmatter = requireNotNull(selectedSkill.frontmatter)
                            val commandNameCounts =
                                commandSkills
                                    .mapNotNull { skill -> skill.frontmatter?.name }
                                    .groupingBy { commandName -> commandName }
                                    .eachCount()
                            val duplicateCommandCount = commandNameCounts[frontmatter.name] ?: 1
                            val exampleMarkdown =
                                if (includeMarkdown) {
                                    selectedSkill.toSkillCommandExampleMarkdown(
                                        duplicateCommandCount = duplicateCommandCount,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary = "Prepared slash-command example for /${frontmatter.name} without executing it.",
                                payload =
                                    selectedSkill.toSkillCommandExamplePayload(
                                        requestedCommand = identifier,
                                        duplicateCommandCount = duplicateCommandCount,
                                        includeMarkdown = includeMarkdown,
                                        exampleMarkdown = exampleMarkdown,
                                    ),
                            )
                        },
                    )
                    add(
                        ToolRegistry.Entry(
                            descriptor =
                                ToolDescriptor(
                                    name = "skills.doctor",
                                    aliases =
                                        listOf(
                                            "skill.doctor",
                                            "skills.check",
                                            "skill.check",
                                        ),
                                    description = "Return actionable skill diagnostics without SKILL.md instruction bodies.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum diagnostic issues to include. Defaults to 20.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeDisabled",
                                                description = "Set false to omit disabled skills before diagnostics. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_DOCTOR_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_DOCTOR_MAX_LIMIT)
                            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val skills = bundledSkillsProvider()
                            val candidates =
                                if (includeDisabled) {
                                    skills
                                } else {
                                    skills.filter { skill -> skill.enabled }
                                }
                            val issues = candidates.flatMap { skill -> skill.toSkillDoctorIssues() }
                            val includedIssues = issues.take(limit)
                            val status = issues.toSkillDoctorStatus()
                            val doctorMarkdown =
                                if (includeMarkdown) {
                                    includedIssues.toSkillDoctorMarkdown(
                                        status = status,
                                        totalSkillCount = skills.size,
                                        candidateSkillCount = candidates.size,
                                        issueCount = issues.size,
                                        limit = limit,
                                        includeDisabled = includeDisabled,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    when {
                                        issues.isEmpty() ->
                                            "Skills doctor found no issues across ${candidates.size} candidate skill(s)."
                                        includedIssues.size == issues.size ->
                                            "Skills doctor found ${issues.size} issue(s) across ${candidates.size} candidate skill(s)."
                                        else ->
                                            "Skills doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("status", status)
                                        put("skillCount", skills.size)
                                        put("candidateSkillCount", candidates.size)
                                        put("issueCount", issues.size)
                                        put("includedIssueCount", includedIssues.size)
                                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                                        put("limit", limit)
                                        put("includeDisabled", includeDisabled)
                                        put("includeMarkdown", includeMarkdown)
                                        put("instructionsOmitted", true)
                                        put("secretValuesOmitted", true)
                                        put("stats", skills.toSkillStatsPayload())
                                        put(
                                            "issues",
                                            buildJsonArray {
                                                includedIssues.forEach { issue ->
                                                    add(issue.toSkillDoctorPayload())
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
                                    name = "skills.handoff",
                                    aliases =
                                        listOf(
                                            "skill.handoff",
                                            "skills.snapshot",
                                            "skill.snapshot",
                                        ),
                                    description = "Return a compact skill inventory handoff without full SKILL.md instructions.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum skill entries to include. Defaults to 8.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeDisabled",
                                                description = "Set false to omit disabled skills. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_HANDOFF_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_HANDOFF_MAX_LIMIT)
                            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val skills = bundledSkillsProvider()
                            val candidates =
                                if (includeDisabled) {
                                    skills
                                } else {
                                    skills.filter { skill -> skill.enabled }
                                }
                            val includedSkills = candidates.take(limit)
                            val handoffMarkdown =
                                if (includeMarkdown) {
                                    includedSkills.toSkillHandoffMarkdown(
                                        totalSkillCount = skills.size,
                                        candidateSkillCount = candidates.size,
                                        limit = limit,
                                        includeDisabled = includeDisabled,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (skills.isEmpty()) {
                                        "Prepared empty skill handoff."
                                    } else {
                                        "Prepared skill handoff with ${includedSkills.size} of ${candidates.size} candidate skill(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("skillCount", skills.size)
                                        put("candidateSkillCount", candidates.size)
                                        put("includedSkillCount", includedSkills.size)
                                        put("omittedSkillCount", (candidates.size - includedSkills.size).coerceAtLeast(0))
                                        put("limit", limit)
                                        put("includeDisabled", includeDisabled)
                                        put("includeMarkdown", includeMarkdown)
                                        put("stats", skills.toSkillStatsPayload())
                                        put(
                                            "skills",
                                            buildJsonArray {
                                                includedSkills.forEach { skill ->
                                                    add(skill.toSkillHandoffPayload())
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
                                    name = "skills.export",
                                    aliases =
                                        listOf(
                                            "skill.export",
                                            "skills.backup",
                                            "skill.backup",
                                            "skills.package.export",
                                            "skill.package.export",
                                        ),
                                    description = "Export bounded non-secret skill definitions and optional config values.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skillId",
                                                required = false,
                                                description = "Optional skill id, key, or display name to include only one skill.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum skill entries to include. Defaults to 20, max 50.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeDisabled",
                                                description = "Set false to omit disabled skills. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeInstructions",
                                                description = "Set false to omit bounded SKILL.md instruction bodies. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeConfigValues",
                                                description = "Set true to include non-secret skill config values. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeMarkdown",
                                                description = "Set false to omit exportMarkdown. Defaults to true.",
                                            ),
                                        ),
                                ),
                        ) { _, arguments ->
                            val identifier = arguments.skillIdentifier()
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_EXPORT_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_EXPORT_MAX_LIMIT)
                            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
                            val includeInstructions = arguments.optionalBoolean("includeInstructions", defaultValue = true)
                            val includeConfigValues = arguments.optionalBoolean("includeConfigValues", defaultValue = false)
                            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
                            val skills = bundledSkillsProvider()
                            val selectedSkills =
                                if (identifier == null) {
                                    skills
                                } else {
                                    listOf(
                                        skills.findByIdentifier(identifier)
                                            ?: return@Entry skillNotFoundResult(
                                                toolName = "skills.export",
                                                skillId = identifier,
                                            ),
                                    )
                                }
                            val candidates =
                                if (includeDisabled) {
                                    selectedSkills
                                } else {
                                    selectedSkills.filter { skill -> skill.enabled }
                                }
                            val includedSkills = candidates.take(limit)
                            val configurations =
                                if (includeConfigValues) {
                                    includedSkills.associate { skill ->
                                        skill.id to skillConfigurationReader(skill)
                                    }
                                } else {
                                    emptyMap()
                                }
                            val exportMarkdown =
                                if (includeMarkdown) {
                                    includedSkills.toSkillExportMarkdown(
                                        totalSkillCount = skills.size,
                                        candidateSkillCount = candidates.size,
                                        limit = limit,
                                        includeDisabled = includeDisabled,
                                        includeInstructions = includeInstructions,
                                        includeConfigValues = includeConfigValues,
                                    )
                                } else {
                                    null
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (identifier == null) {
                                        "Prepared skill export with ${includedSkills.size} of ${candidates.size} candidate skill(s)."
                                    } else {
                                        "Prepared skill export for ${includedSkills.size} matching skill(s)."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("exportFormat", SKILL_EXPORT_FORMAT)
                                        put("exportVersion", SKILL_EXPORT_VERSION)
                                        put("generatedAtIso", clock.instant().toString())
                                        put("skillCount", skills.size)
                                        put("candidateSkillCount", candidates.size)
                                        put("includedSkillCount", includedSkills.size)
                                        put("omittedSkillCount", (candidates.size - includedSkills.size).coerceAtLeast(0))
                                        put("requestedSkillId", identifier?.let(::JsonPrimitive) ?: JsonNull)
                                        put("limit", limit)
                                        put("includeDisabled", includeDisabled)
                                        put("includeInstructions", includeInstructions)
                                        put("includeConfigValues", includeConfigValues)
                                        put("includeMarkdown", includeMarkdown)
                                        put("instructionsMaxChars", SKILL_EXPORT_INSTRUCTIONS_MAX_CHARS)
                                        put("secretValuesIncluded", false)
                                        put("secretValuesOmitted", true)
                                        put("secretStatusesIncluded", true)
                                        put("configValuesIncluded", includeConfigValues)
                                        put("baseDirIncluded", false)
                                        put("rawFrontmatterIncluded", false)
                                        put("stats", skills.toSkillStatsPayload())
                                        put(
                                            "skills",
                                            buildJsonArray {
                                                includedSkills.forEach { skill ->
                                                    add(
                                                        skill.toSkillExportPayload(
                                                            includeInstructions = includeInstructions,
                                                            configuration = configurations[skill.id],
                                                        ),
                                                    )
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
                                    name = "skills.import",
                                    aliases =
                                        listOf(
                                            "skill.import",
                                            "skills.restore",
                                            "skill.restore",
                                            "skills.package.import",
                                            "skill.package.import",
                                        ),
                                    description = "Import non-secret skill definitions from a skills.export payload.",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "skills",
                                                description = "Array of exported skill entries, or pass export.skills.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "export",
                                                description = "Optional skills.export payload containing a skills array.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "limit",
                                                description = "Maximum skill entries to scan. Defaults to 20, max 50.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "includeDisabled",
                                                description = "Set false to skip disabled source skill entries. Defaults to true.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "enableImported",
                                                description = "Set true to enable imported skills that were enabled in the source. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "importConfigValues",
                                                description = "Set true to restore non-secret config values when present. Defaults to false.",
                                            ),
                                            ToolArgumentSpec(
                                                name = "dryRun",
                                                description = "Set true to preview importable skills without writing. Defaults to false.",
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
                                return@Entry missingSkillImportConfirmationResult()
                            }
                            val rawEntries =
                                when (val parsedEntries = arguments.skillImportEntries()) {
                                    is SkillImportEntriesParseResult.Failure -> return@Entry parsedEntries.result
                                    is SkillImportEntriesParseResult.Success -> parsedEntries.entries
                                }
                            val limit =
                                arguments
                                    .optionalInt(
                                        field = "limit",
                                        defaultValue = SKILL_IMPORT_DEFAULT_LIMIT,
                                    ).coerceIn(0, SKILL_IMPORT_MAX_LIMIT)
                            val includeDisabled = arguments.optionalBoolean("includeDisabled", defaultValue = true)
                            val enableImported = arguments.optionalBoolean("enableImported", defaultValue = false)
                            val importConfigValues = arguments.optionalBoolean("importConfigValues", defaultValue = false)
                            val scannedEntries = rawEntries.take(limit)
                            val candidates = mutableListOf<SkillImportCandidate>()
                            val skipped = mutableListOf<SkillImportSkippedEntry>()
                            scannedEntries.forEachIndexed { sourceIndex, element ->
                                when (val parsedCandidate = element.toSkillImportCandidate(sourceIndex = sourceIndex)) {
                                    is SkillImportCandidateParseResult.Candidate -> {
                                        if (!includeDisabled && !parsedCandidate.candidate.sourceEnabled) {
                                            skipped +=
                                                SkillImportSkippedEntry(
                                                    sourceIndex = sourceIndex,
                                                    code = "skills.import.disabled_skipped",
                                                    summary = "Disabled skill entry skipped because includeDisabled=false.",
                                                )
                                        } else {
                                            candidates += parsedCandidate.candidate
                                        }
                                    }
                                    is SkillImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
                                }
                            }
                            val importResult =
                                if (dryRun || candidates.isEmpty()) {
                                    SkillImportResult(
                                        importedSkillNames = emptyList(),
                                        replacedSkillNames = emptyList(),
                                    )
                                } else {
                                    skillPackageImporter(
                                        candidates.map(SkillImportCandidate::entry),
                                        enableImported,
                                        importConfigValues,
                                    )
                                }
                            ToolExecutionResult.success(
                                summary =
                                    if (dryRun) {
                                        "Prepared dry-run skill import with ${candidates.size} importable skill(s)."
                                    } else {
                                        "Imported ${importResult.importedSkillNames.size} skill(s); skipped ${skipped.size}."
                                    },
                                payload =
                                    buildJsonObject {
                                        put("importFormat", SKILL_IMPORT_FORMAT)
                                        put("importVersion", SKILL_IMPORT_VERSION)
                                        put("acceptedExportFormat", SKILL_EXPORT_FORMAT)
                                        put("acceptedExportVersion", SKILL_EXPORT_VERSION)
                                        put("receivedSkillCount", rawEntries.size)
                                        put("scannedSkillCount", scannedEntries.size)
                                        put("omittedInputSkillCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                                        put("importableSkillCount", candidates.size)
                                        put("importedSkillCount", importResult.importedSkillNames.size)
                                        put("skippedSkillCount", skipped.size)
                                        put("invalidSkillCount", skipped.count { entry -> entry.code.startsWith("skills.import.invalid") })
                                        put("disabledSkillSkippedCount", skipped.count { entry -> entry.code == "skills.import.disabled_skipped" })
                                        put("replacedSkillCount", importResult.replacedSkillNames.size)
                                        put("limit", limit)
                                        put("includeDisabled", includeDisabled)
                                        put("enableImported", enableImported)
                                        put("importConfigValues", importConfigValues)
                                        put("dryRun", dryRun)
                                        put("secretValuesImported", false)
                                        put("secretValuesIncluded", false)
                                        put("secretStatusesImported", false)
                                        put("rawFrontmatterImported", false)
                                        put("baseDirImported", false)
                                        put("instructionsOmittedFromResult", true)
                                        put(
                                            "candidateSkills",
                                            buildJsonArray {
                                                candidates.forEach { candidate ->
                                                    add(candidate.toSkillImportCandidatePayload())
                                                }
                                            },
                                        )
                                        put(
                                            "importedSkillNames",
                                            buildJsonArray {
                                                importResult.importedSkillNames.forEach { name ->
                                                    add(JsonPrimitive(name))
                                                }
                                            },
                                        )
                                        put(
                                            "replacedSkillNames",
                                            buildJsonArray {
                                                importResult.replacedSkillNames.forEach { name ->
                                                    add(JsonPrimitive(name))
                                                }
                                            },
                                        )
                                        put(
                                            "importedSkills",
                                            buildJsonArray {
                                                if (!dryRun) {
                                                    candidates.forEach { candidate ->
                                                        add(
                                                            candidate.toSkillImportedPayload(
                                                                enableImported = enableImported,
                                                                importConfigValues = importConfigValues,
                                                                replacedSkillNames = importResult.replacedSkillNames,
                                                            ),
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                        put(
                                            "skippedSkills",
                                            buildJsonArray {
                                                skipped.forEach { skippedEntry ->
                                                    add(skippedEntry.toSkillImportSkippedPayload())
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
    }
