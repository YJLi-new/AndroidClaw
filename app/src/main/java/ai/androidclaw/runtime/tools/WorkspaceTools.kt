package ai.androidclaw.runtime.tools

import ai.androidclaw.data.WorkspaceException
import ai.androidclaw.data.WorkspaceStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val WORKSPACE_READ_DEFAULT_CHARS = 12_000
private const val WORKSPACE_READ_MAX_CHARS = 20_000

internal fun workspaceToolEntries(workspaceStore: WorkspaceStore): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "workspace_list",
                    description = "List files in the assistant's private workspace (AGENTS.md, SOUL.md, USER.md, MEMORY.md, notes).",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                ),
        ) { _, _ ->
            val files = workspaceStore.list()
            toolSuccess(
                summary =
                    buildString {
                        append("${files.size} workspace file(s):")
                        files.forEach { file ->
                            append("\n- ")
                                .append(file.path)
                                .append(" (")
                                .append(file.sizeBytes)
                                .append(" bytes)")
                        }
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "workspace_read",
                    description = "Read a workspace text file. Use offset to page through long files.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("path", "Relative path, e.g. MEMORY.md or notes/today.md.", required = true),
                            intArg("offset", "Start character, default 0."),
                            intArg("maxChars", "Default 12000, max 20000."),
                        ),
                ),
        ) { _, arguments ->
            workspaceCall {
                val path = arguments.requireText("path", "workspace_read")
                val text = workspaceStore.read(path) ?: return@workspaceCall fileNotFound(path)
                val offset = arguments.boundedInt("offset", 0, 0, text.length)
                val maxChars = arguments.boundedInt("maxChars", WORKSPACE_READ_DEFAULT_CHARS, 1, WORKSPACE_READ_MAX_CHARS)
                val end = (offset + maxChars).coerceAtMost(text.length)
                toolSuccess(
                    summary =
                        if (end < text.length) {
                            "$path: characters $offset-$end of ${text.length}; read again with offset=$end for more."
                        } else {
                            "$path (${text.length} characters)."
                        },
                    payload = buildJsonObject { put("text", text.substring(offset, end)) },
                )
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "workspace_write",
                    description = "Create or overwrite a workspace file, or append to it. Max 256 KB per file.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("path", "Relative path.", required = true),
                            stringArg("content", "Full text to write.", required = true),
                            boolArg("append", "Append instead of overwrite."),
                        ),
                ),
        ) { _, arguments ->
            workspaceCall {
                val path = arguments.requireText("path", "workspace_write")
                val content = arguments.optionalRawText("content") ?: throw IllegalArgumentException("workspace_write requires content.")
                val append = arguments.optionalBoolean("append")
                val info = workspaceStore.write(path, content, append = append)
                toolSuccess(summary = "${if (append) "Appended to" else "Wrote"} ${info.path} (${info.sizeBytes} bytes).")
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "workspace_edit",
                    description = "Replace exact text in a workspace file. oldText must match once unless replaceAll is true.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("path", "Relative path.", required = true),
                            stringArg("oldText", "Exact text to find.", required = true),
                            stringArg("newText", "Replacement text (may be empty)."),
                            boolArg("replaceAll", "Replace every match."),
                        ),
                ),
        ) { _, arguments ->
            workspaceCall {
                val path = arguments.requireText("path", "workspace_edit")
                val oldText = arguments.optionalRawText("oldText") ?: throw IllegalArgumentException("workspace_edit requires oldText.")
                val newText = arguments.optionalRawText("newText").orEmpty()
                val current = workspaceStore.read(path) ?: return@workspaceCall fileNotFound(path)
                val matches = current.windowedCount(oldText)
                if (matches == 0) {
                    return@workspaceCall toolFailure("TEXT_NOT_FOUND", "oldText was not found in $path. Read the file first.")
                }
                val replaceAll = arguments.optionalBoolean("replaceAll")
                if (matches > 1 && !replaceAll) {
                    return@workspaceCall toolFailure(
                        "TEXT_NOT_UNIQUE",
                        "oldText matches $matches times in $path; add context or set replaceAll=true.",
                    )
                }
                val updated = if (replaceAll) current.replace(oldText, newText) else current.replaceFirst(oldText, newText)
                val info = workspaceStore.write(path, updated)
                toolSuccess(summary = "Edited ${info.path}: replaced ${if (replaceAll) matches else 1} occurrence(s).")
            }
        },
    )

private inline fun workspaceCall(block: () -> ToolExecutionResult): ToolExecutionResult =
    try {
        block()
    } catch (error: WorkspaceException) {
        toolFailure("INVALID_PATH", error.message ?: "Invalid workspace path.")
    }

private fun fileNotFound(path: String): ToolExecutionResult = toolFailure("FILE_NOT_FOUND", "Workspace file $path does not exist. Use workspace_list to see files.")

private fun String.windowedCount(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = indexOf(needle)
    while (index >= 0) {
        count++
        index = indexOf(needle, index + needle.length)
    }
    return count
}
