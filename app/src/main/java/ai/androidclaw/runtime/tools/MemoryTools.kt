package ai.androidclaw.runtime.tools

import ai.androidclaw.data.MemorySettingsSnapshot
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MemoryItem
import ai.androidclaw.data.model.Session
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun memoryToolEntries(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.status",
                    description = "Return local cross-session memory status.",
                ),
        ) { _, _ ->
            memoryStatusResult(settingsDataStore, memoryRepository)
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.stats",
                    aliases = listOf("memories.stats"),
                    description = "Return local cross-session memory aggregate statistics without exposing the install owner id.",
                ),
        ) { _, _ ->
            memoryStatsResult(settingsDataStore, memoryRepository)
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.doctor",
                    aliases =
                        listOf(
                            "memories.doctor",
                            "memory.check",
                            "memories.check",
                            "memory.health",
                            "memories.health",
                        ),
                    description = "Return actionable local memory diagnostics without exposing memory text or owner ids.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum diagnostic issues and recent memory checks to include. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MEMORY_DOCTOR_DEFAULT_LIMIT,
                            maxValue = MEMORY_DOCTOR_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryDoctorResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.handoff",
                    aliases =
                        listOf(
                            "memories.handoff",
                            "memory.snapshot",
                            "memories.snapshot",
                        ),
                    description = "Return a compact local memory handoff without exposing the install owner id.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Recent active memory count. Defaults to 8, max 20.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MEMORY_HANDOFF_DEFAULT_LIMIT,
                            maxValue = MEMORY_HANDOFF_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryHandoffResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.provenance",
                    aliases =
                        listOf(
                            "memories.provenance",
                            "memory.trace",
                            "memories.trace",
                            "memory.context",
                            "memories.context",
                        ),
                    description = "Resolve one memory's source session and source-message snippets without exposing owner ids or provider metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "id",
                                required = true,
                                description = "Memory identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMemoryText",
                                description = "Set false to omit the memory text. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeSourceSnippets",
                                description = "Set false to omit source message snippets. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit provenanceMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val id =
                arguments.memoryId()
                    ?: return@Entry missingMemoryIdResult("Provide a memory id to inspect provenance.")
            val memory =
                memoryRepository.get(
                    ownerUserId = settings.installUserId,
                    id = id,
                ) ?: return@Entry memoryNotFoundResult(id)
            memoryProvenanceResult(
                memory = memory,
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                includeMemoryText = arguments.optionalBoolean("includeMemoryText", defaultValue = true),
                includeSourceSnippets = arguments.optionalBoolean("includeSourceSnippets", defaultValue = true),
                includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.remember",
                    description = "Store an explicit local cross-session memory.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "text",
                                required = true,
                                description = "Memory text to store.",
                            ),
                        ),
                ),
        ) { context, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val text = arguments.optionalText("text")
            if (text.isNullOrBlank()) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Provide memory text to store.",
                    errorCode = "MISSING_MEMORY_TEXT",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_MEMORY_TEXT")
                        },
                )
            }
            val memory =
                memoryRepository.remember(
                    ownerUserId = settings.installUserId,
                    text = text,
                    sourceSessionId = context.sessionId,
                    sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                )
            if (memory == null) {
                ToolExecutionResult.failure(
                    summary = "Memory text was empty after normalization.",
                    errorCode = "EMPTY_MEMORY_TEXT",
                    payload =
                        buildJsonObject {
                            put("errorCode", "EMPTY_MEMORY_TEXT")
                        },
                )
            } else {
                ToolExecutionResult.success(
                    summary = "Stored memory: ${memory.text}",
                    payload = memoryPayload(memory),
                )
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.search",
                    description = "Search local cross-session memories.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Search query.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Optional result limit.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val query = arguments.optionalText("query")
            if (query.isNullOrBlank()) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Provide a memory search query.",
                    errorCode = "MISSING_MEMORY_QUERY",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_MEMORY_QUERY")
                        },
                )
            }
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MemoryRepository.DEFAULT_SEARCH_LIMIT,
                            maxValue = MemoryRepository.MAX_SEARCH_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            val memories =
                memoryRepository.search(
                    ownerUserId = settings.installUserId,
                    query = query,
                    limit = limit,
                )
            memoryListResult(
                memories = memories,
                emptySummary = "No matching memories found.",
                nonEmptySummary = "Found ${memories.size} matching memory item(s).",
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.list",
                    aliases = listOf("memories.list"),
                    description = "List recent local cross-session memories.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Optional result limit.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MemoryRepository.DEFAULT_LIST_LIMIT,
                            maxValue = MemoryRepository.MAX_LIST_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            val memories =
                memoryRepository.listRecent(
                    ownerUserId = settings.installUserId,
                    limit = limit,
                )
            memoryListResult(
                memories = memories,
                emptySummary = "No memories stored.",
                nonEmptySummary = "Found ${memories.size} memory item(s).",
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.session",
                    aliases =
                        listOf(
                            "memories.session",
                            "memory.by_session",
                            "memories.by_session",
                            "memory.session.list",
                            "memories.session.list",
                        ),
                    description = "List recent local cross-session memories captured from one source session.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "sourceSessionId",
                                description = "Optional source session id. Defaults to the current session.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Optional result limit.",
                            ),
                        ),
                ),
        ) { context, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val sourceSessionId = arguments.sourceSessionIdOrContext(context)
            if (sourceSessionId.isNullOrBlank()) {
                return@Entry missingMemorySourceSessionIdResult()
            }
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MemoryRepository.DEFAULT_LIST_LIMIT,
                            maxValue = MemoryRepository.MAX_LIST_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            val memories =
                memoryRepository.listForSourceSession(
                    ownerUserId = settings.installUserId,
                    sourceSessionId = sourceSessionId,
                    limit = limit,
                )
            memorySessionListResult(
                sourceSessionId = sourceSessionId,
                memories = memories,
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.source",
                    aliases =
                        listOf(
                            "memories.source",
                            "memory.by_source",
                            "memories.by_source",
                            "memory.source.list",
                            "memories.source.list",
                        ),
                    description = "List recent local cross-session memories captured by one source type.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "sourceType",
                                description = "Memory source type: manual or automatic.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Optional result limit.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val sourceType =
                when (val parsedSourceType = arguments.parseMemorySourceType()) {
                    is MemorySourceTypeParseResult.Failure -> return@Entry parsedSourceType.result
                    is MemorySourceTypeParseResult.Success -> parsedSourceType.value
                }
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MemoryRepository.DEFAULT_LIST_LIMIT,
                            maxValue = MemoryRepository.MAX_LIST_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            val memories =
                memoryRepository.listForSourceType(
                    ownerUserId = settings.installUserId,
                    sourceType = sourceType,
                    limit = limit,
                )
            memorySourceTypeListResult(
                sourceType = sourceType,
                memories = memories,
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.message",
                    aliases =
                        listOf(
                            "memories.message",
                            "memory.by_message",
                            "memories.by_message",
                            "memory.source_message",
                            "memories.source_message",
                        ),
                    description = "List recent local cross-session memories captured from one source message.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "sourceMessageId",
                                description = "Source message id. The alias messageId is also accepted.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Optional result limit.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val sourceMessageId = arguments.sourceMessageId()
            if (sourceMessageId.isNullOrBlank()) {
                return@Entry missingMemorySourceMessageIdResult()
            }
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MemoryRepository.DEFAULT_LIST_LIMIT,
                            maxValue = MemoryRepository.MAX_LIST_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            val memories =
                memoryRepository.listForSourceMessage(
                    ownerUserId = settings.installUserId,
                    sourceMessageId = sourceMessageId,
                    limit = limit,
                )
            memorySourceMessageListResult(
                sourceMessageId = sourceMessageId,
                memories = memories,
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.deleted",
                    aliases =
                        listOf(
                            "memories.deleted",
                            "memory.trash",
                            "memories.trash",
                            "memory.deleted.list",
                            "memories.deleted.list",
                        ),
                    description = "List recently deleted local cross-session memories that can be restored.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Optional result limit.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MemoryRepository.DEFAULT_LIST_LIMIT,
                            maxValue = MemoryRepository.MAX_LIST_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            val memories =
                memoryRepository.listDeletedRecent(
                    ownerUserId = settings.installUserId,
                    limit = limit,
                )
            memoryListResult(
                memories = memories,
                emptySummary = "No deleted memories found.",
                nonEmptySummary = "Found ${memories.size} deleted memory item(s).",
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.get",
                    aliases = listOf("memories.get"),
                    description = "Return one local cross-session memory by id.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "id",
                                required = true,
                                description = "Memory identifier.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val id = arguments.optionalText("id")
            if (id.isNullOrBlank()) {
                return@Entry missingMemoryIdResult("Provide a memory id to inspect.")
            }
            memoryGetResult(
                memory = memoryRepository.get(settings.installUserId, id),
                id = id,
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.update",
                    aliases = listOf("memories.update"),
                    description = "Replace one local cross-session memory's text by id.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "id",
                                required = true,
                                description = "Memory identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "text",
                                required = true,
                                description = "Replacement memory text.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val id = arguments.optionalText("id")
            if (id.isNullOrBlank()) {
                return@Entry missingMemoryIdResult("Provide a memory id to update.")
            }
            val text = arguments.optionalText("text")
            if (text.isNullOrBlank()) {
                return@Entry missingMemoryTextResult("Provide replacement memory text.")
            }
            memoryUpdateResult(
                memory =
                    memoryRepository.update(
                        ownerUserId = settings.installUserId,
                        id = id,
                        text = text,
                    ),
                id = id,
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.delete",
                    aliases = listOf("memories.delete"),
                    description = "Delete one local cross-session memory.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "id",
                                required = true,
                                description = "Memory identifier.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            val id = arguments.optionalText("id")
            if (id.isNullOrBlank()) {
                return@Entry missingMemoryIdResult("Provide a memory id to delete.")
            }
            val deleted = memoryRepository.delete(settings.installUserId, id)
            if (deleted) {
                ToolExecutionResult.success(
                    summary = "Deleted memory $id.",
                    payload =
                        buildJsonObject {
                            put("id", id)
                            put("deleted", true)
                        },
                )
            } else {
                ToolExecutionResult.failure(
                    summary = "Memory $id was not found.",
                    errorCode = "MEMORY_NOT_FOUND",
                    payload =
                        buildJsonObject {
                            put("id", id)
                            put("errorCode", "MEMORY_NOT_FOUND")
                        },
                )
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.restore",
                    aliases = listOf("memories.restore"),
                    description = "Restore one soft-deleted local cross-session memory by id.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "id",
                                required = true,
                                description = "Memory identifier.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabledResult()
            }
            val id = arguments.optionalText("id")
            if (id.isNullOrBlank()) {
                return@Entry missingMemoryIdResult("Provide a memory id to restore.")
            }
            memoryRestoreResult(
                restoredMemory = memoryRepository.restore(settings.installUserId, id),
                id = id,
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.clear",
                    aliases = listOf("memories.clear"),
                    description = "Clear all local cross-session memories after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "confirm",
                                required = true,
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to clear all memories.",
                    errorCode = "MISSING_CLEAR_CONFIRMATION",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_CLEAR_CONFIRMATION")
                        },
                )
            }
            val deletedCount = memoryRepository.clear(settings.installUserId)
            ToolExecutionResult.success(
                summary = "Cleared $deletedCount memory item(s).",
                payload =
                    buildJsonObject {
                        put("deletedCount", deletedCount)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.command",
                    description = "Dispatch /memory commands to local memory tools.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "command",
                                description = "Raw /memory command arguments.",
                            ),
                        ),
                ),
        ) { context, arguments ->
            executeMemoryCommand(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                context = context,
                command = arguments.optionalText("command").orEmpty(),
            )
        },
    )

private suspend fun executeMemoryCommand(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    context: ToolExecutionContext,
    command: String,
): ToolExecutionResult {
    val trimmed = command.trim()
    if (trimmed.isBlank() || trimmed.equals("status", ignoreCase = true)) {
        return memoryStatusResult(settingsDataStore, memoryRepository)
    }
    if (trimmed.equals("stats", ignoreCase = true)) {
        return memoryStatsResult(settingsDataStore, memoryRepository)
    }
    if (
        trimmed.equals("doctor", ignoreCase = true) ||
        trimmed.equals("check", ignoreCase = true) ||
        trimmed.equals("health", ignoreCase = true)
    ) {
        return memoryDoctorResult(
            settingsDataStore = settingsDataStore,
            memoryRepository = memoryRepository,
            limit = MEMORY_DOCTOR_DEFAULT_LIMIT,
            includeMarkdown = true,
        )
    }
    if (trimmed.equals("handoff", ignoreCase = true) || trimmed.equals("snapshot", ignoreCase = true)) {
        return memoryHandoffResult(
            settingsDataStore = settingsDataStore,
            memoryRepository = memoryRepository,
            limit = MEMORY_HANDOFF_DEFAULT_LIMIT,
            includeMarkdown = true,
        )
    }
    val verb = trimmed.substringBefore(' ').lowercase()
    val rest = trimmed.substringAfter(' ', "").trim()
    if (verb == "doctor" || verb == "check" || verb == "health") {
        val limit =
            if (rest.isBlank()) {
                MEMORY_DOCTOR_DEFAULT_LIMIT
            } else {
                val parsedLimit =
                    rest.toLongOrNull()
                        ?: return invalidMemoryLimitResult(
                            field = "limit",
                            maxValue = MEMORY_DOCTOR_MAX_LIMIT,
                            rawValue = rest,
                        )
                if (parsedLimit !in 1L..MEMORY_DOCTOR_MAX_LIMIT.toLong()) {
                    return invalidMemoryLimitResult(
                        field = "limit",
                        maxValue = MEMORY_DOCTOR_MAX_LIMIT,
                        rawValue = rest,
                    )
                }
                parsedLimit.toInt()
            }
        return memoryDoctorResult(
            settingsDataStore = settingsDataStore,
            memoryRepository = memoryRepository,
            limit = limit,
            includeMarkdown = true,
        )
    }
    val settings = settingsDataStore.memorySettingsSnapshot()
    if (!settings.enabled && verb != "delete" && verb != "clear") {
        return memoryDisabledResult()
    }
    return when (verb) {
        "remember" -> {
            val memory =
                memoryRepository.remember(
                    ownerUserId = settings.installUserId,
                    text = rest,
                    sourceSessionId = context.sessionId,
                    sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                )
            if (memory == null) {
                ToolExecutionResult.failure(
                    summary = "Provide memory text after /memory remember.",
                    errorCode = "MISSING_MEMORY_TEXT",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_MEMORY_TEXT")
                        },
                )
            } else {
                ToolExecutionResult.success(
                    summary = "Stored memory: ${memory.text}",
                    payload = memoryPayload(memory),
                )
            }
        }

        "search" -> {
            if (rest.isBlank()) {
                return ToolExecutionResult.failure(
                    summary = "Provide a memory search query after /memory search.",
                    errorCode = "MISSING_MEMORY_QUERY",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_MEMORY_QUERY")
                        },
                )
            }
            memoryListResult(
                memories =
                    memoryRepository.search(
                        ownerUserId = settings.installUserId,
                        query = rest,
                        limit = MemoryRepository.DEFAULT_SEARCH_LIMIT,
                    ),
                emptySummary = "No matching memories found.",
                nonEmptySummary = "Found matching memories.",
            )
        }

        "list" ->
            memoryListResult(
                memories =
                    memoryRepository.listRecent(
                        ownerUserId = settings.installUserId,
                        limit = MemoryRepository.DEFAULT_LIST_LIMIT,
                    ),
                emptySummary = "No memories stored.",
                nonEmptySummary = "Found stored memories.",
            )

        "handoff", "snapshot" -> {
            val limit =
                if (rest.isBlank()) {
                    MEMORY_HANDOFF_DEFAULT_LIMIT
                } else {
                    val parsedLimit =
                        rest.toLongOrNull()
                            ?: return invalidMemoryLimitResult(
                                field = "limit",
                                maxValue = MEMORY_HANDOFF_MAX_LIMIT,
                                rawValue = rest,
                            )
                    if (parsedLimit !in 1L..MEMORY_HANDOFF_MAX_LIMIT.toLong()) {
                        return invalidMemoryLimitResult(
                            field = "limit",
                            maxValue = MEMORY_HANDOFF_MAX_LIMIT,
                            rawValue = rest,
                        )
                    }
                    parsedLimit.toInt()
                }
            memoryHandoffResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeMarkdown = true,
            )
        }

        "session", "by-session" -> {
            val sourceSessionId = rest.ifBlank { context.sessionId.orEmpty() }.trim()
            if (sourceSessionId.isBlank()) {
                return missingMemorySourceSessionIdResult()
            }
            memorySessionListResult(
                sourceSessionId = sourceSessionId,
                memories =
                    memoryRepository.listForSourceSession(
                        ownerUserId = settings.installUserId,
                        sourceSessionId = sourceSessionId,
                        limit = MemoryRepository.DEFAULT_LIST_LIMIT,
                    ),
            )
        }

        "source", "by-source" -> {
            val sourceType =
                normalizeMemorySourceType(rest)
                    ?: return if (rest.isBlank()) {
                        missingMemorySourceTypeResult()
                    } else {
                        invalidMemorySourceTypeResult(rest)
                    }
            memorySourceTypeListResult(
                sourceType = sourceType,
                memories =
                    memoryRepository.listForSourceType(
                        ownerUserId = settings.installUserId,
                        sourceType = sourceType,
                        limit = MemoryRepository.DEFAULT_LIST_LIMIT,
                    ),
            )
        }

        "message", "by-message", "source-message" -> {
            val sourceMessageId =
                rest.toSourceMessageIdOrNull()
                    ?: return missingMemorySourceMessageIdResult()
            memorySourceMessageListResult(
                sourceMessageId = sourceMessageId,
                memories =
                    memoryRepository.listForSourceMessage(
                        ownerUserId = settings.installUserId,
                        sourceMessageId = sourceMessageId,
                        limit = MemoryRepository.DEFAULT_LIST_LIMIT,
                    ),
            )
        }

        "deleted", "trash" ->
            memoryListResult(
                memories =
                    memoryRepository.listDeletedRecent(
                        ownerUserId = settings.installUserId,
                        limit = MemoryRepository.DEFAULT_LIST_LIMIT,
                    ),
                emptySummary = "No deleted memories found.",
                nonEmptySummary = "Found deleted memories.",
            )

        "provenance", "trace", "context" -> {
            if (rest.isBlank()) {
                return missingMemoryIdResult("Provide a memory id after /memory $verb.")
            }
            val memory =
                memoryRepository.get(
                    ownerUserId = settings.installUserId,
                    id = rest,
                ) ?: return memoryNotFoundResult(rest)
            memoryProvenanceResult(
                memory = memory,
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                includeMemoryText = true,
                includeSourceSnippets = true,
                includeMarkdown = true,
            )
        }

        "get" -> {
            if (rest.isBlank()) {
                return missingMemoryIdResult("Provide a memory id after /memory get.")
            }
            memoryGetResult(
                memory = memoryRepository.get(settings.installUserId, rest),
                id = rest,
            )
        }

        "update" -> {
            val id = rest.substringBefore(' ').trim()
            val text = rest.substringAfter(' ', "").trim()
            if (id.isBlank()) {
                return missingMemoryIdResult("Provide a memory id after /memory update.")
            }
            if (text.isBlank()) {
                return missingMemoryTextResult("Provide replacement memory text after /memory update <id>.")
            }
            memoryUpdateResult(
                memory =
                    memoryRepository.update(
                        ownerUserId = settings.installUserId,
                        id = id,
                        text = text,
                    ),
                id = id,
            )
        }

        "delete" -> {
            if (rest.isBlank()) {
                return missingMemoryIdResult("Provide a memory id after /memory delete.")
            }
            val deleted = memoryRepository.delete(settings.installUserId, rest)
            if (deleted) {
                ToolExecutionResult.success(
                    summary = "Deleted memory $rest.",
                    payload =
                        buildJsonObject {
                            put("id", rest)
                            put("deleted", true)
                        },
                )
            } else {
                ToolExecutionResult.failure(
                    summary = "Memory $rest was not found.",
                    errorCode = "MEMORY_NOT_FOUND",
                    payload =
                        buildJsonObject {
                            put("id", rest)
                            put("errorCode", "MEMORY_NOT_FOUND")
                        },
                )
            }
        }

        "restore" -> {
            if (rest.isBlank()) {
                return missingMemoryIdResult("Provide a memory id after /memory restore.")
            }
            memoryRestoreResult(
                restoredMemory = memoryRepository.restore(settings.installUserId, rest),
                id = rest,
            )
        }

        "clear" -> {
            if (rest != "CONFIRM") {
                ToolExecutionResult.failure(
                    summary = "Use /memory clear CONFIRM to clear all memories.",
                    errorCode = "MISSING_CLEAR_CONFIRMATION",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_CLEAR_CONFIRMATION")
                        },
                )
            } else {
                val deletedCount = memoryRepository.clear(settings.installUserId)
                ToolExecutionResult.success(
                    summary = "Cleared $deletedCount memory item(s).",
                    payload =
                        buildJsonObject {
                            put("deletedCount", deletedCount)
                        },
                )
            }
        }

        else ->
            ToolExecutionResult.failure(
                summary = "Unknown memory command: $verb.",
                errorCode = "UNKNOWN_MEMORY_COMMAND",
                payload =
                    buildJsonObject {
                        put("errorCode", "UNKNOWN_MEMORY_COMMAND")
                        put("command", verb)
                    },
            )
    }
}

private suspend fun memoryStatusResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    val count = memoryRepository.countActive(settings.installUserId)
    return ToolExecutionResult.success(
        summary =
            if (settings.enabled) {
                "Memory is enabled with $count stored memory item(s)."
            } else {
                "Memory is disabled."
            },
        payload =
            buildJsonObject {
                put("enabled", settings.enabled)
                put("scope", "local-device")
                put("memoryCount", count)
            },
    )
}

private suspend fun memoryStatsResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    val stats = memoryRepository.stats(settings.installUserId)
    return ToolExecutionResult.success(
        summary =
            if (settings.enabled) {
                "Memory is enabled with ${stats.activeMemoryCount} active memory item(s)."
            } else {
                "Memory is disabled with ${stats.activeMemoryCount} active memory item(s)."
            },
        payload =
            buildJsonObject {
                put("enabled", settings.enabled)
                put("scope", "local-device")
                put("memoryCount", stats.activeMemoryCount)
                put("activeMemoryCount", stats.activeMemoryCount)
                put("deletedMemoryCount", stats.deletedMemoryCount)
                put("totalMemoryCount", stats.totalMemoryCount)
                put("activeWithSourceSessionCount", stats.activeWithSourceSessionCount)
                put(
                    "oldestActiveCreatedAt",
                    stats.oldestActiveCreatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                )
                put(
                    "newestActiveUpdatedAt",
                    stats.newestActiveUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                )
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
            },
    )
}

private suspend fun memoryDoctorResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    limit: Int,
    includeMarkdown: Boolean,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    val stats = memoryRepository.stats(settings.installUserId)
    val memoryChecks =
        memoryRepository.listRecent(
            ownerUserId = settings.installUserId,
            limit = limit,
        )
    val issues =
        buildMemoryDoctorIssues(
            settings = settings,
            stats = stats,
            memoryChecks = memoryChecks,
        )
    val includedIssues = issues.take(limit)
    val status = issues.toMemoryDoctorStatus()
    val doctorMarkdown =
        if (includeMarkdown) {
            includedIssues.toMemoryDoctorMarkdown(
                status = status,
                settings = settings,
                stats = stats,
                checkedMemoryCount = memoryChecks.size,
                issueCount = issues.size,
                limit = limit,
            )
        } else {
            null
        }
    return ToolExecutionResult.success(
        summary =
            when {
                issues.isEmpty() ->
                    "Memory doctor found no issues across ${memoryChecks.size} checked active memory item(s)."
                includedIssues.size == issues.size ->
                    "Memory doctor found ${issues.size} issue(s) across ${memoryChecks.size} checked active memory item(s)."
                else ->
                    "Memory doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
            },
        payload =
            buildJsonObject {
                put("status", status)
                put("enabled", settings.enabled)
                put("scope", "local-device")
                put("ownerUserIdIncluded", false)
                put("memoryTextIncluded", false)
                put("memoryLimit", limit)
                put("memoryCheckCount", memoryChecks.size)
                put("memoryChecksOmitted", (stats.activeMemoryCount - memoryChecks.size.toLong()).coerceAtLeast(0))
                put("issueCount", issues.size)
                put("includedIssueCount", includedIssues.size)
                put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                put("errorCount", issues.count { issue -> issue.severity == "Error" })
                put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                put("includeMarkdown", includeMarkdown)
                put("stats", stats.toMemoryDoctorStatsPayload(settings.enabled))
                put(
                    "memoryChecks",
                    buildJsonArray {
                        memoryChecks.forEach { memory ->
                            add(memory.toMemoryDoctorCheckPayload())
                        }
                    },
                )
                put(
                    "issues",
                    buildJsonArray {
                        includedIssues.forEach { issue ->
                            add(issue.toMemoryDoctorPayload())
                        }
                    },
                )
                put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
            },
    )
}

private suspend fun memoryHandoffResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    limit: Int,
    includeMarkdown: Boolean,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    if (!settings.enabled) {
        return memoryDisabledResult()
    }
    val stats = memoryRepository.stats(settings.installUserId)
    val memories =
        memoryRepository.listRecent(
            ownerUserId = settings.installUserId,
            limit = limit,
        )
    val handoffMarkdown =
        if (includeMarkdown) {
            memoryHandoffMarkdown(
                stats = stats,
                memories = memories,
                limit = limit,
            )
        } else {
            null
        }
    return ToolExecutionResult.success(
        summary =
            if (memories.isEmpty()) {
                "Prepared memory handoff with no active memories."
            } else {
                "Prepared memory handoff with ${memories.size} active memory item(s)."
            },
        payload =
            buildJsonObject {
                put("enabled", true)
                put("scope", "local-device")
                put("memoryLimit", limit)
                put("memoryCount", memories.size)
                put("activeMemoryCount", stats.activeMemoryCount)
                put("deletedMemoryCount", stats.deletedMemoryCount)
                put("totalMemoryCount", stats.totalMemoryCount)
                put("activeWithSourceSessionCount", stats.activeWithSourceSessionCount)
                put(
                    "oldestActiveCreatedAt",
                    stats.oldestActiveCreatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                )
                put(
                    "newestActiveUpdatedAt",
                    stats.newestActiveUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                )
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
                put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memoryPayload(memory))
                        }
                    },
                )
            },
    )
}

private suspend fun memoryProvenanceResult(
    memory: MemoryItem,
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    includeMemoryText: Boolean,
    includeSourceSnippets: Boolean,
    includeMarkdown: Boolean,
): ToolExecutionResult {
    val sourceSession = memory.sourceSessionId?.let { sessionId -> sessionRepository.getSession(sessionId) }
    val sourceMessagesById = messageRepository.getMessagesByIds(memory.sourceMessageIds)
    val sourceMessages =
        memory.sourceMessageIds.map { sourceMessageId ->
            MemorySourceMessageReference(
                sourceMessageId = sourceMessageId,
                message = sourceMessagesById[sourceMessageId],
            )
        }
    val missingSourceMessageIds =
        sourceMessages
            .filter { reference -> reference.message == null }
            .map(MemorySourceMessageReference::sourceMessageId)
    val crossSessionSourceMessageCount =
        memory.sourceSessionId?.let { sourceSessionId ->
            sourceMessages.count { reference ->
                val message = reference.message
                message != null && message.sessionId != sourceSessionId
            }
        } ?: 0
    val provenanceMarkdown =
        if (includeMarkdown) {
            memory.toMemoryProvenanceMarkdown(
                sourceSession = sourceSession,
                sourceMessages = sourceMessages,
                missingSourceMessageIds = missingSourceMessageIds,
                crossSessionSourceMessageCount = crossSessionSourceMessageCount,
                includeMemoryText = includeMemoryText,
                includeSourceSnippets = includeSourceSnippets,
            )
        } else {
            null
        }
    return ToolExecutionResult.success(
        summary =
            if (memory.sourceSessionId == null && memory.sourceMessageIds.isEmpty()) {
                "Prepared provenance for memory ${memory.id} with no source references."
            } else {
                "Prepared provenance for memory ${memory.id} with ${sourceMessagesById.size} resolved source message(s)."
            },
        payload =
            buildJsonObject {
                put("id", memory.id)
                put("sourceType", memory.sourceType)
                put("createdAt", memory.createdAt.toString())
                put("updatedAt", memory.updatedAt.toString())
                put("deletedAt", memory.deletedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("textLength", memory.text.length)
                put("text", if (includeMemoryText) JsonPrimitive(memory.text) else JsonNull)
                put("memoryTextIncluded", includeMemoryText)
                put("ownerUserIdIncluded", false)
                put("sourceSessionId", memory.sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
                put("sourceSessionMissing", memory.sourceSessionId != null && sourceSession == null)
                put("sourceSession", sourceSession?.toMemorySourceSessionPayload() ?: JsonNull)
                put("sourceMessageCount", memory.sourceMessageIds.size)
                put("resolvedSourceMessageCount", sourceMessagesById.size)
                put("missingSourceMessageCount", missingSourceMessageIds.size)
                put("crossSessionSourceMessageCount", crossSessionSourceMessageCount)
                put("sourceMessageSnippetsIncluded", includeSourceSnippets)
                put("fullMessageBodiesIncluded", false)
                put("providerMetaIncluded", false)
                put("includeMarkdown", includeMarkdown)
                put(
                    "sourceMessageIds",
                    buildJsonArray {
                        memory.sourceMessageIds.forEach { sourceMessageId ->
                            add(JsonPrimitive(sourceMessageId))
                        }
                    },
                )
                put(
                    "missingSourceMessageIds",
                    buildJsonArray {
                        missingSourceMessageIds.forEach { sourceMessageId ->
                            add(JsonPrimitive(sourceMessageId))
                        }
                    },
                )
                put(
                    "sourceMessages",
                    buildJsonArray {
                        sourceMessages.forEach { reference ->
                            add(reference.toMemorySourceMessagePayload(includeSourceSnippets))
                        }
                    },
                )
                put("provenanceMarkdown", provenanceMarkdown?.let(::JsonPrimitive) ?: JsonNull)
            },
    )
}

private fun memoryDisabledResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory is disabled. Enable it in Settings before using memory tools.",
        errorCode = "MEMORY_DISABLED",
        payload =
            buildJsonObject {
                put("errorCode", "MEMORY_DISABLED")
                put("enabled", false)
            },
    )

private fun memoryNotFoundResult(id: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory $id was not found.",
        errorCode = "MEMORY_NOT_FOUND",
        payload =
            buildJsonObject {
                put("id", id)
                put("errorCode", "MEMORY_NOT_FOUND")
            },
    )

private fun memoryListResult(
    memories: List<MemoryItem>,
    emptySummary: String,
    nonEmptySummary: String,
): ToolExecutionResult =
    ToolExecutionResult.success(
        summary = if (memories.isEmpty()) emptySummary else nonEmptySummary,
        payload =
            buildJsonObject {
                put("memoryCount", memories.size)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memoryPayload(memory))
                        }
                    },
                )
            },
    )

private fun memorySessionListResult(
    sourceSessionId: String,
    memories: List<MemoryItem>,
): ToolExecutionResult =
    ToolExecutionResult.success(
        summary =
            if (memories.isEmpty()) {
                "No memories found for session $sourceSessionId."
            } else {
                "Found ${memories.size} memory item(s) for session $sourceSessionId."
            },
        payload =
            buildJsonObject {
                put("sourceSessionId", sourceSessionId)
                put("memoryCount", memories.size)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memoryPayload(memory))
                        }
                    },
                )
            },
    )

private fun memorySourceTypeListResult(
    sourceType: String,
    memories: List<MemoryItem>,
): ToolExecutionResult =
    ToolExecutionResult.success(
        summary =
            if (memories.isEmpty()) {
                "No $sourceType memories found."
            } else {
                "Found ${memories.size} $sourceType memory item(s)."
            },
        payload =
            buildJsonObject {
                put("sourceType", sourceType)
                put("memoryCount", memories.size)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memoryPayload(memory))
                        }
                    },
                )
            },
    )

private fun memorySourceMessageListResult(
    sourceMessageId: String,
    memories: List<MemoryItem>,
): ToolExecutionResult =
    ToolExecutionResult.success(
        summary =
            if (memories.isEmpty()) {
                "No memories found for source message $sourceMessageId."
            } else {
                "Found ${memories.size} memory item(s) for source message $sourceMessageId."
            },
        payload =
            buildJsonObject {
                put("sourceMessageId", sourceMessageId)
                put("memoryCount", memories.size)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memoryPayload(memory))
                        }
                    },
                )
            },
    )

private fun memoryGetResult(
    memory: MemoryItem?,
    id: String,
): ToolExecutionResult =
    if (memory == null) {
        ToolExecutionResult.failure(
            summary = "Memory $id was not found.",
            errorCode = "MEMORY_NOT_FOUND",
            payload =
                buildJsonObject {
                    put("id", id)
                    put("errorCode", "MEMORY_NOT_FOUND")
                },
        )
    } else {
        ToolExecutionResult.success(
            summary = "Found memory: ${memory.text}",
            payload = memoryPayload(memory),
        )
    }

private fun memoryUpdateResult(
    memory: MemoryItem?,
    id: String,
): ToolExecutionResult =
    if (memory == null) {
        ToolExecutionResult.failure(
            summary = "Memory $id was not found.",
            errorCode = "MEMORY_NOT_FOUND",
            payload =
                buildJsonObject {
                    put("id", id)
                    put("errorCode", "MEMORY_NOT_FOUND")
                },
        )
    } else {
        ToolExecutionResult.success(
            summary = "Updated memory: ${memory.text}",
            payload = memoryPayload(memory),
        )
    }

private fun memoryRestoreResult(
    restoredMemory: MemoryRepository.RestoredMemory?,
    id: String,
): ToolExecutionResult =
    if (restoredMemory == null) {
        ToolExecutionResult.failure(
            summary = "Memory $id was not found.",
            errorCode = "MEMORY_NOT_FOUND",
            payload =
                buildJsonObject {
                    put("id", id)
                    put("errorCode", "MEMORY_NOT_FOUND")
                },
        )
    } else {
        val memory = restoredMemory.memory
        ToolExecutionResult.success(
            summary =
                if (restoredMemory.restored) {
                    "Restored memory: ${memory.text}"
                } else {
                    "Memory was already active: ${memory.text}"
                },
            payload = memoryPayload(memory, restored = restoredMemory.restored),
        )
    }

private data class MemoryDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val memoryId: String?,
    val sourceType: String?,
    val summary: String,
    val action: String,
    val detail: String? = null,
)

private data class MemorySourceMessageReference(
    val sourceMessageId: String,
    val message: ChatMessage?,
)

private fun buildMemoryDoctorIssues(
    settings: MemorySettingsSnapshot,
    stats: MemoryRepository.MemoryStats,
    memoryChecks: List<MemoryItem>,
): List<MemoryDoctorIssue> =
    buildList {
        if (!settings.enabled) {
            add(
                MemoryDoctorIssue(
                    id = "settings:memory.disabled",
                    severity = "Warning",
                    code = "memory.disabled",
                    memoryId = null,
                    sourceType = null,
                    summary =
                        (
                            if (stats.activeMemoryCount > 0) {
                                "Memory is disabled while ${stats.activeMemoryCount} active memory item(s) are stored."
                            } else {
                                "Memory is disabled."
                            }
                        ).toMemoryDoctorText(),
                    action = "Enable memory in Settings if cross-session recall should be available.".toMemoryDoctorText(),
                ),
            )
        }
        if (settings.enabled && settings.installUserId.isBlank()) {
            add(
                MemoryDoctorIssue(
                    id = "settings:memory.owner_missing",
                    severity = "Error",
                    code = "memory.owner_missing",
                    memoryId = null,
                    sourceType = null,
                    summary = "Memory is enabled but the local owner identifier is missing.".toMemoryDoctorText(),
                    action = "Toggle memory off and on to initialize local ownership before storing memories.".toMemoryDoctorText(),
                ),
            )
        }
        stats.sourceTypeStats
            .filter { sourceStats -> sourceStats.sourceType !in MEMORY_KNOWN_SOURCE_TYPES }
            .forEach { sourceStats ->
                add(
                    MemoryDoctorIssue(
                        id = "sourceType:${sourceStats.sourceType}:memory.source_type.unknown",
                        severity = "Warning",
                        code = "memory.source_type.unknown",
                        memoryId = null,
                        sourceType = sourceStats.sourceType,
                        summary =
                            "Memory source type ${sourceStats.sourceType} has ${sourceStats.memoryCount} active item(s)."
                                .toMemoryDoctorText(),
                        action = "Normalize memory sourceType to manual or automatic during import/update flows.".toMemoryDoctorText(),
                    ),
                )
            }
        memoryChecks.forEach { memory ->
            if (memory.text.isBlank()) {
                add(
                    MemoryDoctorIssue(
                        id = "memory:${memory.id}:memory.text.blank",
                        severity = "Error",
                        code = "memory.text.blank",
                        memoryId = memory.id,
                        sourceType = memory.sourceType,
                        summary = "Memory ${memory.id} has blank text.".toMemoryDoctorText(),
                        action = "Delete the blank memory or replace it with meaningful text.".toMemoryDoctorText(),
                    ),
                )
            }
            if (memory.sourceType == MemoryRepository.SOURCE_TYPE_AUTOMATIC &&
                memory.sourceSessionId.isNullOrBlank() &&
                memory.sourceMessageIds.isEmpty()
            ) {
                add(
                    MemoryDoctorIssue(
                        id = "memory:${memory.id}:memory.automatic.source_missing",
                        severity = "Warning",
                        code = "memory.automatic.source_missing",
                        memoryId = memory.id,
                        sourceType = memory.sourceType,
                        summary = "Automatic memory ${memory.id} has no source session or source message reference.".toMemoryDoctorText(),
                        action =
                            "Capture sourceSessionId or sourceMessageIds for automatic memories so provenance remains inspectable."
                                .toMemoryDoctorText(),
                    ),
                )
            }
            if (memory.updatedAt.isBefore(memory.createdAt)) {
                add(
                    MemoryDoctorIssue(
                        id = "memory:${memory.id}:memory.timestamps.inverted",
                        severity = "Error",
                        code = "memory.timestamps.inverted",
                        memoryId = memory.id,
                        sourceType = memory.sourceType,
                        summary = "Memory ${memory.id} has updatedAt before createdAt.".toMemoryDoctorText(),
                        action = "Repair the memory timestamps or recreate the affected memory.".toMemoryDoctorText(),
                    ),
                )
            }
            if (memory.text.length >= MemoryRepository.MAX_MEMORY_TEXT_CHARS) {
                add(
                    MemoryDoctorIssue(
                        id = "memory:${memory.id}:memory.text.max_length",
                        severity = "Warning",
                        code = "memory.text.max_length",
                        memoryId = memory.id,
                        sourceType = memory.sourceType,
                        summary =
                            "Memory ${memory.id} is at the ${MemoryRepository.MAX_MEMORY_TEXT_CHARS} character storage limit."
                                .toMemoryDoctorText(),
                        action = "Review whether the memory was truncated and split it into shorter facts if needed.".toMemoryDoctorText(),
                    ),
                )
            }
        }
    }

private fun MemoryRepository.MemoryStats.toMemoryDoctorStatsPayload(enabled: Boolean): JsonObject =
    buildJsonObject {
        put("enabled", enabled)
        put("scope", "local-device")
        put("memoryCount", activeMemoryCount)
        put("activeMemoryCount", activeMemoryCount)
        put("deletedMemoryCount", deletedMemoryCount)
        put("totalMemoryCount", totalMemoryCount)
        put("activeWithSourceSessionCount", activeWithSourceSessionCount)
        put("oldestActiveCreatedAt", oldestActiveCreatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("newestActiveUpdatedAt", newestActiveUpdatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("ownerUserIdIncluded", false)
        put("memoryTextIncluded", false)
        put(
            "sourceTypeStats",
            buildJsonArray {
                sourceTypeStats.forEach { sourceTypeStats ->
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

private fun MemoryItem.toMemoryDoctorCheckPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("sourceType", sourceType)
        put("sourceSessionId", sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceMessageCount", sourceMessageIds.size)
        put("createdAt", createdAt.toString())
        put("updatedAt", updatedAt.toString())
        put("deletedAt", deletedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("textLength", text.length)
        put("textIncluded", false)
        put("ownerUserIdIncluded", false)
    }

private fun List<MemoryDoctorIssue>.toMemoryDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

private fun MemoryDoctorIssue.toMemoryDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("memoryId", memoryId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceType", sourceType?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", summary)
        put("action", action)
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun List<MemoryDoctorIssue>.toMemoryDoctorMarkdown(
    status: String,
    settings: MemorySettingsSnapshot,
    stats: MemoryRepository.MemoryStats,
    checkedMemoryCount: Int,
    issueCount: Int,
    limit: Int,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Memory doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Scope: local-device")
        appendLine("- Enabled: ${settings.enabled}")
        appendLine("- Active memories: ${stats.activeMemoryCount}")
        appendLine("- Deleted memories: ${stats.deletedMemoryCount}")
        appendLine("- Total memories: ${stats.totalMemoryCount}")
        appendLine("- Checked active memories: $checkedMemoryCount of up to $limit")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Owner user id included: false")
        appendLine("- Memory text included: false")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No memory issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toMemoryDoctorMarkdownLine())
            }
        }
    }
}

private fun MemoryDoctorIssue.toMemoryDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(memoryId?.toMemoryHandoffLine() ?: "memory")
        append("` code=")
        append(code)
        sourceType?.let { sourceType ->
            append(" sourceType=")
            append(sourceType.toMemoryHandoffLine())
        }
        append(": ")
        append(summary.toMemoryHandoffLine())
        detail?.let { detail ->
            append(" detail=")
            append(detail.toMemoryHandoffLine())
        }
        append(" Action: ")
        append(action.toMemoryHandoffLine())
    }

private fun String.toMemoryDoctorText(): String = toMemoryHandoffLine().take(MEMORY_DOCTOR_TEXT_MAX_CHARS)

private fun Session.toMemorySourceSessionPayload(): JsonObject =
    buildJsonObject {
        put("sessionId", id)
        put("title", title)
        put("isMain", isMain)
        put("archived", archived)
        put("createdAtIso", createdAt.toString())
        put("updatedAtIso", updatedAt.toString())
        put("hasSummary", summaryText != null)
        put("summaryLength", summaryText?.length ?: 0)
        put("summaryTextIncluded", false)
    }

private fun MemorySourceMessageReference.toMemorySourceMessagePayload(includeSourceSnippet: Boolean): JsonObject {
    val resolvedMessage = message
    return if (resolvedMessage == null) {
        buildJsonObject {
            put("sourceMessageId", sourceMessageId)
            put("messageId", sourceMessageId)
            put("resolved", false)
            put("missing", true)
            put("contentSnippet", JsonNull)
            put("messageBodyIncluded", false)
            put("providerMetaIncluded", false)
        }
    } else {
        resolvedMessage.toMemorySourceMessagePayload(
            sourceMessageId = sourceMessageId,
            includeSourceSnippet = includeSourceSnippet,
        )
    }
}

private fun ChatMessage.toMemorySourceMessagePayload(
    sourceMessageId: String,
    includeSourceSnippet: Boolean,
): JsonObject {
    val contentSnippet = content.toMemorySourceSnippet()
    return buildJsonObject {
        put("sourceMessageId", sourceMessageId)
        put("messageId", id)
        put("resolved", true)
        put("missing", false)
        put("sessionId", sessionId)
        put("role", role.name)
        put("createdAtIso", createdAt.toString())
        put("contentSnippet", if (includeSourceSnippet) JsonPrimitive(contentSnippet) else JsonNull)
        put("contentLength", content.length)
        put("contentTruncated", contentSnippet.length < content.length)
        put("messageBodyIncluded", false)
        put("providerMetaIncluded", false)
        put("hasProviderMeta", providerMeta != null)
        put("toolCallId", toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("taskRunId", taskRunId?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun MemoryItem.toMemoryProvenanceMarkdown(
    sourceSession: Session?,
    sourceMessages: List<MemorySourceMessageReference>,
    missingSourceMessageIds: List<String>,
    crossSessionSourceMessageCount: Int,
    includeMemoryText: Boolean,
    includeSourceSnippets: Boolean,
): String =
    buildString {
        appendLine("# Memory provenance")
        appendLine()
        appendLine("- Memory id: `$id`")
        appendLine("- Source type: ${sourceType.toMemoryHandoffLine()}")
        appendLine("- Source session id: ${sourceSessionId?.toMemoryHandoffLine() ?: "none"}")
        appendLine("- Source session missing: ${sourceSessionId != null && sourceSession == null}")
        appendLine("- Source messages resolved: ${sourceMessages.count { reference -> reference.message != null }} of ${sourceMessages.size}")
        appendLine("- Missing source messages: ${missingSourceMessageIds.size}")
        appendLine("- Cross-session source messages: $crossSessionSourceMessageCount")
        appendLine("- Owner user id included: false")
        appendLine("- Memory text included: $includeMemoryText")
        appendLine("- Source snippets included: $includeSourceSnippets")
        appendLine("- Full message bodies included: false")
        appendLine("- Provider metadata included: false")
        appendLine()
        appendLine("## Memory")
        appendLine(if (includeMemoryText) text.toMemoryHandoffLine() else "_Memory text omitted._")
        appendLine()
        appendLine("## Source session")
        if (sourceSession == null) {
            appendLine(if (sourceSessionId == null) "_No source session recorded._" else "_Source session not found._")
        } else {
            append("- `")
            append(sourceSession.id.toMemoryHandoffLine())
            append("` ")
            append(sourceSession.title.toMemoryHandoffLine())
            append(" archived=")
            appendLine(sourceSession.archived)
        }
        appendLine()
        appendLine("## Source messages")
        if (sourceMessages.isEmpty()) {
            appendLine("_No source messages recorded._")
        } else {
            sourceMessages.forEach { reference ->
                val sourceMessage = reference.message
                append("- `")
                append(reference.sourceMessageId.toMemoryHandoffLine())
                append("` ")
                if (sourceMessage == null) {
                    appendLine("missing")
                } else {
                    append(sourceMessage.role.name)
                    append(" session=")
                    append(sourceMessage.sessionId.toMemoryHandoffLine())
                    append(": ")
                    if (includeSourceSnippets) {
                        appendLine(sourceMessage.content.toMemorySourceSnippet().toMemoryHandoffLine())
                    } else {
                        appendLine("_Snippet omitted._")
                    }
                }
            }
        }
    }

private fun String.toMemorySourceSnippet(): String =
    if (length <= MEMORY_SOURCE_SNIPPET_MAX_CHARS) {
        this
    } else {
        take(MEMORY_SOURCE_SNIPPET_MAX_CHARS)
    }

private fun memoryHandoffMarkdown(
    stats: MemoryRepository.MemoryStats,
    memories: List<MemoryItem>,
    limit: Int,
): String =
    buildString {
        appendLine("# Memory handoff")
        appendLine()
        appendLine("- Scope: local-device")
        appendLine("- Active memories: ${stats.activeMemoryCount}")
        appendLine("- Deleted memories: ${stats.deletedMemoryCount}")
        appendLine("- Total memories: ${stats.totalMemoryCount}")
        appendLine("- Active with source session: ${stats.activeWithSourceSessionCount}")
        appendLine("- Memories included: ${memories.size} of up to $limit")
        appendLine()
        appendLine("## Source types")
        if (stats.sourceTypeStats.isEmpty()) {
            appendLine("_No active source type counts._")
        } else {
            stats.sourceTypeStats.forEach { sourceTypeStats ->
                appendLine("- ${sourceTypeStats.sourceType}: ${sourceTypeStats.memoryCount}")
            }
        }
        appendLine()
        appendLine("## Memories")
        if (memories.isEmpty()) {
            appendLine("_No active memories included._")
        } else {
            memories.forEach { memory ->
                appendLine(memory.toMemoryHandoffMarkdownLine())
            }
        }
    }

private fun MemoryItem.toMemoryHandoffMarkdownLine(): String =
    buildString {
        append("- [")
        append(sourceType)
        append("] ")
        append(text.toMemoryHandoffLine())
        sourceSessionId?.let { sessionId ->
            append(" (sourceSession=")
            append(sessionId.toMemoryHandoffLine())
            append(")")
        }
    }

private fun String.toMemoryHandoffLine(): String =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .ifBlank { "(blank)" }

private fun memoryPayload(
    memory: MemoryItem,
    restored: Boolean? = null,
): JsonObject =
    buildJsonObject {
        put("id", memory.id)
        put("text", memory.text)
        memory.sourceSessionId?.let { put("sourceSessionId", it) }
        if (memory.sourceMessageIds.isNotEmpty()) {
            put(
                "sourceMessageIds",
                buildJsonArray {
                    memory.sourceMessageIds.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        put("sourceType", memory.sourceType)
        put("createdAt", memory.createdAt.toString())
        put("updatedAt", memory.updatedAt.toString())
        memory.deletedAt?.let { put("deletedAt", it.toString()) }
        restored?.let { put("restored", it) }
    }

private fun missingMemoryIdResult(summary: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "MISSING_MEMORY_ID",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_ID")
            },
    )

private fun missingMemoryTextResult(summary: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "MISSING_MEMORY_TEXT",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_TEXT")
            },
    )

private fun missingMemorySourceSessionIdResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide sourceSessionId or run within a session to list source-session memories.",
        errorCode = "MISSING_MEMORY_SOURCE_SESSION_ID",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_SOURCE_SESSION_ID")
                put("field", "sourceSessionId")
            },
    )

private fun missingMemorySourceTypeResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide sourceType=manual or sourceType=automatic.",
        errorCode = "MISSING_MEMORY_SOURCE_TYPE",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_SOURCE_TYPE")
                put("field", "sourceType")
                putAllowedMemorySourceTypes()
            },
    )

private fun invalidMemorySourceTypeResult(rawValue: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory sourceType must be manual or automatic.",
        errorCode = "INVALID_MEMORY_SOURCE_TYPE",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_MEMORY_SOURCE_TYPE")
                put("field", "sourceType")
                put("received", rawValue.take(MAX_MEMORY_SOURCE_TYPE_PAYLOAD_CHARS))
                putAllowedMemorySourceTypes()
            },
    )

private fun missingMemorySourceMessageIdResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide sourceMessageId or messageId to list source-message memories.",
        errorCode = "MISSING_MEMORY_SOURCE_MESSAGE_ID",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_SOURCE_MESSAGE_ID")
                put("field", "sourceMessageId")
            },
    )

private fun JsonObject.optionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
}

private fun JsonObject.optionalBoolean(
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

private fun JsonObject.sourceSessionIdOrContext(context: ToolExecutionContext): String? =
    optionalText("sourceSessionId")
        ?: optionalText("sessionId")
        ?: context.sessionId?.trim()?.ifBlank { null }

private fun JsonObject.memoryId(): String? = optionalText("id") ?: optionalText("memoryId")

private fun JsonObject.sourceMessageId(): String? =
    optionalText("sourceMessageId")
        ?.toSourceMessageIdOrNull()
        ?: optionalText("messageId")?.toSourceMessageIdOrNull()

private fun String.toSourceMessageIdOrNull(): String? =
    trim()
        .take(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS)
        .ifBlank { null }

private sealed interface MemorySourceTypeParseResult {
    data class Success(
        val value: String,
    ) : MemorySourceTypeParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemorySourceTypeParseResult
}

private fun JsonObject.parseMemorySourceType(): MemorySourceTypeParseResult {
    val rawValue = optionalText("sourceType") ?: optionalText("source")
    if (rawValue.isNullOrBlank()) {
        return MemorySourceTypeParseResult.Failure(missingMemorySourceTypeResult())
    }
    return normalizeMemorySourceType(rawValue)?.let(MemorySourceTypeParseResult::Success)
        ?: MemorySourceTypeParseResult.Failure(invalidMemorySourceTypeResult(rawValue))
}

private fun normalizeMemorySourceType(rawValue: String): String? =
    when (rawValue.trim().lowercase().replace("_", "-")) {
        MemoryRepository.SOURCE_TYPE_MANUAL -> MemoryRepository.SOURCE_TYPE_MANUAL
        MemoryRepository.SOURCE_TYPE_AUTOMATIC, "auto" -> MemoryRepository.SOURCE_TYPE_AUTOMATIC
        else -> null
    }

private sealed interface MemoryLimitParseResult {
    data class Success(
        val value: Int,
    ) : MemoryLimitParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemoryLimitParseResult
}

private fun JsonObject.parseMemoryLimit(
    field: String,
    defaultValue: Int,
    maxValue: Int,
): MemoryLimitParseResult {
    val rawValue = optionalText(field) ?: return MemoryLimitParseResult.Success(defaultValue)
    val parsedValue =
        rawValue.toLongOrNull()
            ?: return MemoryLimitParseResult.Failure(
                invalidMemoryLimitResult(
                    field = field,
                    maxValue = maxValue,
                    rawValue = rawValue,
                ),
            )
    if (parsedValue !in 1L..maxValue.toLong()) {
        return MemoryLimitParseResult.Failure(
            invalidMemoryLimitResult(
                field = field,
                maxValue = maxValue,
                rawValue = rawValue,
            ),
        )
    }
    return MemoryLimitParseResult.Success(parsedValue.toInt())
}

private fun invalidMemoryLimitResult(
    field: String,
    maxValue: Int,
    rawValue: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory $field must be an integer from 1 to $maxValue.",
        errorCode = "INVALID_MEMORY_LIMIT",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_MEMORY_LIMIT")
                put("field", field)
                put("max", maxValue)
                put("received", rawValue.take(MAX_MEMORY_LIMIT_PAYLOAD_CHARS))
            },
    )

private fun JsonObjectBuilder.putAllowedMemorySourceTypes() {
    put(
        "allowedSourceTypes",
        buildJsonArray {
            add(JsonPrimitive(MemoryRepository.SOURCE_TYPE_MANUAL))
            add(JsonPrimitive(MemoryRepository.SOURCE_TYPE_AUTOMATIC))
        },
    )
}

private val MEMORY_KNOWN_SOURCE_TYPES =
    setOf(
        MemoryRepository.SOURCE_TYPE_MANUAL,
        MemoryRepository.SOURCE_TYPE_AUTOMATIC,
    )
private const val MAX_MEMORY_SOURCE_TYPE_PAYLOAD_CHARS = 80
private const val MAX_MEMORY_LIMIT_PAYLOAD_CHARS = 80
private const val MEMORY_DOCTOR_DEFAULT_LIMIT = 20
private const val MEMORY_DOCTOR_MAX_LIMIT = 50
private const val MEMORY_DOCTOR_TEXT_MAX_CHARS = 500
private const val MEMORY_HANDOFF_DEFAULT_LIMIT = 8
private const val MEMORY_HANDOFF_MAX_LIMIT = 20
private const val MEMORY_SOURCE_SNIPPET_MAX_CHARS = 500
