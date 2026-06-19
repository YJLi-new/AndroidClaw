package ai.androidclaw.feature.chat

import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import ai.androidclaw.runtime.tools.redactToolArguments
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant

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

data class ChatExportFileRequest(
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val format: ChatExportFormat,
    val exportedAt: Instant,
)

data class ChatExportWriteResult(
    val fileName: String,
    val messageCount: Int,
    val omittedMessageCount: Int,
    val truncatedBySizeLimit: Boolean,
)

internal const val CHAT_SHARE_TEXT_MAX_CHARS = 100_000
internal const val CHAT_SHARE_TEXT_TRUNCATED_NOTICE =
    "\n\n[Share text truncated by AndroidClaw. Use Export or Share file for the full transcript.]"
internal const val CHAT_FILE_EXPORT_MAX_CHARS = 5_000_000
internal const val CHAT_FILE_EXPORT_TRUNCATED_NOTICE =
    "\n\n[File export truncated by AndroidClaw because it reached the configured size limit.]"

sealed interface ChatExternalAction {
    data class ExportDocument(
        val payload: ChatExportFileRequest,
    ) : ChatExternalAction

    data class ShareText(
        val subject: String,
        val text: String,
    ) : ChatExternalAction

    data class ShareFile(
        val payload: ChatExportFileRequest,
    ) : ChatExternalAction
}

object ChatExportFormatter {
    private val exportJson =
        Json {
            prettyPrint = true
        }

    fun buildExportPayload(
        session: Session,
        messages: List<ChatMessage>,
        format: ChatExportFormat,
        exportedAt: Instant = Instant.now(),
        maxChars: Int = CHAT_FILE_EXPORT_MAX_CHARS,
    ): ChatExportPayload {
        val request = buildFileRequest(session = session, format = format, exportedAt = exportedAt)
        val content =
            buildString {
                val writer =
                    openStreamingWriter(
                        session = session,
                        format = format,
                        exportedAt = exportedAt,
                        appendable = this,
                        maxChars = maxChars,
                    )
                var emitted = 0
                for (message in messages) {
                    if (writer.writeMessage(message)) {
                        emitted += 1
                    } else {
                        break
                    }
                }
                writer.close(omittedMessageCount = messages.size - emitted)
            }
        return ChatExportPayload(
            fileName = request.fileName,
            mimeType = request.mimeType,
            content = content,
        )
    }

    fun buildFileRequest(
        session: Session,
        format: ChatExportFormat,
        exportedAt: Instant = Instant.now(),
    ): ChatExportFileRequest =
        ChatExportFileRequest(
            sessionId = session.id,
            fileName = "${buildFileStem(session, exportedAt)}.${format.extension}",
            mimeType = format.mimeType,
            format = format,
            exportedAt = exportedAt,
        )

    fun openStreamingWriter(
        session: Session,
        format: ChatExportFormat,
        exportedAt: Instant,
        appendable: Appendable,
        maxChars: Int = CHAT_FILE_EXPORT_MAX_CHARS,
        truncatedNotice: String = CHAT_FILE_EXPORT_TRUNCATED_NOTICE,
        includeOmittedCountNotice: Boolean = true,
    ): ChatStreamingExportWriter =
        ChatStreamingExportWriter(
            session = session,
            format = format,
            exportedAt = exportedAt,
            appendable = appendable,
            maxChars = maxChars,
            truncatedNotice = truncatedNotice,
            includeOmittedCountNotice = includeOmittedCountNotice,
        )

    fun buildShareText(
        session: Session,
        messages: List<ChatMessage>,
        exportedAt: Instant = Instant.now(),
    ): String =
        buildTextExport(session, messages, exportedAt)
            .toBoundedShareText()

    private fun buildTextExport(
        session: Session,
        messages: List<ChatMessage>,
        exportedAt: Instant,
    ): String =
        buildString {
            append(buildTextHeader(session, exportedAt))
            messages.forEachIndexed { index, message ->
                append(
                    buildTextMessage(
                        message = message,
                        includeLeadingBlankLine = index > 0,
                    ),
                )
            }
        }.trimEnd()

    private fun buildTextHeader(
        session: Session,
        exportedAt: Instant,
    ): String =
        buildString {
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
        }

    private fun buildTextMessage(
        message: ChatMessage,
        includeLeadingBlankLine: Boolean,
    ): String =
        buildString {
            if (includeLeadingBlankLine) appendLine()
            appendLine("[${message.createdAt}] ${message.role.displayName()}")
            appendLine(message.exportContent().trimEnd())
        }

    private fun buildMarkdownHeader(
        session: Session,
        exportedAt: Instant,
    ): String =
        buildString {
            appendLine("# ${escapeMarkdown(session.title)}")
            appendLine()
            appendLine("- Session ID: `${session.id}`")
            appendLine("- Main session: `${session.isMain}`")
            appendLine("- Archived: `${session.archived}`")
            appendLine("- Created: `${session.createdAt}`")
            appendLine("- Updated: `${session.updatedAt}`")
            appendLine("- Exported: `$exportedAt`")
            session.summaryText?.takeIf { it.isNotBlank() }?.let { summary ->
                appendLine()
                appendLine("## Summary")
                appendLine()
                appendLine(summary.trim())
            }
            appendLine()
            appendLine("## Transcript")
        }

    private fun buildMarkdownMessage(message: ChatMessage): String =
        buildString {
            appendLine()
            appendLine("### ${message.role.displayName()} · `${message.createdAt}`")
            message.toolCallId?.let { appendLine("- Tool call ID: `$it`") }
            message.providerMeta?.takeIf { it.isNotBlank() }?.let { appendLine("- Provider meta: `${escapeMarkdown(it)}`") }
            appendLine()
            appendLine("```text")
            appendLine(message.exportContent().trimEnd())
            appendLine("```")
        }

    private fun buildJsonHeader(
        session: Session,
        exportedAt: Instant,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"exportedAt\": ${exportJson.encodeToString(exportedAt.toString())},")
            appendLine("  \"app\": \"AndroidClaw\",")
            appendLine("  \"session\": ${exportJson.encodeToString(session.toExportedMetadata()).prependIndent("  ").trimStart()},")
            appendLine("  \"messages\": [")
        }

    private fun buildJsonMessage(
        message: ChatMessage,
        includeComma: Boolean,
    ): String =
        buildString {
            if (includeComma) appendLine(",")
            append(exportJson.encodeToString(message.toExportedMessage()).prependIndent("    "))
        }

    private fun buildJsonFooter(
        omittedMessageCount: Int,
        maxChars: Int,
    ): String =
        buildString {
            appendLine()
            appendLine("  ],")
            appendLine("  \"messagesOmittedDueToSizeLimit\": $omittedMessageCount,")
            appendLine("  \"sizeLimitChars\": $maxChars")
            appendLine("}")
        }

    private fun Session.toExportedMetadata(): ExportedSessionMetadata =
        ExportedSessionMetadata(
            id = id,
            title = title,
            isMain = isMain,
            archived = archived,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            summaryText = summaryText,
        )

    private fun ChatMessage.toExportedMessage(): ExportedMessage =
        ExportedMessage(
            id = id,
            role = role.storageName(),
            content = exportContent(),
            createdAt = createdAt.toString(),
            providerMeta = providerMeta,
            toolCallId = toolCallId,
            taskRunId = taskRunId,
        )

    private fun buildFileStem(
        session: Session,
        exportedAt: Instant,
    ): String {
        val sessionPart =
            session.title
                .trim()
                .ifBlank { "session" }
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-')
                .ifBlank { "session" }
                .take(48)
        val timestampPart =
            exportedAt
                .toString()
                .replace(':', '-')
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
                .trim('-')
        return "${sessionPart}_$timestampPart"
    }

    private fun ChatMessage.exportContent(): String =
        if (role == MessageRole.ToolCall) {
            content.redactToolCallExportContent()
        } else {
            content
        }

    private fun String.redactToolCallExportContent(): String {
        val jsonStart = indexOf('{').takeIf { index -> index >= 0 } ?: return this
        val prefix = take(jsonStart)
        val jsonText = drop(jsonStart)
        val arguments =
            runCatching { exportJson.parseToJsonElement(jsonText).jsonObject }
                .getOrNull()
                ?: return this
        val redactedArguments =
            redactToolArguments(
                arguments = arguments,
                sensitiveArgumentNames = emptySet(),
            ).arguments
        return prefix + redactedArguments.toString()
    }

    private fun escapeMarkdown(value: String): String = value.replace("`", "\\`")

    private fun String.toBoundedShareText(): String {
        if (length <= CHAT_SHARE_TEXT_MAX_CHARS) {
            return this
        }
        val prefixLength =
            (CHAT_SHARE_TEXT_MAX_CHARS - CHAT_SHARE_TEXT_TRUNCATED_NOTICE.length)
                .coerceAtLeast(0)
        return take(prefixLength).trimEnd() + CHAT_SHARE_TEXT_TRUNCATED_NOTICE
    }

    class ChatStreamingExportWriter internal constructor(
        private val session: Session,
        private val format: ChatExportFormat,
        private val exportedAt: Instant,
        private val appendable: Appendable,
        private val maxChars: Int,
        private val truncatedNotice: String,
        private val includeOmittedCountNotice: Boolean,
    ) {
        private var closed = false
        private var writtenChars = 0
        private var emittedMessageCount = 0
        private var truncated = false

        init {
            appendRequired(
                when (format) {
                    ChatExportFormat.Text -> buildTextHeader(session, exportedAt)
                    ChatExportFormat.Markdown -> buildMarkdownHeader(session, exportedAt)
                    ChatExportFormat.Json -> buildJsonHeader(session, exportedAt)
                },
            )
        }

        fun writeMessage(message: ChatMessage): Boolean {
            check(!closed) { "Export writer is already closed." }
            val messageText =
                when (format) {
                    ChatExportFormat.Text ->
                        buildTextMessage(
                            message = message,
                            includeLeadingBlankLine = emittedMessageCount > 0,
                        )
                    ChatExportFormat.Markdown -> buildMarkdownMessage(message)
                    ChatExportFormat.Json ->
                        buildJsonMessage(
                            message = message,
                            includeComma = emittedMessageCount > 0,
                        )
                }
            if (!appendOptional(messageText)) {
                truncated = true
                return false
            }
            emittedMessageCount += 1
            return true
        }

        fun close(omittedMessageCount: Int): ChatExportWriteResult {
            if (closed) {
                return toResult(omittedMessageCount)
            }
            closed = true
            val boundedOmitted = omittedMessageCount.coerceAtLeast(0)
            when (format) {
                ChatExportFormat.Text,
                ChatExportFormat.Markdown,
                -> {
                    if (boundedOmitted > 0 || truncated) {
                        appendRequired(truncatedNotice)
                        if (includeOmittedCountNotice) {
                            appendRequired("\nMessages omitted: $boundedOmitted\n")
                        }
                    }
                }
                ChatExportFormat.Json -> appendRequired(buildJsonFooter(boundedOmitted, maxChars))
            }
            return toResult(boundedOmitted)
        }

        private fun toResult(omittedMessageCount: Int): ChatExportWriteResult =
            ChatExportWriteResult(
                fileName = buildFileRequest(session, format, exportedAt).fileName,
                messageCount = emittedMessageCount,
                omittedMessageCount = omittedMessageCount.coerceAtLeast(0),
                truncatedBySizeLimit = truncated || omittedMessageCount > 0,
            )

        private fun appendOptional(value: String): Boolean {
            if (writtenChars + value.length > maxChars) {
                return false
            }
            appendRequired(value)
            return true
        }

        private fun appendRequired(value: String) {
            appendable.append(value)
            writtenChars += value.length
        }
    }
}

private fun MessageRole.displayName(): String =
    when (this) {
        MessageRole.User -> "User"
        MessageRole.Assistant -> "Assistant"
        MessageRole.ToolCall -> "Tool call"
        MessageRole.ToolResult -> "Tool result"
        MessageRole.System -> "System"
    }

private fun MessageRole.storageName(): String =
    when (this) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.ToolCall -> "tool_call"
        MessageRole.ToolResult -> "tool_result"
        MessageRole.System -> "system"
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
