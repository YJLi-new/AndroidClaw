package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.MemoryItem
import ai.androidclaw.data.repository.MemoryRepository
import kotlinx.serialization.json.JsonObject
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
                    name = "memory.delete",
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
                return@Entry ToolExecutionResult.failure(
                    summary = "Provide a memory id to delete.",
                    errorCode = "MISSING_MEMORY_ID",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_MEMORY_ID")
                        },
                )
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
                    name = "memory.clear",
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

        "delete" -> {
            if (rest.isBlank()) {
                return ToolExecutionResult.failure(
                    summary = "Provide a memory id after /memory delete.",
                    errorCode = "MISSING_MEMORY_ID",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_MEMORY_ID")
                        },
                )
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

private fun memoryPayload(memory: MemoryItem): JsonObject =
    buildJsonObject {
        put("id", memory.id)
        put("text", memory.text)
        memory.sourceSessionId?.let { put("sourceSessionId", it) }
        put("sourceType", memory.sourceType)
        put("createdAt", memory.createdAt.toString())
    }

private fun JsonObject.optionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
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

private const val MAX_MEMORY_LIMIT_PAYLOAD_CHARS = 80
