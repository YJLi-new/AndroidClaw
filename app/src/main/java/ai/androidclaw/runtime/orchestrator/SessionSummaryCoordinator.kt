package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class SessionSummaryRefreshResult(
    val refreshed: Boolean,
    val skippedReason: String? = null,
    val messageCount: Int = 0,
    val summaryText: String? = null,
)

class SessionSummaryCoordinator(
    private val applicationScope: CoroutineScope,
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val sessionLaneCoordinator: SessionLaneCoordinator,
    private val minMessageCount: Int = DEFAULT_MIN_MESSAGE_COUNT,
    private val refreshIntervalMessages: Int = DEFAULT_REFRESH_INTERVAL_MESSAGES,
    private val summarySourceMessageLimit: Int = DEFAULT_SUMMARY_SOURCE_MESSAGE_LIMIT,
    private val allowFakeProviderForTesting: Boolean = false,
) {
    private val inFlightSessions = ConcurrentHashMap.newKeySet<String>()
    private val lastSummarizedMessageCounts = ConcurrentHashMap<String, Int>()

    fun onTurnCompleted(sessionId: String) {
        if (!inFlightSessions.add(sessionId)) {
            return
        }
        applicationScope.launch {
            try {
                sessionLaneCoordinator.withLane(sessionId) {
                    maybeRefreshSummary(sessionId)
                }
            } finally {
                inFlightSessions.remove(sessionId)
            }
        }
    }

    suspend fun maybeRefreshSummary(sessionId: String): SessionSummaryRefreshResult {
        val session =
            sessionRepository.getSession(sessionId)
                ?: return SessionSummaryRefreshResult(
                    refreshed = false,
                    skippedReason = "missing_session",
                )
        val providerSettings = settingsDataStore.settings.first()
        if (providerSettings.providerType == ProviderType.Fake && !allowFakeProviderForTesting) {
            return SessionSummaryRefreshResult(
                refreshed = false,
                skippedReason = "fake_provider_disabled",
            )
        }

        val messageCount = messageRepository.getMessageCount(sessionId)
        if (messageCount < minMessageCount) {
            return SessionSummaryRefreshResult(
                refreshed = false,
                skippedReason = "below_threshold",
                messageCount = messageCount,
            )
        }

        val lastSummarizedCount =
            lastSummarizedMessageCounts[sessionId]
                ?: if (session.summaryText.isNullOrBlank()) 0 else messageCount
        if (!session.summaryText.isNullOrBlank() && messageCount - lastSummarizedCount < refreshIntervalMessages) {
            return SessionSummaryRefreshResult(
                refreshed = false,
                skippedReason = "not_enough_new_messages",
                messageCount = messageCount,
                summaryText = session.summaryText,
            )
        }

        val sourceMessages =
            messageRepository
                .getRecentMessages(
                    sessionId = sessionId,
                    limit = summarySourceMessageLimit,
                ).asReversed()
        if (sourceMessages.isEmpty()) {
            return SessionSummaryRefreshResult(
                refreshed = false,
                skippedReason = "no_messages",
                messageCount = messageCount,
                summaryText = session.summaryText,
            )
        }

        val response =
            withContext(Dispatchers.IO) {
                providerRegistry.require(providerSettings.providerType).generate(
                    ModelRequest(
                        sessionId = sessionId,
                        requestId = "session-summary-$sessionId-$messageCount",
                        messageHistory =
                            listOf(
                                ModelMessage(
                                    role = ModelMessageRole.User,
                                    content =
                                        buildSummaryPrompt(
                                            existingSummary = session.summaryText,
                                            sourceMessages = sourceMessages,
                                        ),
                                ),
                            ),
                        systemPrompt = SUMMARY_SYSTEM_PROMPT,
                        enabledSkills = emptyList(),
                        toolDescriptors = emptyList(),
                        runMode = ModelRunMode.Scheduled,
                    ),
                )
            }
        val newSummary =
            response.text.trim().takeIf { it.isNotBlank() }
                ?: return SessionSummaryRefreshResult(
                    refreshed = false,
                    skippedReason = "empty_summary",
                    messageCount = messageCount,
                    summaryText = session.summaryText,
                )

        sessionRepository.updateSummary(sessionId, newSummary)
        lastSummarizedMessageCounts[sessionId] = messageCount
        return SessionSummaryRefreshResult(
            refreshed = true,
            messageCount = messageCount,
            summaryText = newSummary,
        )
    }

    companion object {
        private const val DEFAULT_MIN_MESSAGE_COUNT = 24
        private const val DEFAULT_REFRESH_INTERVAL_MESSAGES = 12
        private const val DEFAULT_SUMMARY_SOURCE_MESSAGE_LIMIT = 32

        private val SUMMARY_SYSTEM_PROMPT =
            """
            You maintain concise cumulative summaries for AndroidClaw chat sessions.
            Return only the updated session summary in plain text.
            Keep it short, factual, and cumulative.
            Preserve durable facts, user preferences, decisions, and unresolved tasks.
            Do not include hidden reasoning or chain-of-thought.
            """.trimIndent()
    }
}

private fun buildSummaryPrompt(
    existingSummary: String?,
    sourceMessages: List<ChatMessage>,
): String =
    buildString {
        if (!existingSummary.isNullOrBlank()) {
            appendLine("Existing summary:")
            appendLine(existingSummary.trim())
            appendLine()
            appendLine("Update it using the new transcript below.")
        } else {
            appendLine("Create a first cumulative summary for this session.")
        }
        appendLine()
        appendLine("Recent transcript:")
        sourceMessages.forEach { message ->
            appendLine("${message.summaryRoleLabel()}: ${message.content.trim()}")
        }
    }.trim()

private fun ChatMessage.summaryRoleLabel(): String =
    when (role) {
        MessageRole.User -> "User"
        MessageRole.Assistant -> "Assistant"
        MessageRole.System -> "System"
        MessageRole.ToolCall -> "Tool request"
        MessageRole.ToolResult -> "Tool result"
    }
