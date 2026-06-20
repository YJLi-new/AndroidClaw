package ai.androidclaw.runtime.tools

import ai.androidclaw.data.MemorySettingsSnapshot
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MemoryItem
import ai.androidclaw.data.model.Session
import ai.androidclaw.data.repository.MemoryRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun memoryDisabledResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory is disabled. Enable it in Settings before using memory tools.",
        errorCode = "MEMORY_DISABLED",
        payload =
            buildJsonObject {
                put("errorCode", "MEMORY_DISABLED")
                put("enabled", false)
            },
    )

internal fun memoryNotFoundResult(id: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory $id was not found.",
        errorCode = "MEMORY_NOT_FOUND",
        payload =
            buildJsonObject {
                put("id", id)
                put("errorCode", "MEMORY_NOT_FOUND")
            },
    )

internal fun missingMemoryImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import memories, or dryRun=true to preview without writing.",
        errorCode = "MISSING_MEMORY_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

internal fun missingMemoryImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide a memories array or an export object containing memories to import.",
        errorCode = "MISSING_MEMORY_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_IMPORT_ENTRIES")
                put("field", "memories")
            },
    )

internal fun invalidMemoryImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory import entries must be an array.",
        errorCode = "INVALID_MEMORY_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_MEMORY_IMPORT_ENTRIES")
                put("field", "memories")
            },
    )

internal fun memoryListResult(
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

internal fun memorySessionListResult(
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

internal fun memorySourceTypeListResult(
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

internal fun memorySourceMessageListResult(
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

internal fun memoryGetResult(
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

internal fun memoryUpdateResult(
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

internal fun memoryRestoreResult(
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

internal data class MemoryDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val memoryId: String?,
    val sourceType: String?,
    val summary: String,
    val action: String,
    val detail: String? = null,
)

internal data class MemorySourceMessageReference(
    val sourceMessageId: String,
    val message: ChatMessage?,
)

internal data class MemoryImportCandidate(
    val sourceIndex: Int,
    val text: String,
    val sourceSessionId: String?,
    val sourceMessageIds: List<String>,
    val sourceType: String,
    val sourceTypeAdjusted: Boolean,
    val importedFromDeleted: Boolean,
)

internal data class MemoryImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

internal data class MemoryImportedItem(
    val candidate: MemoryImportCandidate,
    val memory: MemoryItem,
)

internal fun buildMemoryDoctorIssues(
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

internal fun MemoryRepository.MemoryStats.toMemoryDoctorStatsPayload(enabled: Boolean): JsonObject =
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

internal fun MemoryItem.toMemoryDoctorCheckPayload(): JsonObject =
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

internal fun List<MemoryDoctorIssue>.toMemoryDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun MemoryDoctorIssue.toMemoryDoctorPayload(): JsonObject =
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

internal fun List<MemoryDoctorIssue>.toMemoryDoctorMarkdown(
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

internal fun MemoryDoctorIssue.toMemoryDoctorMarkdownLine(): String =
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

internal fun String.toMemoryDoctorText(): String = toMemoryHandoffLine().take(MEMORY_TOOL_DOCTOR_TEXT_MAX_CHARS)

internal fun Session.toMemorySourceSessionPayload(): JsonObject =
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

internal fun MemorySourceMessageReference.toMemorySourceMessagePayload(includeSourceSnippet: Boolean): JsonObject {
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

internal fun ChatMessage.toMemorySourceMessagePayload(
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

internal fun MemoryItem.toMemoryProvenanceMarkdown(
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

internal fun String.toMemorySourceSnippet(): String =
    if (length <= MEMORY_TOOL_SOURCE_SNIPPET_MAX_CHARS) {
        this
    } else {
        take(MEMORY_TOOL_SOURCE_SNIPPET_MAX_CHARS)
    }

internal fun memoryTimelineMarkdown(
    stats: MemoryRepository.MemoryStats,
    memories: List<MemoryItem>,
    limit: Int,
    includeDeleted: Boolean,
    includeText: Boolean,
): String =
    buildString {
        appendLine("# Memory timeline")
        appendLine()
        appendLine("- Scope: local-device")
        appendLine("- Active memories: ${stats.activeMemoryCount}")
        appendLine("- Deleted memories: ${stats.deletedMemoryCount}")
        appendLine("- Total memories: ${stats.totalMemoryCount}")
        appendLine("- Include deleted: $includeDeleted")
        appendLine("- Memory text included: $includeText")
        appendLine("- Owner user id included: false")
        appendLine("- Full message bodies included: false")
        appendLine("- Provider metadata included: false")
        appendLine("- Timeline entries included: ${memories.size} of up to $limit")
        appendLine()
        appendLine("## Timeline")
        if (memories.isEmpty()) {
            appendLine("_No memory timeline entries included._")
        } else {
            memories.forEach { memory ->
                appendLine(memory.toMemoryTimelineMarkdownLine(includeText = includeText))
            }
        }
    }

internal fun MemoryItem.toMemoryTimelineMarkdownLine(includeText: Boolean): String =
    buildString {
        append("- ")
        append(memoryTimelineStatus())
        append(" `")
        append(id.toMemoryHandoffLine())
        append("` ")
        append("at=")
        append(memoryTimelineAt())
        append(" sourceType=")
        append(sourceType.toMemoryHandoffLine())
        sourceSessionId?.let { sessionId ->
            append(" sourceSession=")
            append(sessionId.toMemoryHandoffLine())
        }
        append(" sourceMessages=")
        append(sourceMessageIds.size)
        append(": ")
        append(if (includeText) text.toMemoryHandoffLine() else "_Memory text omitted._")
    }

internal fun MemoryItem.toMemoryTimelinePayload(includeText: Boolean): JsonObject =
    buildJsonObject {
        put("id", id)
        put("status", memoryTimelineStatus())
        put("deleted", deletedAt != null)
        put(
            "lifecycleEvent",
            if (deletedAt == null) {
                "updated"
            } else {
                "deleted"
            },
        )
        put("lifecycleAt", memoryTimelineAt().toString())
        put("sourceType", sourceType)
        put("sourceSessionId", sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceMessageCount", sourceMessageIds.size)
        put(
            "sourceMessageIds",
            buildJsonArray {
                sourceMessageIds.forEach { sourceMessageId ->
                    add(JsonPrimitive(sourceMessageId))
                }
            },
        )
        put("createdAt", createdAt.toString())
        put("updatedAt", updatedAt.toString())
        put("deletedAt", deletedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("textLength", text.length)
        put("text", if (includeText) JsonPrimitive(text) else JsonNull)
        put("memoryTextIncluded", includeText)
        put("ownerUserIdIncluded", false)
        put("fullMessageBodiesIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun MemoryItem.memoryTimelineStatus(): String =
    if (deletedAt == null) {
        "Active"
    } else {
        "Deleted"
    }

internal fun MemoryItem.memoryTimelineAt() = deletedAt ?: updatedAt

internal fun memoryExportMarkdown(
    stats: MemoryRepository.MemoryStats,
    memories: List<MemoryItem>,
    limit: Int,
    includeDeleted: Boolean,
    includeText: Boolean,
): String =
    buildString {
        appendLine("# Memory export")
        appendLine()
        appendLine("- Format: $MEMORY_TOOL_EXPORT_FORMAT")
        appendLine("- Version: $MEMORY_TOOL_EXPORT_VERSION")
        appendLine("- Scope: local-device")
        appendLine("- Active memories: ${stats.activeMemoryCount}")
        appendLine("- Deleted memories: ${stats.deletedMemoryCount}")
        appendLine("- Total memories: ${stats.totalMemoryCount}")
        appendLine("- Include deleted: $includeDeleted")
        appendLine("- Memory text included: $includeText")
        appendLine("- Owner user id included: false")
        appendLine("- Full message bodies included: false")
        appendLine("- Provider metadata included: false")
        appendLine("- Memories exported: ${memories.size} of up to $limit")
        appendLine()
        appendLine("## Memories")
        if (memories.isEmpty()) {
            appendLine("_No memories exported._")
        } else {
            memories.forEach { memory ->
                appendLine(memory.toMemoryExportMarkdownLine(includeText = includeText))
            }
        }
    }

internal fun MemoryItem.toMemoryExportMarkdownLine(includeText: Boolean): String =
    buildString {
        append("- ")
        append(memoryTimelineStatus())
        append(" `")
        append(id.toMemoryHandoffLine())
        append("` sourceType=")
        append(sourceType.toMemoryHandoffLine())
        append(" createdAt=")
        append(createdAt)
        deletedAt?.let { deletedAt ->
            append(" deletedAt=")
            append(deletedAt)
        }
        sourceSessionId?.let { sessionId ->
            append(" sourceSession=")
            append(sessionId.toMemoryHandoffLine())
        }
        append(" sourceMessages=")
        append(sourceMessageIds.size)
        append(": ")
        append(if (includeText) text.toMemoryHandoffLine() else "_Memory text omitted._")
    }

internal fun MemoryItem.toMemoryExportPayload(includeText: Boolean): JsonObject =
    buildJsonObject {
        put("id", id)
        put("sourceMemoryId", id)
        put("status", memoryTimelineStatus())
        put("deleted", deletedAt != null)
        put("sourceType", sourceType)
        put("sourceSessionId", sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceMessageCount", sourceMessageIds.size)
        put(
            "sourceMessageIds",
            buildJsonArray {
                sourceMessageIds.forEach { sourceMessageId ->
                    add(JsonPrimitive(sourceMessageId))
                }
            },
        )
        put("createdAt", createdAt.toString())
        put("updatedAt", updatedAt.toString())
        put("deletedAt", deletedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("textLength", text.length)
        put("text", if (includeText) JsonPrimitive(text) else JsonNull)
        put("memoryTextIncluded", includeText)
        put("ownerUserIdIncluded", false)
        put("fullMessageBodiesIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun MemoryImportCandidate.toMemoryImportCandidatePayload(includeText: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("status", if (importedFromDeleted) "DeletedSource" else "ActiveSource")
        put("sourceType", sourceType)
        put("sourceTypeAdjusted", sourceTypeAdjusted)
        put("importedFromDeleted", importedFromDeleted)
        put("sourceSessionId", sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceMessageCount", sourceMessageIds.size)
        put(
            "sourceMessageIds",
            buildJsonArray {
                sourceMessageIds.forEach { sourceMessageId ->
                    add(JsonPrimitive(sourceMessageId))
                }
            },
        )
        put("textLength", text.length)
        put("text", if (includeText) JsonPrimitive(text) else JsonNull)
        put("memoryTextIncluded", includeText)
        put("ownerUserIdIncluded", false)
        put("fullMessageBodiesIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun MemoryImportedItem.toMemoryImportedPayload(includeText: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", candidate.sourceIndex)
        put("id", memory.id)
        put("status", memory.memoryTimelineStatus())
        put("sourceType", memory.sourceType)
        put("sourceTypeAdjusted", candidate.sourceTypeAdjusted)
        put("importedFromDeleted", candidate.importedFromDeleted)
        put("sourceSessionId", memory.sourceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceMessageCount", memory.sourceMessageIds.size)
        put(
            "sourceMessageIds",
            buildJsonArray {
                memory.sourceMessageIds.forEach { sourceMessageId ->
                    add(JsonPrimitive(sourceMessageId))
                }
            },
        )
        put("createdAt", memory.createdAt.toString())
        put("updatedAt", memory.updatedAt.toString())
        put("deletedAt", memory.deletedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("textLength", memory.text.length)
        put("text", if (includeText) JsonPrimitive(memory.text) else JsonNull)
        put("memoryTextIncluded", includeText)
        put("ownerUserIdIncluded", false)
        put("fullMessageBodiesIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun MemoryImportSkippedEntry.toMemoryImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
        put("ownerUserIdIncluded", false)
        put("memoryTextIncluded", false)
        put("fullMessageBodiesIncluded", false)
        put("providerMetaIncluded", false)
    }

internal fun memoryHandoffMarkdown(
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

internal fun MemoryItem.toMemoryHandoffMarkdownLine(): String =
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

internal fun String.toMemoryHandoffLine(): String =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .ifBlank { "(blank)" }

internal fun memoryPayload(
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

internal fun missingMemoryIdResult(summary: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "MISSING_MEMORY_ID",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_ID")
            },
    )

internal fun missingMemoryTextResult(summary: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "MISSING_MEMORY_TEXT",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_TEXT")
            },
    )

internal fun missingMemorySourceSessionIdResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide sourceSessionId or run within a session to list source-session memories.",
        errorCode = "MISSING_MEMORY_SOURCE_SESSION_ID",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_SOURCE_SESSION_ID")
                put("field", "sourceSessionId")
            },
    )

internal fun missingMemorySourceTypeResult(): ToolExecutionResult =
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

internal fun invalidMemorySourceTypeResult(rawValue: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Memory sourceType must be manual or automatic.",
        errorCode = "INVALID_MEMORY_SOURCE_TYPE",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_MEMORY_SOURCE_TYPE")
                put("field", "sourceType")
                put("received", rawValue.take(MEMORY_TOOL_MAX_SOURCE_TYPE_PAYLOAD_CHARS))
                putAllowedMemorySourceTypes()
            },
    )

internal fun missingMemorySourceMessageIdResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide sourceMessageId or messageId to list source-message memories.",
        errorCode = "MISSING_MEMORY_SOURCE_MESSAGE_ID",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_MEMORY_SOURCE_MESSAGE_ID")
                put("field", "sourceMessageId")
            },
    )

internal fun JsonObject.memoryOptionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
}

internal fun JsonObject.memoryOptionalBoolean(
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

internal fun JsonObject.sourceSessionIdOrContext(context: ToolExecutionContext): String? =
    memoryOptionalText("sourceSessionId")
        ?: memoryOptionalText("sessionId")
        ?: context.sessionId?.trim()?.ifBlank { null }

internal fun JsonObject.memoryId(): String? = memoryOptionalText("id") ?: memoryOptionalText("memoryId")

internal fun JsonObject.sourceMessageId(): String? =
    memoryOptionalText("sourceMessageId")
        ?.toSourceMessageIdOrNull()
        ?: memoryOptionalText("messageId")?.toSourceMessageIdOrNull()

internal fun String.toSourceMessageIdOrNull(): String? =
    trim()
        .take(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS)
        .ifBlank { null }

internal fun JsonObject.memoryImportEntries(): MemoryImportEntriesParseResult {
    val directEntries = this["memories"]
    val exportEntries = (this["export"] as? JsonObject)?.get("memories")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("memories")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return MemoryImportEntriesParseResult.Failure(
            missingMemoryImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(MemoryImportEntriesParseResult::Success)
        ?: MemoryImportEntriesParseResult.Failure(invalidMemoryImportEntriesResult())
}

internal fun JsonElement.toMemoryImportCandidate(
    sourceIndex: Int,
    includeDeleted: Boolean,
): MemoryImportCandidateParseResult {
    val objectValue = this as? JsonObject
    if (objectValue == null) {
        val text =
            (this as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
                ?: return memoryImportSkipped(
                    sourceIndex = sourceIndex,
                    code = "memory.import.invalid_entry",
                    summary = "Import entry must be an object or non-blank text.",
                )
        return MemoryImportCandidateParseResult.Candidate(
            MemoryImportCandidate(
                sourceIndex = sourceIndex,
                text = text,
                sourceSessionId = null,
                sourceMessageIds = emptyList(),
                sourceType = MemoryRepository.SOURCE_TYPE_MANUAL,
                sourceTypeAdjusted = false,
                importedFromDeleted = false,
            ),
        )
    }
    val deleted = objectValue.importDeleted()
    if (deleted && !includeDeleted) {
        return memoryImportSkipped(
            sourceIndex = sourceIndex,
            code = "memory.import.deleted_skipped",
            summary = "Deleted export entry skipped because includeDeleted=false.",
        )
    }
    val text =
        objectValue.memoryOptionalText("text")
            ?: return memoryImportSkipped(
                sourceIndex = sourceIndex,
                code = "memory.import.invalid_missing_text",
                summary = "Import entry skipped because text is missing or blank.",
            )
    val rawSourceType = objectValue.memoryOptionalText("sourceType") ?: MemoryRepository.SOURCE_TYPE_MANUAL
    val sourceType = normalizeMemorySourceType(rawSourceType) ?: MemoryRepository.SOURCE_TYPE_MANUAL
    return MemoryImportCandidateParseResult.Candidate(
        MemoryImportCandidate(
            sourceIndex = sourceIndex,
            text = text,
            sourceSessionId = objectValue.memoryOptionalText("sourceSessionId"),
            sourceMessageIds = objectValue.importSourceMessageIds(),
            sourceType = sourceType,
            sourceTypeAdjusted = sourceType != rawSourceType,
            importedFromDeleted = deleted,
        ),
    )
}

internal fun memoryImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): MemoryImportCandidateParseResult.Skipped =
    MemoryImportCandidateParseResult.Skipped(
        MemoryImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

internal fun JsonObject.importDeleted(): Boolean =
    memoryOptionalBoolean("deleted", defaultValue = false) ||
        (this["deletedAt"] != null && this["deletedAt"] != JsonNull)

internal fun JsonObject.importSourceMessageIds(): List<String> {
    val sourceMessageIds = mutableListOf<String>()
    (this["sourceMessageIds"] as? JsonArray)
        ?.forEach { element ->
            (element as? JsonPrimitive)
                ?.contentOrNull
                ?.toSourceMessageIdOrNull()
                ?.let(sourceMessageIds::add)
        }
    sourceMessageId()?.let(sourceMessageIds::add)
    return sourceMessageIds
        .distinct()
        .take(MemoryRepository.MAX_SOURCE_MESSAGE_IDS)
}

internal sealed interface MemorySourceTypeParseResult {
    data class Success(
        val value: String,
    ) : MemorySourceTypeParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemorySourceTypeParseResult
}

internal fun JsonObject.parseMemorySourceType(): MemorySourceTypeParseResult {
    val rawValue = memoryOptionalText("sourceType") ?: memoryOptionalText("source")
    if (rawValue.isNullOrBlank()) {
        return MemorySourceTypeParseResult.Failure(missingMemorySourceTypeResult())
    }
    return normalizeMemorySourceType(rawValue)?.let(MemorySourceTypeParseResult::Success)
        ?: MemorySourceTypeParseResult.Failure(invalidMemorySourceTypeResult(rawValue))
}

internal fun normalizeMemorySourceType(rawValue: String): String? =
    when (rawValue.trim().lowercase().replace("_", "-")) {
        MemoryRepository.SOURCE_TYPE_MANUAL -> MemoryRepository.SOURCE_TYPE_MANUAL
        MemoryRepository.SOURCE_TYPE_AUTOMATIC, "auto" -> MemoryRepository.SOURCE_TYPE_AUTOMATIC
        else -> null
    }

internal sealed interface MemoryLimitParseResult {
    data class Success(
        val value: Int,
    ) : MemoryLimitParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemoryLimitParseResult
}

internal data class MemoryTimelineCommandOptions(
    val limit: Int,
    val includeDeleted: Boolean,
)

internal data class MemoryExportCommandOptions(
    val limit: Int,
    val includeDeleted: Boolean,
)

internal sealed interface MemoryTimelineCommandParseResult {
    data class Success(
        val value: MemoryTimelineCommandOptions,
    ) : MemoryTimelineCommandParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemoryTimelineCommandParseResult
}

internal sealed interface MemoryExportCommandParseResult {
    data class Success(
        val value: MemoryExportCommandOptions,
    ) : MemoryExportCommandParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemoryExportCommandParseResult
}

internal sealed interface MemoryImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : MemoryImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : MemoryImportEntriesParseResult
}

internal sealed interface MemoryImportCandidateParseResult {
    data class Candidate(
        val candidate: MemoryImportCandidate,
    ) : MemoryImportCandidateParseResult

    data class Skipped(
        val skipped: MemoryImportSkippedEntry,
    ) : MemoryImportCandidateParseResult
}

internal fun JsonObject.parseMemoryLimit(
    field: String,
    defaultValue: Int,
    maxValue: Int,
): MemoryLimitParseResult {
    val rawValue = memoryOptionalText(field) ?: return MemoryLimitParseResult.Success(defaultValue)
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

internal fun parseMemoryTimelineCommandOptions(
    rest: String,
    defaultIncludeDeleted: Boolean,
): MemoryTimelineCommandParseResult {
    val tokens =
        rest
            .split(Regex("\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
    if (tokens.isEmpty()) {
        return MemoryTimelineCommandParseResult.Success(
            MemoryTimelineCommandOptions(
                limit = MEMORY_TOOL_TIMELINE_DEFAULT_LIMIT,
                includeDeleted = defaultIncludeDeleted,
            ),
        )
    }
    val deletedTokens = setOf("all", "deleted", "trash", "with-deleted", "include-deleted")
    val includeDeleted =
        defaultIncludeDeleted ||
            tokens.any { token -> token.lowercase().replace("_", "-") in deletedTokens }
    val limitTokens = tokens.filterNot { token -> token.lowercase().replace("_", "-") in deletedTokens }
    if (limitTokens.size > 1) {
        return MemoryTimelineCommandParseResult.Failure(
            invalidMemoryLimitResult(
                field = "limit",
                maxValue = MEMORY_TOOL_TIMELINE_MAX_LIMIT,
                rawValue = rest,
            ),
        )
    }
    val limitToken = limitTokens.singleOrNull()
    val limit =
        if (limitToken == null) {
            MEMORY_TOOL_TIMELINE_DEFAULT_LIMIT
        } else {
            val parsedLimit =
                limitToken.toLongOrNull()
                    ?: return MemoryTimelineCommandParseResult.Failure(
                        invalidMemoryLimitResult(
                            field = "limit",
                            maxValue = MEMORY_TOOL_TIMELINE_MAX_LIMIT,
                            rawValue = limitToken,
                        ),
                    )
            if (parsedLimit !in 1L..MEMORY_TOOL_TIMELINE_MAX_LIMIT.toLong()) {
                return MemoryTimelineCommandParseResult.Failure(
                    invalidMemoryLimitResult(
                        field = "limit",
                        maxValue = MEMORY_TOOL_TIMELINE_MAX_LIMIT,
                        rawValue = limitToken,
                    ),
                )
            }
            parsedLimit.toInt()
        }
    return MemoryTimelineCommandParseResult.Success(
        MemoryTimelineCommandOptions(
            limit = limit,
            includeDeleted = includeDeleted,
        ),
    )
}

internal fun parseMemoryExportCommandOptions(rest: String): MemoryExportCommandParseResult {
    val tokens =
        rest
            .split(Regex("\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
    if (tokens.isEmpty()) {
        return MemoryExportCommandParseResult.Success(
            MemoryExportCommandOptions(
                limit = MEMORY_TOOL_EXPORT_DEFAULT_LIMIT,
                includeDeleted = false,
            ),
        )
    }
    val deletedTokens = setOf("all", "deleted", "trash", "with-deleted", "include-deleted")
    val includeDeleted = tokens.any { token -> token.lowercase().replace("_", "-") in deletedTokens }
    val limitTokens = tokens.filterNot { token -> token.lowercase().replace("_", "-") in deletedTokens }
    if (limitTokens.size > 1) {
        return MemoryExportCommandParseResult.Failure(
            invalidMemoryLimitResult(
                field = "limit",
                maxValue = MEMORY_TOOL_EXPORT_MAX_LIMIT,
                rawValue = rest,
            ),
        )
    }
    val limitToken = limitTokens.singleOrNull()
    val limit =
        if (limitToken == null) {
            MEMORY_TOOL_EXPORT_DEFAULT_LIMIT
        } else {
            val parsedLimit =
                limitToken.toLongOrNull()
                    ?: return MemoryExportCommandParseResult.Failure(
                        invalidMemoryLimitResult(
                            field = "limit",
                            maxValue = MEMORY_TOOL_EXPORT_MAX_LIMIT,
                            rawValue = limitToken,
                        ),
                    )
            if (parsedLimit !in 1L..MEMORY_TOOL_EXPORT_MAX_LIMIT.toLong()) {
                return MemoryExportCommandParseResult.Failure(
                    invalidMemoryLimitResult(
                        field = "limit",
                        maxValue = MEMORY_TOOL_EXPORT_MAX_LIMIT,
                        rawValue = limitToken,
                    ),
                )
            }
            parsedLimit.toInt()
        }
    return MemoryExportCommandParseResult.Success(
        MemoryExportCommandOptions(
            limit = limit,
            includeDeleted = includeDeleted,
        ),
    )
}

internal fun invalidMemoryLimitResult(
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
                put("received", rawValue.take(MEMORY_TOOL_MAX_LIMIT_PAYLOAD_CHARS))
            },
    )

internal fun JsonObjectBuilder.putAllowedMemorySourceTypes() {
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
internal const val MEMORY_TOOL_MAX_SOURCE_TYPE_PAYLOAD_CHARS = 80
internal const val MEMORY_TOOL_MAX_LIMIT_PAYLOAD_CHARS = 80
internal const val MEMORY_TOOL_DOCTOR_DEFAULT_LIMIT = 20
internal const val MEMORY_TOOL_DOCTOR_MAX_LIMIT = 50
internal const val MEMORY_TOOL_DOCTOR_TEXT_MAX_CHARS = 500
internal const val MEMORY_TOOL_HANDOFF_DEFAULT_LIMIT = 8
internal const val MEMORY_TOOL_HANDOFF_MAX_LIMIT = 20
internal const val MEMORY_TOOL_TIMELINE_DEFAULT_LIMIT = 20
internal const val MEMORY_TOOL_TIMELINE_MAX_LIMIT = 50
internal const val MEMORY_TOOL_EXPORT_FORMAT = "androidclaw.memory.export.v1"
internal const val MEMORY_TOOL_EXPORT_VERSION = 1
internal const val MEMORY_TOOL_EXPORT_DEFAULT_LIMIT = 50
internal const val MEMORY_TOOL_EXPORT_MAX_LIMIT = 50
internal const val MEMORY_TOOL_IMPORT_DEFAULT_LIMIT = 50
internal const val MEMORY_TOOL_IMPORT_MAX_LIMIT = 50
internal const val MEMORY_TOOL_SOURCE_SNIPPET_MAX_CHARS = 500
