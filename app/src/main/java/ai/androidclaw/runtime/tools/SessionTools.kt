package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SESSIONS_LIST_DEFAULT_LIMIT = 20
private const val SESSIONS_LIST_MAX_LIMIT = 50
private const val SESSIONS_HISTORY_DEFAULT_LIMIT = 20
private const val SESSIONS_HISTORY_MAX_LIMIT = 50
private const val SESSIONS_HISTORY_MESSAGE_MAX_CHARS = 1_500
private const val SESSIONS_SEND_MAX_CHARS = 8_000

internal fun sessionToolEntries(
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "sessions_list",
                    aliases = listOf("sessions.list"),
                    description = "List chat sessions (newest activity first) with ids, titles and message counts.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            intArg("limit", "Max sessions, default 20, max 50."),
                            boolArg("includeArchived", "Include archived sessions."),
                        ),
                ),
        ) { context, arguments ->
            val limit = arguments.boundedInt("limit", SESSIONS_LIST_DEFAULT_LIMIT, 1, SESSIONS_LIST_MAX_LIMIT)
            val activity =
                sessionRepository.listSessionActivity(
                    limit = limit,
                    includeArchived = arguments.optionalBoolean("includeArchived"),
                )
            if (activity.isEmpty()) {
                return@Entry toolSuccess("No sessions yet.")
            }
            toolSuccess(
                summary =
                    buildString {
                        append("${activity.size} session(s):")
                        activity.forEach { item ->
                            val session = item.session
                            append("\n- ").append(session.title.clip(80))
                            if (session.isMain) append(" [main]")
                            if (session.archived) append(" [archived]")
                            if (session.id == context.sessionId) append(" [current]")
                            append(" id=").append(session.id)
                            append(", ${item.messageCount} messages, updated ${session.updatedAt}")
                        }
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "sessions_history",
                    aliases = listOf("sessions.history", "messages.recent"),
                    description = "Read recent messages of a session (default: current), oldest first.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("sessionId", "Session id, 'current' or 'main'. Default current."),
                            intArg("limit", "Max messages, default 20, max 50."),
                            boolArg("includeTools", "Include tool call/result rows."),
                        ),
                ),
        ) { context, arguments ->
            val session =
                resolveSession(arguments.optionalText("sessionId"), context, sessionRepository)
                    ?: return@Entry sessionNotFound(arguments)
            val limit = arguments.boundedInt("limit", SESSIONS_HISTORY_DEFAULT_LIMIT, 1, SESSIONS_HISTORY_MAX_LIMIT)
            val includeTools = arguments.optionalBoolean("includeTools")
            val messages =
                messageRepository
                    .getRecentMessages(session.id, limit = if (includeTools) limit else limit * 3)
                    .asReversed()
                    .filter { message ->
                        includeTools || (message.role != MessageRole.ToolCall && message.role != MessageRole.ToolResult)
                    }.takeLast(limit)
            toolSuccess(
                summary = "${messages.size} message(s) from \"${session.title.clip(80)}\" (id=${session.id}).",
                payload =
                    buildJsonObject {
                        put(
                            "messages",
                            buildJsonArray {
                                messages.forEach { message ->
                                    add(
                                        buildJsonObject {
                                            put("role", message.role.name.lowercase())
                                            put("at", message.createdAt.toString())
                                            put("text", message.content.clip(SESSIONS_HISTORY_MESSAGE_MAX_CHARS))
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "sessions_create",
                    aliases = listOf("sessions.create"),
                    description = "Create a new chat session and return its id.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments = listOf(stringArg("title", "Session title.", required = true)),
                ),
        ) { _, arguments ->
            val title = arguments.requireText("title", "sessions_create")
            val session = sessionRepository.createSession(title = title)
            toolSuccess(
                summary = "Created session \"${session.title}\" (id=${session.id}).",
                payload = sessionPayload(session),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "sessions_rename",
                    aliases = listOf("sessions.rename"),
                    description = "Rename a session (default: current).",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("title", "New title.", required = true),
                            stringArg("sessionId", "Session id, 'current' or 'main'. Default current."),
                        ),
                ),
        ) { context, arguments ->
            val title = arguments.requireText("title", "sessions_rename")
            val session =
                resolveSession(arguments.optionalText("sessionId"), context, sessionRepository)
                    ?: return@Entry sessionNotFound(arguments)
            sessionRepository.updateTitle(session.id, title)
            val renamed = sessionRepository.getSession(session.id) ?: session
            toolSuccess(
                summary = "Renamed session to \"${renamed.title}\".",
                payload = sessionPayload(renamed),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "sessions_send",
                    aliases = listOf("sessions.send"),
                    description = "Post a note into another session's transcript. It does not start a turn there.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                    arguments =
                        listOf(
                            stringArg("sessionId", "Target session id or 'main'.", required = true),
                            stringArg("text", "Text to post.", required = true),
                        ),
                ),
        ) { context, arguments ->
            val text = arguments.requireText("text", "sessions_send").clip(SESSIONS_SEND_MAX_CHARS)
            val target =
                resolveSession(arguments.optionalText("sessionId"), context, sessionRepository)
                    ?: return@Entry sessionNotFound(arguments)
            if (target.id == context.sessionId) {
                return@Entry toolFailure("SAME_SESSION", "sessions_send posts into another session; reply here directly instead.")
            }
            if (target.archived) {
                return@Entry toolFailure("SESSION_ARCHIVED", "Session \"${target.title}\" is archived.")
            }
            val sourceTitle =
                context.sessionId
                    ?.let { sessionId -> sessionRepository.getSession(sessionId)?.title }
                    ?: "another session"
            val message =
                messageRepository.addMessage(
                    sessionId = target.id,
                    role = MessageRole.Assistant,
                    content = "[From $sourceTitle] $text",
                    taskRunId = context.taskRunId,
                )
            toolSuccess(
                summary = "Posted a note into \"${target.title}\".",
                payload =
                    buildJsonObject {
                        put("sessionId", target.id)
                        put("messageId", message.id)
                    },
            )
        },
    )

/** Resolves `current` (default), `main`, or an explicit session id. */
internal suspend fun resolveSession(
    rawId: String?,
    context: ToolExecutionContext,
    sessionRepository: SessionRepository,
): Session? =
    when (rawId?.trim()?.takeIf(String::isNotEmpty)?.lowercase()) {
        null, "current" -> context.sessionId?.let { sessionRepository.getSession(it) }
        "main" -> sessionRepository.getOrCreateMainSession()
        else -> sessionRepository.getSession(rawId.trim())
    }

private fun sessionNotFound(arguments: JsonObject): ToolExecutionResult {
    val requested = arguments.optionalText("sessionId") ?: "current"
    return toolFailure(
        errorCode = "SESSION_NOT_FOUND",
        summary = "Session $requested was not found. Use sessions_list to see valid ids.",
    )
}

private fun sessionPayload(session: Session): JsonObject =
    buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("isMain", session.isMain)
    }
