package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.MemoryItem
import ai.androidclaw.data.repository.MemoryRepository

private const val MEMORY_SEARCH_DEFAULT_LIMIT = 10
private const val MEMORY_SEARCH_MAX_LIMIT = 30
private const val MEMORY_LINE_MAX_CHARS = 300

internal fun memoryToolEntries(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory_search",
                    aliases = listOf("memory.search", "memory.list"),
                    description = "Search cross-session memories. Empty query lists the most recent ones.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("query", "Words to search for."),
                            intArg("limit", "Max results, default 10, max 30."),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabled()
            }
            val limit = arguments.boundedInt("limit", MEMORY_SEARCH_DEFAULT_LIMIT, 1, MEMORY_SEARCH_MAX_LIMIT)
            val query = arguments.optionalText("query")
            val memories =
                if (query == null) {
                    memoryRepository.listRecent(settings.installUserId, limit)
                } else {
                    memoryRepository.search(settings.installUserId, query, limit)
                }
            toolSuccess(summary = memories.toMemorySummary(if (query == null) "No memories stored yet." else "No matching memories."))
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory_get",
                    aliases = listOf("memory.get"),
                    description = "Read one memory by id.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments = listOf(stringArg("id", "Memory id.", required = true)),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabled()
            }
            val id = arguments.requireText("id", "memory_get")
            val memory =
                memoryRepository.get(settings.installUserId, id)
                    ?: return@Entry toolFailure("MEMORY_NOT_FOUND", "Memory $id was not found.")
            toolSuccess(summary = "Memory ${memory.id} (${memory.sourceType}, ${memory.createdAt}): ${memory.text}")
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory_remember",
                    aliases = listOf("memory.remember"),
                    description = "Store a short durable fact about the user for future sessions.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments = listOf(stringArg("text", "The fact, one or two sentences.", required = true)),
                ),
        ) { context, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            if (!settings.enabled) {
                return@Entry memoryDisabled()
            }
            val text = arguments.requireText("text", "memory_remember")
            val memory =
                memoryRepository.remember(
                    ownerUserId = settings.installUserId,
                    text = text,
                    sourceSessionId = context.sessionId,
                    sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                ) ?: return@Entry toolFailure("MEMORY_NOT_STORED", "Memory text was empty after cleanup.")
            toolSuccess(summary = "Remembered (id=${memory.id}): ${memory.text}")
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory_forget",
                    aliases = listOf("memory.delete", "memory.forget"),
                    description = "Delete one memory by id.",
                    riskTier = ToolRiskTier.Destructive,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments = listOf(stringArg("id", "Memory id.", required = true)),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.memorySettingsSnapshot()
            val id = arguments.requireText("id", "memory_forget")
            if (memoryRepository.delete(settings.installUserId, id)) {
                toolSuccess(summary = "Deleted memory $id.")
            } else {
                toolFailure("MEMORY_NOT_FOUND", "Memory $id was not found.")
            }
        },
    )

internal fun memoryDisabled(): ToolExecutionResult =
    toolFailure(
        errorCode = "MEMORY_DISABLED",
        summary = "Memory is turned off in Settings. Keep notes in the workspace file MEMORY.md instead.",
    )

internal fun List<MemoryItem>.toMemorySummary(emptySummary: String): String =
    if (isEmpty()) {
        emptySummary
    } else {
        buildString {
            append("$size memory item(s):")
            this@toMemorySummary.forEach { memory ->
                append("\n- [").append(memory.id).append("] ").append(memory.text.singleLine().clip(MEMORY_LINE_MAX_CHARS))
            }
        }
    }
