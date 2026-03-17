package ai.androidclaw.feature.chat

import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ChatExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    Text(
        label = "TXT",
        extension = "txt",
        mimeType = "text/plain",
    ),
    Markdown(
        label = "Markdown",
        extension = "md",
        mimeType = "text/markdown",
    ),
    Json(
        label = "JSON",
        extension = "json",
        mimeType = "application/json",
    ),
}

data class ChatExportPayload(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

sealed interface ChatExternalAction {
    data class ExportDocument(val payload: ChatExportPayload) : ChatExternalAction

    data class ShareText(
        val subject: String,
        val text: String,
    ) : ChatExternalAction

    data class ShareFile(val payload: ChatExportPayload) : ChatExternalAction
}

object ChatExportFormatter {
    private val exportJson = Json {
        prettyPrint = true
    }

    fun buildExportPayload(
        session: Session,
        messages: List<ChatMessage>,
        format: ChatExportFormat,
        exportedAt: Instant = Instant.now(),
    ): ChatExportPayload {
        val fileStem = buildFileStem(session, exportedAt)
        val content = when (format) {
            ChatExportFormat.Text -> buildTextExport(session, messages, exportedAt)
            ChatExportFormat.Markdown -> buildMarkdownExport(session, messages, exportedAt)
            ChatExportFormat.Json -> buildJsonExport(session, messages, exportedAt)
        }
        return ChatExportPayload(
            fileName = "$fileStem.${format.extension}",
            mimeType = format.mimeType,
            content = content,
        )
    }

    private fun buildTextExport(
        session: Session,
        messages: List<ChatMessage>,
        exportedAt: Instant,
    ): String {
        return buildString {
            appendLine("AndroidClaw session export")
            appendLine("Title: ${session.title}")
            appendLine("Session ID: ${session.id}")
            appendLine("Main session: ${session.isMain}")
            appendLine("Archived: ${session.archived}")
            appendLine("Created: ${session.createdAt}")
            appendLine("Updated: ${session.updatedAt}")
            appendLine("Exported: $exportedAt")
            session.summaryText?.takeIf { it.isNotBlank() }?.let { summary ->
                appendLine("Summary: ${summary.trim()}")
            }
            appendLine()
            messages.forEachIndexed { index, message ->
                if (index > 0) appendLine()
                appendLine("[${message.createdAt}] ${message.role.displayName()}")
                appendLine(message.content.trimEnd())
            }
        }.trimEnd()
    }

    private fun buildMarkdownExport(
        session: Session,
        messages: List<ChatMessage>,
        exportedAt: Instant,
    ): String {
        return buildString {
            appendLine("# ${escapeMarkdown(session.title)}")
            appendLine()
            appendLine("- Session ID: `${session.id}`")
            appendLine("- Main session: `${session.isMain}`")
            appendLine("- Archived: `${session.archived}`")
            appendLine("- Created: `${session.createdAt}`")
            appendLine("- Updated: `${session.updatedAt}`")
            appendLine("- Exported: `${exportedAt}`")
            session.summaryText?.takeIf { it.isNotBlank() }?.let { summary ->
                appendLine()
                appendLine("## Summary")
                appendLine()
                appendLine(summary.trim())
            }
            appendLine()
            appendLine("## Transcript")
            messages.forEach { message ->
                appendLine()
                appendLine("### ${message.role.displayName()} · `${message.createdAt}`")
                message.toolCallId?.let { appendLine("- Tool call ID: `$it`") }
                message.providerMeta?.takeIf { it.isNotBlank() }?.let { appendLine("- Provider meta: `${escapeMarkdown(it)}`") }
                appendLine()
                appendLine("```text")
                appendLine(message.content.trimEnd())
                appendLine("```")
            }
        }.trimEnd()
    }

    private fun buildJsonExport(
        session: Session,
        messages: List<ChatMessage>,
        exportedAt: Instant,
    ): String {
        return exportJson.encodeToString(
            ExportedSessionDocument(
                exportedAt = exportedAt.toString(),
                app = "AndroidClaw",
                session = ExportedSessionMetadata(
                    id = session.id,
                    title = session.title,
                    isMain = session.isMain,
                    archived = session.archived,
                    createdAt = session.createdAt.toString(),
                    updatedAt = session.updatedAt.toString(),
                    summaryText = session.summaryText,
                ),
                messages = messages.map { message ->
                    ExportedMessage(
                        id = message.id,
                        role = message.role.storageName(),
                        content = message.content,
                        createdAt = message.createdAt.toString(),
                        providerMeta = message.providerMeta,
                        toolCallId = message.toolCallId,
                        taskRunId = message.taskRunId,
                    )
                },
            ),
        )
    }

    private fun buildFileStem(
        session: Session,
        exportedAt: Instant,
    ): String {
        val sessionPart = session.title
            .trim()
            .ifBlank { "session" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "session" }
            .take(48)
        val timestampPart = exportedAt.toString()
            .replace(':', '-')
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
        return "${sessionPart}_$timestampPart"
    }

    private fun escapeMarkdown(value: String): String {
        return value.replace("`", "\\`")
    }
}

private fun MessageRole.displayName(): String {
    return when (this) {
        MessageRole.User -> "User"
        MessageRole.Assistant -> "Assistant"
        MessageRole.ToolCall -> "Tool call"
        MessageRole.ToolResult -> "Tool result"
        MessageRole.System -> "System"
    }
}

private fun MessageRole.storageName(): String {
    return when (this) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.ToolCall -> "tool_call"
        MessageRole.ToolResult -> "tool_result"
        MessageRole.System -> "system"
    }
}

@Serializable
private data class ExportedSessionDocument(
    val exportedAt: String,
    val app: String,
    val session: ExportedSessionMetadata,
    val messages: List<ExportedMessage>,
)

@Serializable
private data class ExportedSessionMetadata(
    val id: String,
    val title: String,
    val isMain: Boolean,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val summaryText: String? = null,
)

@Serializable
private data class ExportedMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val providerMeta: String? = null,
    val toolCallId: String? = null,
    val taskRunId: String? = null,
)
