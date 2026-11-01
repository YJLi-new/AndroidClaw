package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.app.ChatDependencies
import ai.androidclaw.app.viewModelFactory
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.attachments.AttachmentImporter
import ai.androidclaw.data.attachments.MAX_ATTACHMENTS_PER_MESSAGE
import ai.androidclaw.data.attachments.StagedAttachment
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.MessageAttachment
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MESSAGE_CONTENT_MAX_CHARS
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SQLITE_LIKE_SEARCH_QUERY_MAX_CHARS
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.AgentTurnEvent
import ai.androidclaw.runtime.orchestrator.AgentTurnFailureKind
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import ai.androidclaw.runtime.providers.parseProviderMessageMeta
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.voice.SpeechInput
import ai.androidclaw.runtime.voice.SpeechOutput
import ai.androidclaw.ui.text.UiText
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

enum class ChatRole {
    User,
    Assistant,
    ToolCall,
    ToolResult,
    System,
}

data class ChatMessageUi(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Instant,
    val toolCallId: String? = null,
    /** Searched queries and web sources of an assistant reply (ChatSources.kt). */
    val webSearch: ChatWebSearchUi? = null,
    val attachments: List<ChatAttachmentUi> = emptyList(),
)

/** One row of the transcript. Tool requests and their results render as one card. */
sealed interface ChatItemUi {
    val key: String

    data class Message(
        val message: ChatMessageUi,
    ) : ChatItemUi {
        override val key: String
            get() = message.id
    }

    data class Tool(
        override val key: String,
        val messageIds: List<String>,
        val toolName: String,
        val arguments: String,
        val result: String?,
        val createdAt: Instant,
        /** Files linked to the result row (images from image_generate). */
        val attachments: List<ChatAttachmentUi> = emptyList(),
    ) : ChatItemUi
}

data class ChatSessionUi(
    val id: String,
    val title: String,
    val isSelected: Boolean,
    val isMain: Boolean,
    val updatedAt: Instant? = null,
)

enum class ChatSearchMatchType {
    Session,
    Message,
}

data class ChatSearchResultUi(
    val sessionId: String,
    val sessionTitle: String,
    val preview: String,
    val matchType: ChatSearchMatchType,
    val messageId: String? = null,
)

data class SlashCommandUi(
    val command: String,
    val description: String,
)

data class ChatUsageUi(
    val trackedReplies: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)

data class ChatUiState(
    val currentSessionId: String = "",
    val sessionTitle: String = "",
    val isMainSession: Boolean = false,
    val sessionSummary: String? = null,
    val sessionUsage: ChatUsageUi? = null,
    val compactedHiddenMessageCount: Int = 0,
    val showCompactedHistory: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<ChatSearchResultUi> = emptyList(),
    val searchPerformed: Boolean = false,
    val highlightedMessageId: String? = null,
    val draft: String = "",
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false,
    val errorMessage: UiText? = null,
    val noticeMessage: UiText? = null,
    val slashCommands: List<SlashCommandUi> = emptyList(),
    val sessions: List<ChatSessionUi> = emptyList(),
    val archivedSessions: List<ChatSessionUi> = emptyList(),
    val showArchivedSessions: Boolean = false,
    val messages: List<ChatMessageUi> = emptyList(),
    val items: List<ChatItemUi> = emptyList(),
    val earlierMessageCount: Int = 0,
    val canArchiveCurrentSession: Boolean = false,
    val canCompactCurrentSession: Boolean = false,
    val streamingAssistantText: String = "",
    val canRetryLastFailedTurn: Boolean = false,
    val activeTurnStage: UiText? = null,
    val isFakeProvider: Boolean = false,
    val stagedAttachments: List<ChatAttachmentUi> = emptyList(),
    val isImportingAttachments: Boolean = false,
    val imagesEnabled: Boolean = false,
) {
    val hasContent: Boolean
        get() = messages.isNotEmpty() || compactedHiddenMessageCount > 0
}

internal const val CHAT_STREAMING_ASSISTANT_TEXT_MAX_CHARS = 20_000
internal const val CHAT_STREAMING_ASSISTANT_TEXT_TRUNCATED_NOTICE =
    "\n\n[Live response preview truncated by AndroidClaw. The saved assistant message may contain more text.]"
internal const val CHAT_DRAFT_MAX_CHARS = MESSAGE_CONTENT_MAX_CHARS
internal const val CHAT_SEARCH_QUERY_MAX_CHARS = SQLITE_LIKE_SEARCH_QUERY_MAX_CHARS
internal const val CHAT_UI_MESSAGE_MAX_CHARS = 1_000
internal val CHAT_DRAFT_TRUNCATED_NOTICE: UiText = UiText.res(R.string.chat_notice_draft_truncated)
internal val CHAT_SEARCH_QUERY_TRUNCATED_NOTICE: UiText = UiText.res(R.string.chat_notice_search_truncated)

/** Transcript rows rendered at once; older rows load on demand. */
internal const val CHAT_MESSAGE_WINDOW = 150

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val eventLogRepository: EventLogRepository,
    private val agentRunner: AgentRunner,
    private val skillManager: SkillManager,
    private val settingsDataStore: SettingsDataStore,
    private val attachmentRepository: AttachmentRepository? = null,
    private val attachmentImporter: AttachmentImporter? = null,
    speechInput: SpeechInput? = null,
    speechOutputFactory: (() -> SpeechOutput)? = null,
) : ViewModel() {
    private val uiSharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
    private val draft = MutableStateFlow("")

    /**
     * Composer text as snapshot state so the TextField reads its own edits
     * synchronously; routing it through the combined state flow dropped
     * characters during fast typing.
     */
    var composerText by mutableStateOf("")
        private set
    private val isRunning = MutableStateFlow(false)
    private val isCancelling = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<UiText?>(null)
    private val noticeMessage = MutableStateFlow<UiText?>(null)
    private val slashCommands = MutableStateFlow<List<SlashCommandUi>>(emptyList())
    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow<List<ChatSearchResultUi>>(emptyList())
    private val searchPerformed = MutableStateFlow(false)
    private val highlightedMessageId = MutableStateFlow<String?>(null)
    private val showCompactedHistory = MutableStateFlow(false)
    private val showArchivedSessions = MutableStateFlow(false)
    private val messageWindow = MutableStateFlow(CHAT_MESSAGE_WINDOW)
    private val externalActions = MutableSharedFlow<ChatExternalAction>(extraBufferCapacity = 4)
    private val mutableCurrentSessionId = MutableStateFlow("")
    private val streamingAssistantText = MutableStateFlow("")
    private val canRetryLastFailedTurn = MutableStateFlow(false)
    private val activeTurnStage = MutableStateFlow<UiText?>(null)
    private val stagedAttachments = MutableStateFlow<List<StagedAttachment>>(emptyList())
    private val importingAttachmentCount = MutableStateFlow(0)
    val currentSessionId: StateFlow<String> = mutableCurrentSessionId.asStateFlow()
    val actions = externalActions.asSharedFlow()
    val voice =
        ChatVoiceController(
            scope = viewModelScope,
            voiceSettings = settingsDataStore.voiceSettings,
            saveVoiceReplies = settingsDataStore::setVoiceReplies,
            speechInput = speechInput,
            speechOutputFactory = speechOutputFactory,
            onNotice = { noticeMessage.value = it },
        )

    private var activeTurnJob: Job? = null
    private var cancelRequested: Boolean = false
    private var lastFailedUserMessageText: String? = null
    private var lastFailedSessionId: String? = null

    private val sessionsFlow: Flow<List<Session>> =
        sessionRepository
            .observeSessions()
            .onEach(::keepCurrentSessionValid)
            .shareIn(viewModelScope, uiSharingStarted, replay = 1)

    private val archivedSessionsFlow: Flow<List<Session>> =
        showArchivedSessions.flatMapLatest { show ->
            if (show) sessionRepository.observeArchivedSessions() else flowOf(emptyList())
        }

    // One Room observation feeds both the transcript and the usage summary.
    private val sessionMessagesFlow: Flow<List<ChatMessage>> =
        mutableCurrentSessionId
            .flatMapLatest { sessionId ->
                if (sessionId.isBlank()) {
                    flowOf(emptyList())
                } else {
                    messageRepository.observeMessages(sessionId)
                }
            }.shareIn(viewModelScope, uiSharingStarted, replay = 1)

    private val baseChromeFlow =
        combine(draft, isRunning, isCancelling, errorMessage, noticeMessage) { draftValue, running, cancelling, error, notice ->
            BaseChatChrome(draftValue, running, cancelling, error, notice)
        }

    private val turnChromeFlow =
        combine(streamingAssistantText, canRetryLastFailedTurn, activeTurnStage, slashCommands) { text, canRetry, stage, commands ->
            TurnChrome(text, canRetry, stage, commands)
        }

    private val searchChromeFlow =
        combine(searchQuery, searchResults, searchPerformed, highlightedMessageId) { query, results, performed, highlighted ->
            SearchChrome(query, results, performed, highlighted)
        }

    private val viewOptionsFlow =
        combine(
            showCompactedHistory,
            showArchivedSessions,
            messageWindow,
            settingsDataStore.settings.map { it.providerType == ProviderType.Fake }.distinctUntilChanged(),
        ) { showCompacted, showArchived, window, isFake ->
            ViewOptions(showCompacted, showArchived, window, isFake)
        }

    private val chromeFlow =
        combine(
            baseChromeFlow,
            turnChromeFlow,
            searchChromeFlow,
            viewOptionsFlow,
            mutableCurrentSessionId,
        ) { base, turn, search, options, sessionId ->
            ChatChrome(base, turn, search, options, sessionId)
        }

    private val sessionDataFlow =
        combine(sessionsFlow, archivedSessionsFlow, sessionMessagesFlow) { sessions, archived, messages ->
            SessionData(sessions, archived, messages)
        }

    private val attachmentChromeFlow =
        combine(
            stagedAttachments,
            importingAttachmentCount,
            settingsDataStore.settings.map { it.imagesEnabled() }.distinctUntilChanged(),
        ) { staged, importing, imagesEnabled ->
            Triple(staged.map(::stagedAttachmentUi), importing > 0, imagesEnabled && attachmentImporter != null)
        }

    val state: StateFlow<ChatUiState> =
        combine(chromeFlow, sessionDataFlow, attachmentChromeFlow) { chrome, data, attachments ->
            buildState(chrome, data).copy(
                stagedAttachments = attachments.first,
                isImportingAttachments = attachments.second,
                imagesEnabled = attachments.third,
            )
        }.stateIn(
            scope = viewModelScope,
            started = uiSharingStarted,
            initialValue = ChatUiState(),
        )

    init {
        viewModelScope.launch {
            val mainSession = sessionRepository.getOrCreateMainSession()
            if (mutableCurrentSessionId.value.isBlank()) {
                selectSession(mainSession.id)
            }
        }
    }

    fun onDraftChanged(value: String) {
        val boundedValue = value.take(CHAT_DRAFT_MAX_CHARS)
        draft.value = boundedValue
        composerText = boundedValue
        voice.onDraftEdited(boundedValue)
        errorMessage.value = null
        noticeMessage.value =
            if (value.length > boundedValue.length) {
                CHAT_DRAFT_TRUNCATED_NOTICE
            } else {
                null
            }
    }

    fun sendCurrentDraft() {
        val sessionId = currentSessionId.value
        val draftValue = draft.value.trim()
        if (sessionId.isBlank() || (draftValue.isBlank() && stagedAttachments.value.isEmpty()) || isRunning.value) return

        voice.onTurnStarted()
        startTurn(
            sessionId = sessionId,
            userMessage = draftValue,
            persistUserMessage = true,
            clearDraft = true,
            isRetry = false,
            attachments = stagedAttachments.value,
        )
    }

    /** Recognizer result: into the composer, and sent at once when "Send voice input right away" is on. */
    fun onVoiceTranscript(text: String?) {
        val transcript = voice.onTranscript(text) ?: return
        onDraftChanged(transcript)
        viewModelScope.launch { if (voice.sendRightAway()) sendCurrentDraft() }
    }

    /** Imports picked photos into the session folder and shows them as chips (at most 4). */
    fun onImagesPicked(uris: List<Uri>) {
        val importer = attachmentImporter ?: return
        val sessionId = currentSessionId.value
        if (uris.isEmpty() || sessionId.isBlank()) return
        val room = MAX_ATTACHMENTS_PER_MESSAGE - stagedAttachments.value.size - importingAttachmentCount.value
        if (uris.size > room) {
            noticeMessage.value = UiText.res(R.string.chat_attach_limit, MAX_ATTACHMENTS_PER_MESSAGE)
        }
        val accepted = uris.take(room.coerceAtLeast(0))
        if (accepted.isEmpty()) return
        importingAttachmentCount.value += accepted.size
        viewModelScope.launch {
            accepted.forEach { uri ->
                try {
                    val staged = importer.importImage(sessionId, uri)
                    if (currentSessionId.value == sessionId) {
                        stagedAttachments.value = stagedAttachments.value + staged
                    } else {
                        attachmentRepository?.discardStaged(listOf(staged))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    errorMessage.value = error.toUiError(R.string.chat_attach_failed)
                } finally {
                    importingAttachmentCount.value -= 1
                }
            }
        }
    }

    fun removeStagedAttachment(id: String) {
        val removed = stagedAttachments.value.filter { it.id == id }
        if (removed.isEmpty()) return
        stagedAttachments.value = stagedAttachments.value - removed.toSet()
        viewModelScope.launch { attachmentRepository?.discardStaged(removed) }
    }

    /** The attach button stays visible for text-only models and explains why it does nothing. */
    fun onAttachUnavailable() {
        noticeMessage.value = UiText.res(R.string.chat_attach_unavailable)
    }

    private fun discardStagedAttachments() {
        val staged = stagedAttachments.value
        if (staged.isEmpty()) return
        stagedAttachments.value = emptyList()
        viewModelScope.launch { attachmentRepository?.discardStaged(staged) }
    }

    private fun stagedAttachmentUi(staged: StagedAttachment): ChatAttachmentUi =
        ChatAttachmentUi(
            id = staged.id,
            displayName = staged.displayName,
            mimeType = staged.mimeType,
            width = staged.width,
            height = staged.height,
            file = attachmentFile(staged.relativePath),
            thumbnailFile = attachmentThumbnailFile(staged.relativePath),
        )

    private fun messageAttachmentUi(attachment: MessageAttachment): ChatAttachmentUi? {
        if (attachmentRepository == null) return null
        return ChatAttachmentUi(
            id = attachment.id,
            displayName = attachment.displayName,
            mimeType = attachment.mimeType,
            width = attachment.width,
            height = attachment.height,
            file = attachmentFile(attachment.relativePath),
            thumbnailFile = attachmentThumbnailFile(attachment.relativePath),
        )
    }

    private fun attachmentFile(relativePath: String): java.io.File = attachmentRepository?.file(relativePath) ?: java.io.File(relativePath)

    private fun attachmentThumbnailFile(relativePath: String): java.io.File = attachmentRepository?.thumbnailFile(relativePath) ?: java.io.File(relativePath)

    override fun onCleared() {
        // Chips never sent: remove their files (small, local deletes).
        stagedAttachments.value.forEach { staged -> attachmentRepository?.store?.delete(staged.relativePath) }
        voice.release()
        super.onCleared()
    }

    fun cancelActiveTurn() {
        if (!isRunning.value || activeTurnJob == null || isCancelling.value) {
            return
        }
        cancelRequested = true
        isCancelling.value = true
        activeTurnStage.value = UiText.res(R.string.chat_stage_cancelling)
        activeTurnJob?.cancel()
    }

    fun retryLastFailedTurn() {
        val sessionId = currentSessionId.value
        val failedMessage = lastFailedUserMessageText
        if (
            sessionId.isBlank() ||
            failedMessage == null ||
            lastFailedSessionId != sessionId ||
            isRunning.value
        ) {
            return
        }

        startTurn(
            sessionId = sessionId,
            userMessage = failedMessage,
            persistUserMessage = false,
            clearDraft = false,
            isRetry = true,
        )
    }

    fun switchSession(sessionId: String) {
        if (isRunning.value || sessionId == mutableCurrentSessionId.value) return
        selectSession(sessionId)
        errorMessage.value = null
        noticeMessage.value = null
    }

    fun createNewSession(title: String) {
        if (isRunning.value) return
        viewModelScope.launch {
            val normalizedTitle = title.trim().ifBlank { defaultSessionTitle() }
            val created = sessionRepository.createSession(normalizedTitle)
            selectSession(created.id)
            errorMessage.value = null
            noticeMessage.value = null
        }
    }

    fun renameCurrentSession(title: String) {
        renameSession(currentSessionId.value, title)
    }

    fun renameSession(
        sessionId: String,
        title: String,
    ) {
        val normalized = boundChatRenameInput(title.trim()).value
        if (sessionId.isBlank() || normalized.isBlank()) return
        viewModelScope.launch {
            sessionRepository.updateTitle(id = sessionId, title = normalized)
        }
    }

    fun archiveCurrentSession() {
        archiveSession(currentSessionId.value)
    }

    fun archiveSession(sessionId: String) {
        if (isRunning.value || sessionId.isBlank()) return
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            if (session.isMain) {
                errorMessage.value = UiText.res(R.string.chat_error_main_session_archive)
                return@launch
            }
            sessionRepository.archiveSession(sessionId)
            if (sessionId == mutableCurrentSessionId.value) {
                selectSession(sessionRepository.getOrCreateMainSession().id)
            }
            errorMessage.value = null
            noticeMessage.value = UiText.res(R.string.chat_notice_session_archived, session.title)
        }
    }

    fun restoreSession(sessionId: String) {
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            sessionRepository.unarchiveSession(sessionId)
            noticeMessage.value = UiText.res(R.string.chat_notice_session_restored, session.title)
        }
    }

    fun deleteSession(sessionId: String) {
        if (isRunning.value || sessionId.isBlank()) return
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            if (session.isMain) {
                errorMessage.value = UiText.res(R.string.chat_error_main_session_delete)
                return@launch
            }
            sessionRepository.deleteSession(sessionId)
            if (sessionId == mutableCurrentSessionId.value) {
                selectSession(sessionRepository.getOrCreateMainSession().id)
            }
            errorMessage.value = null
            noticeMessage.value = UiText.res(R.string.chat_notice_session_deleted, session.title)
        }
    }

    fun setShowArchivedSessions(show: Boolean) {
        showArchivedSessions.value = show
    }

    fun loadEarlierMessages() {
        messageWindow.value += CHAT_MESSAGE_WINDOW
    }

    fun onSearchQueryChanged(value: String) {
        val boundedValue = value.take(CHAT_SEARCH_QUERY_MAX_CHARS)
        searchQuery.value = boundedValue
        searchPerformed.value = false
        if (value.length > boundedValue.length) {
            searchResults.value = emptyList()
            highlightedMessageId.value = null
            noticeMessage.value = CHAT_SEARCH_QUERY_TRUNCATED_NOTICE
            return
        }
        if (boundedValue.isBlank()) {
            searchResults.value = emptyList()
            highlightedMessageId.value = null
        }
    }

    fun runSearch() {
        val query = searchQuery.value.trim()
        if (query.isBlank()) {
            searchResults.value = emptyList()
            highlightedMessageId.value = null
            searchPerformed.value = false
            return
        }
        viewModelScope.launch {
            val sessionMatches =
                sessionRepository
                    .searchSessions(query, limit = SEARCH_SESSION_LIMIT)
                    .map { match ->
                        ChatSearchResultUi(
                            sessionId = match.sessionId,
                            sessionTitle = match.sessionTitle,
                            preview = match.sessionTitle,
                            matchType = ChatSearchMatchType.Session,
                        )
                    }
            val messageMatches =
                messageRepository
                    .searchMessages(query, limit = SEARCH_MESSAGE_LIMIT)
                    .map { match ->
                        ChatSearchResultUi(
                            sessionId = match.sessionId,
                            sessionTitle = match.sessionTitle,
                            preview = buildSearchPreview(match.content, query),
                            matchType = ChatSearchMatchType.Message,
                            messageId = match.messageId,
                        )
                    }
            searchResults.value = (sessionMatches + messageMatches).take(MAX_SEARCH_RESULTS)
            searchPerformed.value = true
            highlightedMessageId.value = null
        }
    }

    fun clearSearch() {
        searchQuery.value = ""
        searchResults.value = emptyList()
        searchPerformed.value = false
        highlightedMessageId.value = null
    }

    fun openSearchResult(result: ChatSearchResultUi) {
        if (isRunning.value) return
        if (result.sessionId != mutableCurrentSessionId.value) {
            selectSession(result.sessionId)
        }
        highlightedMessageId.value = result.messageId
        if (result.messageId != null) {
            showCompactedHistory.value = true
        }
        errorMessage.value = null
        noticeMessage.value = null
    }

    fun clearHighlight() {
        highlightedMessageId.value = null
    }

    fun clearNotice() {
        noticeMessage.value = null
    }

    fun clearError() {
        errorMessage.value = null
    }

    fun exportCurrentSession(format: ChatExportFormat) {
        if (isRunning.value) return
        viewModelScope.launch {
            runCatching { buildExportFileRequest(format) }
                .onSuccess { payload ->
                    errorMessage.value = null
                    externalActions.emit(ChatExternalAction.ExportDocument(payload))
                }.onFailure { throwable ->
                    errorMessage.value = throwable.toUiError(R.string.chat_error_export_failed)
                }
        }
    }

    fun toggleCompactedHistory() {
        showCompactedHistory.value =
            if (state.value.compactedHiddenMessageCount > 0) {
                !showCompactedHistory.value
            } else {
                false
            }
    }

    fun compactCurrentSession() {
        val stateSnapshot = state.value
        val sessionId = stateSnapshot.currentSessionId
        if (sessionId.isBlank() || isRunning.value || !stateSnapshot.canCompactCurrentSession) {
            return
        }
        startTurn(
            sessionId = sessionId,
            userMessage = "/compact",
            persistUserMessage = true,
            clearDraft = false,
            isRetry = false,
        )
    }

    fun shareCurrentSessionAsText() {
        if (isRunning.value) return
        viewModelScope.launch {
            runCatching { buildShareText() }
                .onSuccess { (subject, shareText) ->
                    errorMessage.value = null
                    externalActions.emit(ChatExternalAction.ShareText(subject = subject, text = shareText))
                }.onFailure { throwable ->
                    errorMessage.value = throwable.toUiError(R.string.chat_error_share_failed)
                }
        }
    }

    fun shareCurrentSessionAsFile(format: ChatExportFormat = ChatExportFormat.Markdown) {
        if (isRunning.value) return
        viewModelScope.launch {
            runCatching { buildExportFileRequest(format) }
                .onSuccess { payload ->
                    errorMessage.value = null
                    externalActions.emit(ChatExternalAction.ShareFile(payload))
                }.onFailure { throwable ->
                    errorMessage.value = throwable.toUiError(R.string.chat_error_share_failed)
                }
        }
    }

    fun onExternalActionCompleted(message: UiText?) {
        errorMessage.value = null
        noticeMessage.value = message?.boundedForChat()
    }

    fun onExternalActionFailed(message: UiText) {
        errorMessage.value = message.boundedForChat()
    }

    fun onExportCancelled() {
        noticeMessage.value = UiText.res(R.string.chat_notice_export_cancelled)
    }

    suspend fun writeExportFile(
        request: ChatExportFileRequest,
        appendable: Appendable,
    ): ChatExportWriteResult =
        withContext(Dispatchers.IO) {
            val session =
                sessionRepository.getSession(request.sessionId)
                    ?: error("Session is no longer available.")
            val totalMessageCount = messageRepository.getMessageCount(request.sessionId)
            val writer =
                ChatExportFormatter.openStreamingWriter(
                    session = session,
                    format = request.format,
                    exportedAt = request.exportedAt,
                    appendable = appendable,
                )
            val emittedMessages = writePagedMessages(request.sessionId) { writer.writeMessage(it) }
            writer.close(omittedMessageCount = totalMessageCount - emittedMessages)
        }

    private fun buildState(
        chrome: ChatChrome,
        data: SessionData,
    ): ChatUiState {
        val currentId = chrome.sessionId
        val sessionItems =
            data.sessions.map { session ->
                ChatSessionUi(
                    id = session.id,
                    title = session.title,
                    isSelected = session.id == currentId,
                    isMain = session.isMain,
                    updatedAt = session.updatedAt,
                )
            }
        val archivedItems =
            data.archivedSessions.map { session ->
                ChatSessionUi(
                    id = session.id,
                    title = session.title,
                    isSelected = false,
                    isMain = false,
                    updatedAt = session.updatedAt,
                )
            }
        val currentSession = data.sessions.firstOrNull { it.id == currentId }
        val messages = data.messages.map { message -> message.toUi(::messageAttachmentUi) }
        val boundaryIndex =
            currentSession
                ?.compactedUntilMessageId
                ?.let { boundary -> messages.indexOfFirst { it.id == boundary } }
                ?: -1
        val compactedHiddenMessageCount = if (boundaryIndex >= 0) boundaryIndex + 1 else 0
        val messagesAfterBoundary =
            if (boundaryIndex >= 0) messages.drop(compactedHiddenMessageCount) else messages
        val showCompacted = chrome.options.showCompactedHistory && compactedHiddenMessageCount > 0
        val visibleMessages =
            when {
                compactedHiddenMessageCount <= 0 -> messages
                showCompacted -> messages.filterNot(ChatMessageUi::isCompactControlMessage)
                else -> messagesAfterBoundary.filterNot(ChatMessageUi::isCompactControlMessage)
            }
        val window = transcriptWindow(visibleMessages, chrome.options.messageWindow, chrome.search.highlightedMessageId)
        val windowedMessages = visibleMessages.takeLast(window)
        return ChatUiState(
            currentSessionId = currentId,
            sessionTitle = currentSession?.title.orEmpty(),
            isMainSession = currentSession?.isMain == true,
            sessionSummary = currentSession?.summaryText,
            sessionUsage = buildUsage(data.messages),
            compactedHiddenMessageCount = compactedHiddenMessageCount,
            showCompactedHistory = showCompacted,
            searchQuery = chrome.search.searchQuery,
            searchResults = chrome.search.searchResults,
            searchPerformed = chrome.search.searchPerformed,
            highlightedMessageId = chrome.search.highlightedMessageId,
            draft = chrome.base.draft,
            isRunning = chrome.base.isRunning,
            isCancelling = chrome.base.isCancelling,
            errorMessage = chrome.base.errorMessage,
            noticeMessage = chrome.base.noticeMessage,
            slashCommands = chrome.turn.slashCommands,
            sessions = sessionItems,
            archivedSessions = archivedItems,
            showArchivedSessions = chrome.options.showArchivedSessions,
            messages = visibleMessages,
            items = groupChatItems(windowedMessages),
            earlierMessageCount = visibleMessages.size - windowedMessages.size,
            canArchiveCurrentSession = currentSession?.isMain == false,
            canCompactCurrentSession = messagesAfterBoundary.any { !it.isCompactControlMessage() },
            streamingAssistantText = chrome.turn.streamingAssistantText,
            canRetryLastFailedTurn = chrome.turn.canRetryLastFailedTurn,
            activeTurnStage = chrome.turn.activeTurnStage,
            isFakeProvider = chrome.options.isFakeProvider,
        )
    }

    private suspend fun keepCurrentSessionValid(sessions: List<Session>) {
        if (sessions.isEmpty()) return
        val current = mutableCurrentSessionId.value
        if (current.isNotBlank() && sessions.any { it.id == current }) return
        if (current.isNotBlank()) {
            // A list emitted just before a newly created session landed must not
            // bounce the selection; only fall back when the session is really gone.
            val stored = sessionRepository.getSession(current)
            if (stored != null && !stored.archived) return
            if (mutableCurrentSessionId.value != current) return
        }
        selectSession(sessions.firstOrNull { it.isMain }?.id ?: sessions.first().id)
    }

    private fun selectSession(sessionId: String) {
        if (stagedAttachments.value.any { it.sessionId != sessionId }) {
            discardStagedAttachments()
        }
        mutableCurrentSessionId.value = sessionId
        highlightedMessageId.value = null
        showCompactedHistory.value = false
        messageWindow.value = CHAT_MESSAGE_WINDOW
        refreshSkillCommands(sessionId)
        syncRetryAvailability(sessionId)
    }

    private suspend fun defaultSessionTitle(): String {
        val count = sessionRepository.getSessionStats().activeSessionCount
        return "Session ${count + 1}"
    }

    private fun startTurn(
        sessionId: String,
        userMessage: String,
        persistUserMessage: Boolean,
        clearDraft: Boolean,
        isRetry: Boolean,
        attachments: List<StagedAttachment> = emptyList(),
    ) {
        val normalizedUserMessage = userMessage.trim()
        if ((normalizedUserMessage.isBlank() && attachments.isEmpty() && persistUserMessage) || isRunning.value) return

        activeTurnJob?.cancel()
        activeTurnJob = null
        cancelRequested = false
        if (clearDraft) {
            draft.value = ""
            composerText = ""
            stagedAttachments.value = emptyList()
        }
        isRunning.value = true
        isCancelling.value = false
        errorMessage.value = null
        noticeMessage.value = null
        streamingAssistantText.value = ""
        activeTurnStage.value = UiText.res(R.string.chat_stage_waiting)
        canRetryLastFailedTurn.value = false

        activeTurnJob =
            viewModelScope.launch {
                if (isRetry) {
                    eventLogRepository.log(
                        category = EventCategory.Provider,
                        level = EventLevel.Info,
                        message = "Retrying failed turn.",
                        details = "sessionId=$sessionId",
                    )
                }
                try {
                    agentRunner
                        .runInteractiveTurnStream(
                            request =
                                AgentTurnRequest(
                                    sessionId = sessionId,
                                    userMessage = normalizedUserMessage,
                                    persistUserMessage = persistUserMessage,
                                    attachments = attachments,
                                ),
                        ).collect { event -> onTurnEvent(event, sessionId, normalizedUserMessage) }
                } catch (error: CancellationException) {
                    if (!cancelRequested) {
                        throw error
                    }
                } finally {
                    if (cancelRequested) {
                        markTurnCancelled()
                    }
                    isRunning.value = false
                    isCancelling.value = false
                    cancelRequested = false
                    activeTurnJob = null
                    voice.onTurnEnded()
                }
            }
    }

    private fun onTurnEvent(
        event: AgentTurnEvent,
        sessionId: String,
        userMessage: String,
    ) {
        when (event) {
            is AgentTurnEvent.AssistantTextDelta -> {
                streamingAssistantText.value =
                    streamingAssistantText.value.appendBoundedStreamingAssistantText(event.text)
                if (activeTurnStage.value == UiText.res(R.string.chat_stage_waiting)) {
                    activeTurnStage.value = UiText.res(R.string.chat_stage_generating)
                }
            }

            is AgentTurnEvent.ToolStarted -> {
                activeTurnStage.value = UiText.res(R.string.chat_stage_running_tool, event.name)
            }

            is AgentTurnEvent.ToolFinished -> {
                activeTurnStage.value =
                    if (event.success) {
                        UiText.res(R.string.chat_stage_tool_finished, event.name)
                    } else {
                        UiText.res(R.string.chat_stage_tool_failed, event.name)
                    }
            }

            is AgentTurnEvent.TurnCompleted -> {
                streamingAssistantText.value = ""
                activeTurnStage.value = null
                errorMessage.value = null
                lastFailedUserMessageText = null
                lastFailedSessionId = null
                canRetryLastFailedTurn.value = false
                refreshSkillCommands(sessionId)
                voice.onTurnCompleted(event.result.assistantMessageId, event.result.assistantMessage)
            }

            is AgentTurnEvent.TurnFailed -> {
                streamingAssistantText.value = ""
                activeTurnStage.value = null
                errorMessage.value =
                    if (event.kind == AgentTurnFailureKind.ImagesUnsupported) {
                        UiText.res(R.string.chat_error_images_unsupported)
                    } else {
                        boundedChatUiText(event.message, fallback = R.string.chat_error_turn_failed)
                    }
                lastFailedUserMessageText = userMessage
                lastFailedSessionId = sessionId
                canRetryLastFailedTurn.value = event.retryable
                logTurnFailure(message = event.message, kind = event.kind, retryable = event.retryable)
            }

            AgentTurnEvent.Cancelled -> markTurnCancelled()
        }
    }

    private fun markTurnCancelled() {
        streamingAssistantText.value = ""
        activeTurnStage.value = null
        errorMessage.value = null
        noticeMessage.value = UiText.res(R.string.chat_notice_turn_cancelled)
        canRetryLastFailedTurn.value = false
    }

    private fun logTurnFailure(
        message: String,
        kind: AgentTurnFailureKind,
        retryable: Boolean,
    ) {
        viewModelScope.launch {
            eventLogRepository.log(
                category = if (kind == AgentTurnFailureKind.Runtime) EventCategory.System else EventCategory.Provider,
                level = EventLevel.Error,
                message = message,
                details = "kind=${kind.name} retryable=$retryable",
            )
        }
    }

    private fun syncRetryAvailability(sessionId: String) {
        canRetryLastFailedTurn.value =
            lastFailedUserMessageText != null &&
            lastFailedSessionId == sessionId &&
            !isRunning.value
    }

    private fun refreshSkillCommands(sessionId: String?) {
        viewModelScope.launch {
            val commands =
                runCatching { skillManager.refreshSkills(sessionId = sessionId?.takeIf { it.isNotBlank() }) }
                    .getOrDefault(emptyList())
                    .mapNotNull { skill ->
                        val frontmatter = skill.frontmatter ?: return@mapNotNull null
                        if (!skill.enabled || !frontmatter.userInvocable) return@mapNotNull null
                        SlashCommandUi(
                            command = "/${frontmatter.name}",
                            description = frontmatter.description,
                        )
                    }.distinctBy { it.command }
                    .sortedBy { it.command }
            slashCommands.value = commands
        }
    }

    private suspend fun buildExportFileRequest(format: ChatExportFormat): ChatExportFileRequest =
        withContext(Dispatchers.IO) {
            val sessionId =
                currentSessionId.value.takeIf { it.isNotBlank() }
                    ?: error("No active session to export.")
            val session =
                sessionRepository.getSession(sessionId)
                    ?: error("Session is no longer available.")
            ChatExportFormatter.buildFileRequest(
                session = session,
                format = format,
            )
        }

    private suspend fun buildShareText(): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val sessionId =
                currentSessionId.value.takeIf { it.isNotBlank() }
                    ?: error("No active session to share.")
            val session =
                sessionRepository.getSession(sessionId)
                    ?: error("Session is no longer available.")
            val totalMessageCount = messageRepository.getMessageCount(sessionId)
            val output = StringBuilder()
            val writer =
                ChatExportFormatter.openStreamingWriter(
                    session = session,
                    format = ChatExportFormat.Text,
                    exportedAt = Instant.now(),
                    appendable = output,
                    maxChars = (CHAT_SHARE_TEXT_MAX_CHARS - CHAT_SHARE_TEXT_TRUNCATED_NOTICE.length).coerceAtLeast(0),
                    truncatedNotice = CHAT_SHARE_TEXT_TRUNCATED_NOTICE,
                    includeOmittedCountNotice = false,
                )
            val emittedMessages = writePagedMessages(sessionId) { writer.writeMessage(it) }
            writer.close(omittedMessageCount = totalMessageCount - emittedMessages)
            session.title to output.toString()
        }

    private suspend fun writePagedMessages(
        sessionId: String,
        write: (ChatMessage) -> Boolean,
    ): Int {
        var offset = 0
        var emittedMessages = 0
        while (true) {
            val page =
                messageRepository.getMessagePage(
                    sessionId = sessionId,
                    limit = CHAT_EXPORT_PAGE_SIZE,
                    offset = offset,
                )
            if (page.isEmpty()) {
                return emittedMessages
            }
            for (message in page) {
                if (!write(message)) {
                    return emittedMessages
                }
                emittedMessages += 1
            }
            offset += page.size
        }
    }

    companion object {
        private const val SEARCH_SESSION_LIMIT = 6
        private const val SEARCH_MESSAGE_LIMIT = 10
        private const val MAX_SEARCH_RESULTS = 12
        private const val CHAT_EXPORT_PAGE_SIZE = 100

        fun factory(dependencies: ChatDependencies) =
            viewModelFactory {
                ChatViewModel(
                    sessionRepository = dependencies.sessionRepository,
                    messageRepository = dependencies.messageRepository,
                    eventLogRepository = dependencies.eventLogRepository,
                    agentRunner = dependencies.agentRunner,
                    skillManager = dependencies.skillManager,
                    settingsDataStore = dependencies.settingsDataStore,
                    attachmentRepository = dependencies.attachmentRepository,
                    attachmentImporter = dependencies.attachmentImporter,
                    speechInput = dependencies.speechInput,
                    speechOutputFactory = dependencies.speechOutputFactory,
                )
            }
    }
}

/** Expands the transcript window so a highlighted search hit is always rendered. */
internal fun transcriptWindow(
    messages: List<ChatMessageUi>,
    requestedWindow: Int,
    highlightedMessageId: String?,
): Int {
    val highlightIndex =
        highlightedMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: return requestedWindow.coerceAtMost(messages.size)
    val needed = messages.size - highlightIndex + CHAT_HIGHLIGHT_CONTEXT
    return maxOf(requestedWindow, needed).coerceAtMost(messages.size)
}

private const val CHAT_HIGHLIGHT_CONTEXT = 10

/**
 * Pairs each tool request with its result (by tool call id) so the transcript
 * shows one collapsible card per tool invocation.
 */
internal fun groupChatItems(messages: List<ChatMessageUi>): List<ChatItemUi> {
    val resultsByCallId =
        messages
            .filter { it.role == ChatRole.ToolResult && it.toolCallId != null }
            .associateBy { it.toolCallId }
    val consumedResultIds = HashSet<String>()
    val items = ArrayList<ChatItemUi>(messages.size)
    for (message in messages) {
        when (message.role) {
            ChatRole.ToolCall -> {
                val result = message.toolCallId?.let(resultsByCallId::get)
                result?.let { consumedResultIds += it.id }
                val (name, arguments) = parseToolRequest(message.text)
                items +=
                    ChatItemUi.Tool(
                        key = message.id,
                        messageIds = listOfNotNull(message.id, result?.id),
                        toolName = name,
                        arguments = arguments,
                        result = result?.text?.let(::parseToolResult),
                        createdAt = message.createdAt,
                        attachments = result?.attachments.orEmpty(),
                    )
            }

            ChatRole.ToolResult -> {
                if (message.id !in consumedResultIds) {
                    items +=
                        ChatItemUi.Tool(
                            key = message.id,
                            messageIds = listOf(message.id),
                            toolName = "",
                            arguments = "",
                            result = parseToolResult(message.text),
                            createdAt = message.createdAt,
                            attachments = message.attachments,
                        )
                }
            }

            ChatRole.Assistant -> {
                val text = message.text.withoutLegacyActiveSkillsSuffix()
                val previousTool = items.lastOrNull() as? ChatItemUi.Tool
                // A slash command dispatched to a tool persists the card summary as the reply;
                // the card already shows it, so the duplicate line is not rendered.
                if (previousTool?.result == null || text.trim() != parseToolResultView(previousTool.result).headline) {
                    items += ChatItemUi.Message(if (text == message.text) message else message.copy(text = text))
                }
            }

            else -> items += ChatItemUi.Message(message)
        }
    }
    return items
}

private const val TOOL_REQUEST_PREFIX = "Tool request:"
private const val TOOL_RESULT_PREFIX = "Tool result:"

internal fun parseToolRequest(content: String): Pair<String, String> {
    val body = content.trim().removePrefix(TOOL_REQUEST_PREFIX).trim()
    if (body.isEmpty()) return "" to ""
    val separator = body.indexOfFirst { it.isWhitespace() || it == '{' || it == '(' }
    return if (separator <= 0) {
        body to ""
    } else {
        body.substring(0, separator).trim() to body.substring(separator).trim()
    }
}

internal fun parseToolResult(content: String): String = content.trim().removePrefix(TOOL_RESULT_PREFIX).trim()

private fun buildUsage(messages: List<ChatMessage>): ChatUsageUi? {
    var trackedReplies = 0
    var inputTokens = 0
    var outputTokens = 0
    var totalTokens = 0
    var hasAnyUsage = false

    messages.forEach { message ->
        if (message.role != MessageRole.Assistant) {
            return@forEach
        }
        val usage = parseProviderMessageMeta(message.providerMeta)?.usage ?: return@forEach
        trackedReplies += 1
        usage.inputTokens?.let {
            inputTokens += it
            hasAnyUsage = true
        }
        usage.outputTokens?.let {
            outputTokens += it
            hasAnyUsage = true
        }
        usage.totalTokens?.let {
            totalTokens += it
            hasAnyUsage = true
        }
    }

    if (!hasAnyUsage || trackedReplies == 0) {
        return null
    }
    return ChatUsageUi(
        trackedReplies = trackedReplies,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )
}

private data class BaseChatChrome(
    val draft: String,
    val isRunning: Boolean,
    val isCancelling: Boolean,
    val errorMessage: UiText?,
    val noticeMessage: UiText?,
)

private data class TurnChrome(
    val streamingAssistantText: String,
    val canRetryLastFailedTurn: Boolean,
    val activeTurnStage: UiText?,
    val slashCommands: List<SlashCommandUi>,
)

private data class SearchChrome(
    val searchQuery: String,
    val searchResults: List<ChatSearchResultUi>,
    val searchPerformed: Boolean,
    val highlightedMessageId: String?,
)

private data class ViewOptions(
    val showCompactedHistory: Boolean,
    val showArchivedSessions: Boolean,
    val messageWindow: Int,
    val isFakeProvider: Boolean,
)

private data class ChatChrome(
    val base: BaseChatChrome,
    val turn: TurnChrome,
    val search: SearchChrome,
    val options: ViewOptions,
    val sessionId: String,
)

private data class SessionData(
    val sessions: List<Session>,
    val archivedSessions: List<Session>,
    val messages: List<ChatMessage>,
)

private fun String.appendBoundedStreamingAssistantText(delta: String): String {
    if (endsWith(CHAT_STREAMING_ASSISTANT_TEXT_TRUNCATED_NOTICE)) {
        return this
    }
    val combined = this + delta
    if (combined.length <= CHAT_STREAMING_ASSISTANT_TEXT_MAX_CHARS) {
        return combined
    }
    val prefixLength =
        (CHAT_STREAMING_ASSISTANT_TEXT_MAX_CHARS - CHAT_STREAMING_ASSISTANT_TEXT_TRUNCATED_NOTICE.length)
            .coerceAtLeast(0)
    return combined.take(prefixLength).trimEnd() + CHAT_STREAMING_ASSISTANT_TEXT_TRUNCATED_NOTICE
}

private fun buildSearchPreview(
    content: String,
    query: String,
): String {
    val normalizedContent = content.trim().replace('\n', ' ')
    val index = normalizedContent.indexOf(query, ignoreCase = true)
    if (index < 0 || normalizedContent.length <= SEARCH_PREVIEW_LIMIT) {
        return normalizedContent.take(SEARCH_PREVIEW_LIMIT)
    }
    val start = (index - SEARCH_PREVIEW_WINDOW).coerceAtLeast(0)
    val end = (index + query.length + SEARCH_PREVIEW_WINDOW).coerceAtMost(normalizedContent.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < normalizedContent.length) "…" else ""
    return prefix + normalizedContent.substring(start, end) + suffix
}

private const val SEARCH_PREVIEW_WINDOW = 48
private const val SEARCH_PREVIEW_LIMIT = 120

/** Runtime/provider messages are shown verbatim (bounded); blank ones fall back to a resource. */
internal fun boundedChatUiText(
    message: String?,
    fallback: Int,
): UiText =
    message
        ?.takeIf(String::isNotBlank)
        ?.take(CHAT_UI_MESSAGE_MAX_CHARS)
        ?.let(UiText::Raw)
        ?: UiText.res(fallback)

/** Bounds runtime-provided text inside a [UiText] so oversized messages never reach Compose state. */
internal fun UiText.boundedForChat(): UiText =
    when (this) {
        is UiText.Raw -> UiText.Raw(value.take(CHAT_UI_MESSAGE_MAX_CHARS))
        is UiText.Res -> copy(args = args.map { arg -> if (arg is String) arg.take(CHAT_UI_MESSAGE_MAX_CHARS) else arg })
        is UiText.Plural -> this
    }

private fun Throwable.toUiError(resource: Int): UiText {
    val details = message?.takeIf { it.isNotBlank() }?.take(CHAT_UI_MESSAGE_MAX_CHARS) ?: "-"
    return UiText.res(resource, details)
}

private fun ChatMessage.toUi(attachmentUi: (MessageAttachment) -> ChatAttachmentUi?): ChatMessageUi =
    ChatMessageUi(
        id = id,
        role =
            when (role) {
                MessageRole.User -> ChatRole.User
                MessageRole.Assistant -> ChatRole.Assistant
                MessageRole.ToolCall -> ChatRole.ToolCall
                MessageRole.ToolResult -> ChatRole.ToolResult
                MessageRole.System -> ChatRole.System
            },
        text = content,
        createdAt = createdAt,
        toolCallId = toolCallId,
        webSearch = if (role == MessageRole.Assistant) chatWebSearchFromMeta(providerMeta) else null,
        attachments = attachments.mapNotNull(attachmentUi),
    )

private fun ChatMessageUi.isCompactControlMessage(): Boolean {
    val trimmedText = text.trim()
    return when (role) {
        ChatRole.User -> trimmedText.startsWith("/compact")
        ChatRole.ToolCall -> trimmedText.startsWith("Tool request: sessions.compact")
        ChatRole.ToolResult -> trimmedText.startsWith("Tool result: Compacted this session")
        ChatRole.Assistant -> trimmedText.startsWith("Compacted this session")
        ChatRole.System -> false
    }
}
