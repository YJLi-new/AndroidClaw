package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.MemoryItem
import ai.androidclaw.data.repository.MemoryRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
                            defaultValue = MEMORY_TOOL_DOCTOR_DEFAULT_LIMIT,
                            maxValue = MEMORY_TOOL_DOCTOR_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryDoctorResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeMarkdown = arguments.memoryOptionalBoolean("includeMarkdown", defaultValue = true),
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
                            defaultValue = MEMORY_TOOL_HANDOFF_DEFAULT_LIMIT,
                            maxValue = MEMORY_TOOL_HANDOFF_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryHandoffResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeMarkdown = arguments.memoryOptionalBoolean("includeMarkdown", defaultValue = true),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.timeline",
                    aliases =
                        listOf(
                            "memories.timeline",
                            "memory.activity",
                            "memories.activity",
                            "memory.history",
                            "memories.history",
                        ),
                    description = "Return a bounded local memory lifecycle timeline without exposing owner ids or message bodies.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum timeline entries. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDeleted",
                                description = "Set true to include deleted memory entries. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeText",
                                description = "Set false to omit memory text from entries and markdown. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit timelineMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MEMORY_TOOL_TIMELINE_DEFAULT_LIMIT,
                            maxValue = MEMORY_TOOL_TIMELINE_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryTimelineResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeDeleted = arguments.memoryOptionalBoolean("includeDeleted", defaultValue = false),
                includeText = arguments.memoryOptionalBoolean("includeText", defaultValue = true),
                includeMarkdown = arguments.memoryOptionalBoolean("includeMarkdown", defaultValue = true),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.export",
                    aliases =
                        listOf(
                            "memories.export",
                            "memory.backup",
                            "memories.backup",
                            "memory.dump",
                            "memories.dump",
                        ),
                    description = "Return a bounded portable local memory export without exposing owner ids or message bodies.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum export entries. Defaults to 50, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDeleted",
                                description = "Set true to include deleted memory entries. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeText",
                                description = "Set false to export metadata only. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exportMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MEMORY_TOOL_EXPORT_DEFAULT_LIMIT,
                            maxValue = MEMORY_TOOL_EXPORT_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryExportResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = limit,
                includeDeleted = arguments.memoryOptionalBoolean("includeDeleted", defaultValue = false),
                includeText = arguments.memoryOptionalBoolean("includeText", defaultValue = true),
                includeMarkdown = arguments.memoryOptionalBoolean("includeMarkdown", defaultValue = true),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "memory.import",
                    aliases =
                        listOf(
                            "memories.import",
                            "memory.ingest",
                            "memories.ingest",
                            "memory.restore_export",
                            "memories.restore_export",
                        ),
                    description = "Import a bounded set of local memory entries from a portable export payload.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "memories",
                                description = "Array of exported memory entries, or pass export.memories.",
                            ),
                            ToolArgumentSpec(
                                name = "export",
                                description = "Optional memory.export payload containing a memories array.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum entries to scan. Defaults to 50, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDeleted",
                                description = "Set true to import deleted export entries as active memories. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeText",
                                description = "Set false to omit memory text from the import result payload. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "dryRun",
                                description = "Set true to preview importable entries without writing memories. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM unless dryRun=true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                when (
                    val parsedLimit =
                        arguments.parseMemoryLimit(
                            field = "limit",
                            defaultValue = MEMORY_TOOL_IMPORT_DEFAULT_LIMIT,
                            maxValue = MEMORY_TOOL_IMPORT_MAX_LIMIT,
                        )
                ) {
                    is MemoryLimitParseResult.Failure -> return@Entry parsedLimit.result
                    is MemoryLimitParseResult.Success -> parsedLimit.value
                }
            memoryImportResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                arguments = arguments,
                limit = limit,
                includeDeleted = arguments.memoryOptionalBoolean("includeDeleted", defaultValue = false),
                includeText = arguments.memoryOptionalBoolean("includeText", defaultValue = true),
                dryRun = arguments.memoryOptionalBoolean("dryRun", defaultValue = false),
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
                includeMemoryText = arguments.memoryOptionalBoolean("includeMemoryText", defaultValue = true),
                includeSourceSnippets = arguments.memoryOptionalBoolean("includeSourceSnippets", defaultValue = true),
                includeMarkdown = arguments.memoryOptionalBoolean("includeMarkdown", defaultValue = true),
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
            val text = arguments.memoryOptionalText("text")
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
            val query = arguments.memoryOptionalText("query")
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
            val id = arguments.memoryOptionalText("id")
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
            val id = arguments.memoryOptionalText("id")
            if (id.isNullOrBlank()) {
                return@Entry missingMemoryIdResult("Provide a memory id to update.")
            }
            val text = arguments.memoryOptionalText("text")
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
            val id = arguments.memoryOptionalText("id")
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
            val id = arguments.memoryOptionalText("id")
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
            if (arguments.memoryOptionalText("confirm") != "CONFIRM") {
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
                command = arguments.memoryOptionalText("command").orEmpty(),
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
            limit = MEMORY_TOOL_DOCTOR_DEFAULT_LIMIT,
            includeMarkdown = true,
        )
    }
    if (trimmed.equals("handoff", ignoreCase = true) || trimmed.equals("snapshot", ignoreCase = true)) {
        return memoryHandoffResult(
            settingsDataStore = settingsDataStore,
            memoryRepository = memoryRepository,
            limit = MEMORY_TOOL_HANDOFF_DEFAULT_LIMIT,
            includeMarkdown = true,
        )
    }
    if (
        trimmed.equals("timeline", ignoreCase = true) ||
        trimmed.equals("activity", ignoreCase = true) ||
        trimmed.equals("history", ignoreCase = true)
    ) {
        return memoryTimelineResult(
            settingsDataStore = settingsDataStore,
            memoryRepository = memoryRepository,
            limit = MEMORY_TOOL_TIMELINE_DEFAULT_LIMIT,
            includeDeleted = trimmed.equals("history", ignoreCase = true),
            includeText = true,
            includeMarkdown = true,
        )
    }
    if (
        trimmed.equals("export", ignoreCase = true) ||
        trimmed.equals("backup", ignoreCase = true) ||
        trimmed.equals("dump", ignoreCase = true)
    ) {
        return memoryExportResult(
            settingsDataStore = settingsDataStore,
            memoryRepository = memoryRepository,
            limit = MEMORY_TOOL_EXPORT_DEFAULT_LIMIT,
            includeDeleted = false,
            includeText = true,
            includeMarkdown = true,
        )
    }
    val verb = trimmed.substringBefore(' ').lowercase()
    val rest = trimmed.substringAfter(' ', "").trim()
    if (verb == "doctor" || verb == "check" || verb == "health") {
        val limit =
            if (rest.isBlank()) {
                MEMORY_TOOL_DOCTOR_DEFAULT_LIMIT
            } else {
                val parsedLimit =
                    rest.toLongOrNull()
                        ?: return invalidMemoryLimitResult(
                            field = "limit",
                            maxValue = MEMORY_TOOL_DOCTOR_MAX_LIMIT,
                            rawValue = rest,
                        )
                if (parsedLimit !in 1L..MEMORY_TOOL_DOCTOR_MAX_LIMIT.toLong()) {
                    return invalidMemoryLimitResult(
                        field = "limit",
                        maxValue = MEMORY_TOOL_DOCTOR_MAX_LIMIT,
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
                    MEMORY_TOOL_HANDOFF_DEFAULT_LIMIT
                } else {
                    val parsedLimit =
                        rest.toLongOrNull()
                            ?: return invalidMemoryLimitResult(
                                field = "limit",
                                maxValue = MEMORY_TOOL_HANDOFF_MAX_LIMIT,
                                rawValue = rest,
                            )
                    if (parsedLimit !in 1L..MEMORY_TOOL_HANDOFF_MAX_LIMIT.toLong()) {
                        return invalidMemoryLimitResult(
                            field = "limit",
                            maxValue = MEMORY_TOOL_HANDOFF_MAX_LIMIT,
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

        "timeline", "activity", "history" -> {
            val timelineOptions =
                when (
                    val parsedOptions =
                        parseMemoryTimelineCommandOptions(
                            rest = rest,
                            defaultIncludeDeleted = verb == "history",
                        )
                ) {
                    is MemoryTimelineCommandParseResult.Failure -> return parsedOptions.result
                    is MemoryTimelineCommandParseResult.Success -> parsedOptions.value
                }
            memoryTimelineResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = timelineOptions.limit,
                includeDeleted = timelineOptions.includeDeleted,
                includeText = true,
                includeMarkdown = true,
            )
        }

        "export", "backup", "dump" -> {
            val exportOptions =
                when (val parsedOptions = parseMemoryExportCommandOptions(rest)) {
                    is MemoryExportCommandParseResult.Failure -> return parsedOptions.result
                    is MemoryExportCommandParseResult.Success -> parsedOptions.value
                }
            memoryExportResult(
                settingsDataStore = settingsDataStore,
                memoryRepository = memoryRepository,
                limit = exportOptions.limit,
                includeDeleted = exportOptions.includeDeleted,
                includeText = true,
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

private suspend fun memoryTimelineResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    limit: Int,
    includeDeleted: Boolean,
    includeText: Boolean,
    includeMarkdown: Boolean,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    if (!settings.enabled) {
        return memoryDisabledResult()
    }
    val stats = memoryRepository.stats(settings.installUserId)
    val memories =
        memoryRepository.listTimeline(
            ownerUserId = settings.installUserId,
            includeDeleted = includeDeleted,
            limit = limit,
        )
    val includedActiveMemoryCount = memories.count { memory -> memory.deletedAt == null }
    val includedDeletedMemoryCount = memories.count { memory -> memory.deletedAt != null }
    val eligibleMemoryCount =
        stats.activeMemoryCount +
            if (includeDeleted) {
                stats.deletedMemoryCount
            } else {
                0L
            }
    val timelineMarkdown =
        if (includeMarkdown) {
            memoryTimelineMarkdown(
                stats = stats,
                memories = memories,
                limit = limit,
                includeDeleted = includeDeleted,
                includeText = includeText,
            )
        } else {
            null
        }
    return ToolExecutionResult.success(
        summary =
            if (memories.isEmpty()) {
                "Prepared memory timeline with no matching memories."
            } else {
                "Prepared memory timeline with ${memories.size} memory item(s)."
            },
        payload =
            buildJsonObject {
                put("enabled", true)
                put("scope", "local-device")
                put("memoryLimit", limit)
                put("timelineLimit", limit)
                put("includeDeleted", includeDeleted)
                put("includeText", includeText)
                put("memoryTextIncluded", includeText)
                put("includeMarkdown", includeMarkdown)
                put("ownerUserIdIncluded", false)
                put("fullMessageBodiesIncluded", false)
                put("providerMetaIncluded", false)
                put("memoryCount", memories.size)
                put("timelineMemoryCount", memories.size)
                put("includedActiveMemoryCount", includedActiveMemoryCount)
                put("includedDeletedMemoryCount", includedDeletedMemoryCount)
                put("eligibleMemoryCount", eligibleMemoryCount)
                put("omittedTimelineMemoryCount", (eligibleMemoryCount - memories.size.toLong()).coerceAtLeast(0))
                put("excludedDeletedMemoryCount", if (includeDeleted) 0L else stats.deletedMemoryCount)
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
                put("timelineMarkdown", timelineMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memory.toMemoryTimelinePayload(includeText = includeText))
                        }
                    },
                )
            },
    )
}

private suspend fun memoryExportResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    limit: Int,
    includeDeleted: Boolean,
    includeText: Boolean,
    includeMarkdown: Boolean,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    if (!settings.enabled) {
        return memoryDisabledResult()
    }
    val stats = memoryRepository.stats(settings.installUserId)
    val memories =
        memoryRepository.listTimeline(
            ownerUserId = settings.installUserId,
            includeDeleted = includeDeleted,
            limit = limit,
        )
    val includedActiveMemoryCount = memories.count { memory -> memory.deletedAt == null }
    val includedDeletedMemoryCount = memories.count { memory -> memory.deletedAt != null }
    val eligibleMemoryCount =
        stats.activeMemoryCount +
            if (includeDeleted) {
                stats.deletedMemoryCount
            } else {
                0L
            }
    val exportMarkdown =
        if (includeMarkdown) {
            memoryExportMarkdown(
                stats = stats,
                memories = memories,
                limit = limit,
                includeDeleted = includeDeleted,
                includeText = includeText,
            )
        } else {
            null
        }
    return ToolExecutionResult.success(
        summary =
            if (memories.isEmpty()) {
                "Prepared memory export with no matching memories."
            } else {
                "Prepared memory export with ${memories.size} memory item(s)."
            },
        payload =
            buildJsonObject {
                put("enabled", true)
                put("scope", "local-device")
                put("exportFormat", MEMORY_TOOL_EXPORT_FORMAT)
                put("exportVersion", MEMORY_TOOL_EXPORT_VERSION)
                put("memoryLimit", limit)
                put("exportLimit", limit)
                put("includeDeleted", includeDeleted)
                put("includeText", includeText)
                put("memoryTextIncluded", includeText)
                put("includeMarkdown", includeMarkdown)
                put("ownerUserIdIncluded", false)
                put("fullMessageBodiesIncluded", false)
                put("providerMetaIncluded", false)
                put("memoryCount", memories.size)
                put("exportedMemoryCount", memories.size)
                put("includedActiveMemoryCount", includedActiveMemoryCount)
                put("includedDeletedMemoryCount", includedDeletedMemoryCount)
                put("eligibleMemoryCount", eligibleMemoryCount)
                put("omittedExportMemoryCount", (eligibleMemoryCount - memories.size.toLong()).coerceAtLeast(0))
                put("excludedDeletedMemoryCount", if (includeDeleted) 0L else stats.deletedMemoryCount)
                put("activeMemoryCount", stats.activeMemoryCount)
                put("deletedMemoryCount", stats.deletedMemoryCount)
                put("totalMemoryCount", stats.totalMemoryCount)
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
                put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "memories",
                    buildJsonArray {
                        memories.forEach { memory ->
                            add(memory.toMemoryExportPayload(includeText = includeText))
                        }
                    },
                )
            },
    )
}

private suspend fun memoryImportResult(
    settingsDataStore: SettingsDataStore,
    memoryRepository: MemoryRepository,
    arguments: JsonObject,
    limit: Int,
    includeDeleted: Boolean,
    includeText: Boolean,
    dryRun: Boolean,
): ToolExecutionResult {
    val settings = settingsDataStore.memorySettingsSnapshot()
    if (!settings.enabled) {
        return memoryDisabledResult()
    }
    if (!dryRun && arguments.memoryOptionalText("confirm") != "CONFIRM") {
        return missingMemoryImportConfirmationResult()
    }
    val rawEntries =
        when (val parsedEntries = arguments.memoryImportEntries()) {
            is MemoryImportEntriesParseResult.Failure -> return parsedEntries.result
            is MemoryImportEntriesParseResult.Success -> parsedEntries.entries
        }
    val scannedEntries = rawEntries.take(limit)
    val candidates = mutableListOf<MemoryImportCandidate>()
    val skipped = mutableListOf<MemoryImportSkippedEntry>()
    scannedEntries.forEachIndexed { sourceIndex, element ->
        when (
            val parsedCandidate =
                element.toMemoryImportCandidate(
                    sourceIndex = sourceIndex,
                    includeDeleted = includeDeleted,
                )
        ) {
            is MemoryImportCandidateParseResult.Candidate -> candidates += parsedCandidate.candidate
            is MemoryImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
        }
    }
    val activeMemoryCountBefore = memoryRepository.countActive(settings.installUserId)
    val importedMemories =
        if (dryRun) {
            emptyList()
        } else {
            candidates.mapNotNull { candidate ->
                memoryRepository
                    .remember(
                        ownerUserId = settings.installUserId,
                        text = candidate.text,
                        sourceSessionId = candidate.sourceSessionId,
                        sourceMessageIds = candidate.sourceMessageIds,
                        sourceType = candidate.sourceType,
                    )?.let { memory ->
                        MemoryImportedItem(
                            candidate = candidate,
                            memory = memory,
                        )
                    }
            }
        }
    val activeMemoryCountAfter =
        if (dryRun) {
            activeMemoryCountBefore
        } else {
            memoryRepository.countActive(settings.installUserId)
        }
    return ToolExecutionResult.success(
        summary =
            if (dryRun) {
                "Prepared dry-run memory import with ${candidates.size} importable memory item(s)."
            } else {
                "Imported ${importedMemories.size} memory item(s); skipped ${skipped.size}."
            },
        payload =
            buildJsonObject {
                put("enabled", true)
                put("scope", "local-device")
                put("acceptedExportFormat", MEMORY_TOOL_EXPORT_FORMAT)
                put("acceptedExportVersion", MEMORY_TOOL_EXPORT_VERSION)
                put("memoryLimit", limit)
                put("importLimit", limit)
                put("dryRun", dryRun)
                put("includeDeleted", includeDeleted)
                put("includeText", includeText)
                put("memoryTextIncluded", includeText)
                put("ownerUserIdIncluded", false)
                put("fullMessageBodiesIncluded", false)
                put("providerMetaIncluded", false)
                put("receivedMemoryCount", rawEntries.size)
                put("scannedMemoryCount", scannedEntries.size)
                put("omittedInputMemoryCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                put("importableMemoryCount", candidates.size)
                put("importedMemoryCount", importedMemories.size)
                put("skippedMemoryCount", skipped.size)
                put("invalidMemoryCount", skipped.count { entry -> entry.code.startsWith("memory.import.invalid") })
                put("deletedMemorySkippedCount", skipped.count { entry -> entry.code == "memory.import.deleted_skipped" })
                put("sourceTypeAdjustedCount", candidates.count(MemoryImportCandidate::sourceTypeAdjusted))
                put("deletedEntryImportableCount", candidates.count(MemoryImportCandidate::importedFromDeleted))
                put("activeMemoryCountBefore", activeMemoryCountBefore)
                put("activeMemoryCountAfter", activeMemoryCountAfter)
                put("newActiveMemoryCountDelta", activeMemoryCountAfter - activeMemoryCountBefore)
                put(
                    "candidateMemories",
                    buildJsonArray {
                        candidates.forEach { candidate ->
                            add(candidate.toMemoryImportCandidatePayload(includeText = includeText))
                        }
                    },
                )
                put(
                    "importedMemories",
                    buildJsonArray {
                        importedMemories.forEach { importedItem ->
                            add(importedItem.toMemoryImportedPayload(includeText = includeText))
                        }
                    },
                )
                put(
                    "skippedMemories",
                    buildJsonArray {
                        skipped.forEach { skippedEntry ->
                            add(skippedEntry.toMemoryImportSkippedPayload())
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
