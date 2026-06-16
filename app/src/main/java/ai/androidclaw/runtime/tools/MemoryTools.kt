package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.MemoryItem
import ai.androidclaw.data.repository.MemoryRepository
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
                context = context,
                command = arguments.optionalText("command").orEmpty(),
            )
        },
    )

private suspend fun executeMemoryCommand(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
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
    val verb = trimmed.substringBefore(' ').lowercase()
    val rest = trimmed.substringAfter(' ', "").trim()
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

private fun JsonObject.optionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
}

private fun JsonObject.sourceSessionIdOrContext(context: ToolExecutionContext): String? =
    optionalText("sourceSessionId")
        ?: optionalText("sessionId")
        ?: context.sessionId?.trim()?.ifBlank { null }

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

private const val MAX_MEMORY_SOURCE_TYPE_PAYLOAD_CHARS = 80
private const val MAX_MEMORY_LIMIT_PAYLOAD_CHARS = 80
