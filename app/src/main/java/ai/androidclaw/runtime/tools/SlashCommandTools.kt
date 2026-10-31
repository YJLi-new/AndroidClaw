package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository

internal const val COMPACT_SUMMARY_MAX_CHARS = 4_000
internal const val SESSIONS_COMPACT_TOOL_NAME = "sessions_compact"
internal const val LEGACY_SESSIONS_COMPACT_TOOL_NAME = "sessions.compact"

/** Helpers dispatched by bundled slash-command skills (`/compact`, `/memory`); never offered to the model. */
internal fun slashCommandToolEntries(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository?,
): List<ToolRegistry.Entry> =
    buildList {
        add(compactToolEntry(sessionRepository, messageRepository))
        memoryRepository?.let { repository -> add(memoryCommandToolEntry(settingsDataStore, repository)) }
    }

private fun compactToolEntry(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = SESSIONS_COMPACT_TOOL_NAME,
                aliases = listOf(LEGACY_SESSIONS_COMPACT_TOOL_NAME, "session.compact"),
                description = "Store a compacted summary and hide older messages of the current session.",
                riskTier = ToolRiskTier.WritesLocalState,
                allowedOrigins = SLASH_COMMAND_ORIGINS,
                arguments =
                    listOf(
                        stringArg("summary", "Summary text to store."),
                        stringArg("command", "Raw /compact arguments, used as the summary fallback."),
                        stringArg("compactedUntilMessageId", "Last message id covered by the summary."),
                    ),
            ),
    ) { context, arguments ->
        val sessionId =
            context.sessionId?.takeIf { sessionRepository.getSession(it) != null }
                ?: return@Entry toolFailure("MISSING_SESSION", "No active session is available to compact.")
        val boundaryId =
            arguments.optionalText("compactedUntilMessageId")
                ?: return@Entry toolFailure("EMPTY_COMPACT_SOURCE", "No earlier messages are available to compact.")
        val boundary = messageRepository.getMessagesByIds(listOf(boundaryId))[boundaryId]
        if (boundary?.sessionId != sessionId) {
            return@Entry toolFailure("INVALID_COMPACT_BOUNDARY", "Compaction boundary was not found in this session.")
        }
        val summary =
            arguments.optionalText("summary") ?: arguments.optionalText("command")
                ?: return@Entry toolFailure("MISSING_SUMMARY", "Provide an explicit summary after /compact.")
        val bounded = summary.take(COMPACT_SUMMARY_MAX_CHARS)
        sessionRepository.updateSummaryAndCompactionBoundary(
            id = sessionId,
            summaryText = bounded,
            compactedUntilMessageId = boundaryId,
        )
        // No payload: the chat UI matches this exact result text to hide compact control rows.
        toolSuccess(summary = "Compacted this session summary and hid older messages.")
    }

private fun memoryCommandToolEntry(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = "memory_command",
                aliases = listOf("memory.command"),
                description = "Run a /memory command: status, list, search <q>, remember <text>, delete <id>, clear CONFIRM.",
                riskTier = ToolRiskTier.WritesLocalState,
                allowedOrigins = SLASH_COMMAND_ORIGINS,
                arguments = listOf(stringArg("command", "Raw /memory arguments.")),
            ),
    ) { context, arguments ->
        val command = arguments.optionalText("command").orEmpty()
        val verb = command.substringBefore(' ').lowercase()
        val rest = command.substringAfter(' ', "").trim()
        val settings = settingsDataStore.memorySettingsSnapshot()
        when (verb) {
            "", "status" -> {
                val count = memoryRepository.countActive(settings.installUserId)
                toolSuccess(
                    summary =
                        "Memory is ${if (settings.enabled) "on" else "off"}; $count stored item(s). " +
                            "Commands: /memory list, search <q>, remember <text>, delete <id>, clear CONFIRM.",
                )
            }
            "list" ->
                if (!settings.enabled) {
                    memoryDisabled()
                } else {
                    toolSuccess(memoryRepository.listRecent(settings.installUserId).toMemorySummary("No memories stored."))
                }
            "search" ->
                when {
                    !settings.enabled -> memoryDisabled()
                    rest.isBlank() -> toolFailure("MISSING_MEMORY_QUERY", "Provide a query after /memory search.")
                    else ->
                        toolSuccess(
                            memoryRepository
                                .search(settings.installUserId, rest)
                                .toMemorySummary("No matching memories."),
                        )
                }
            "remember" ->
                when {
                    !settings.enabled -> memoryDisabled()
                    else -> {
                        val memory =
                            memoryRepository.remember(
                                ownerUserId = settings.installUserId,
                                text = rest,
                                sourceSessionId = context.sessionId,
                                sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                            )
                        if (memory == null) {
                            toolFailure("MISSING_MEMORY_TEXT", "Provide memory text after /memory remember.")
                        } else {
                            toolSuccess("Stored memory: ${memory.text}")
                        }
                    }
                }
            "delete", "forget" ->
                when {
                    rest.isBlank() -> toolFailure("MISSING_MEMORY_ID", "Provide a memory id after /memory delete.")
                    memoryRepository.delete(settings.installUserId, rest) -> toolSuccess("Deleted memory $rest.")
                    else -> toolFailure("MEMORY_NOT_FOUND", "Memory $rest was not found.")
                }
            "clear" ->
                if (rest != "CONFIRM") {
                    toolFailure("CONFIRMATION_REQUIRED", "Type /memory clear CONFIRM to delete every stored memory.")
                } else {
                    toolSuccess("Cleared ${memoryRepository.clear(settings.installUserId)} memory item(s).")
                }
            else -> toolFailure("UNKNOWN_MEMORY_COMMAND", "Unknown /memory command \"$verb\". Try /memory status.")
        }
    }
