package ai.androidclaw.feature.chat

import ai.androidclaw.app.ChatDependencies
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.AgentTurnEvent
import ai.androidclaw.runtime.orchestrator.AgentTurnFailureKind
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import ai.androidclaw.runtime.skills.SkillManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
)

data class ChatSessionUi(
    val id: String,
    val title: String,
    val isSelected: Boolean,
    val isMain: Boolean,
)

data class ChatUiState(
    val currentSessionId: String = "",
    val sessionTitle: String = "",
    val draft: String = "",
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val slashCommands: List<String> = emptyList(),
    val sessions: List<ChatSessionUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val canArchiveCurrentSession: Boolean = false,
    val streamingAssistantText: String = "",
    val canRetryLastFailedTurn: Boolean = false,
    val activeTurnStage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val eventLogRepository: EventLogRepository,
    private val agentRunner: AgentRunner,
    private val skillManager: SkillManager,
) : ViewModel() {
    private val uiSharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
    private val draft = MutableStateFlow("")
    private val isRunning = MutableStateFlow(false)
    private val isCancelling = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val noticeMessage = MutableStateFlow<String?>(null)
    private val slashCommands = MutableStateFlow<List<String>>(emptyList())
    private val mutableCurrentSessionId = MutableStateFlow("")
    private val streamingAssistantText = MutableStateFlow("")
    private val canRetryLastFailedTurn = MutableStateFlow(false)
    private val activeTurnStage = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String> = mutableCurrentSessionId.asStateFlow()

    private val sessionsFlow = sessionRepository.observeSessions()
    private var activeTurnJob: Job? = null
    private var cancelRequested: Boolean = false
    private var lastFailedUserMessageText: String? = null
    private var lastFailedSessionId: String? = null

    private val messagesFlow: Flow<List<ChatMessageUi>> = mutableCurrentSessionId.flatMapLatest { sessionId ->
        if (sessionId.isBlank()) {
            flowOf(emptyList())
        } else {
            messageRepository.observeMessages(sessionId).map { messages ->
                messages.map(ChatMessage::toUi)
            }
        }
    }

    private val baseChromeFlow = combine(
        draft,
        isRunning,
        isCancelling,
        errorMessage,
        noticeMessage,
    ) { draftValue, isRunningValue, isCancellingValue, errorMessageValue, noticeMessageValue ->
        BaseChatChrome(
            draft = draftValue,
            isRunning = isRunningValue,
            isCancelling = isCancellingValue,
            errorMessage = errorMessageValue,
            noticeMessage = noticeMessageValue,
        )
    }

    private val turnChromeFlow = combine(
        streamingAssistantText,
        canRetryLastFailedTurn,
        activeTurnStage,
    ) { streamingAssistantTextValue, canRetryValue, activeTurnStageValue ->
        TurnChrome(
            streamingAssistantText = streamingAssistantTextValue,
            canRetryLastFailedTurn = canRetryValue,
            activeTurnStage = activeTurnStageValue,
        )
    }

    private val chromeFlow = combine(
        baseChromeFlow,
        turnChromeFlow,
        slashCommands,
        mutableCurrentSessionId,
    ) { baseChrome, turnChrome, slashCommandsValue, currentSessionIdValue ->
        ChatUiState(
            currentSessionId = currentSessionIdValue,
            sessionTitle = "",
            draft = baseChrome.draft,
            isRunning = baseChrome.isRunning,
            isCancelling = baseChrome.isCancelling,
            errorMessage = baseChrome.errorMessage,
            noticeMessage = baseChrome.noticeMessage,
            slashCommands = slashCommandsValue,
            sessions = emptyList(),
            messages = emptyList(),
            streamingAssistantText = turnChrome.streamingAssistantText,
            canRetryLastFailedTurn = turnChrome.canRetryLastFailedTurn,
            activeTurnStage = turnChrome.activeTurnStage,
        )
    }

    val state: StateFlow<ChatUiState> = combine(
        chromeFlow,
        sessionsFlow,
        messagesFlow,
    ) { chrome, sessions, messages ->
        val sessionItems = sessions.map { session ->
            ChatSessionUi(
                id = session.id,
                title = session.title,
                isSelected = session.id == chrome.currentSessionId,
                isMain = session.isMain,
            )
        }
        val currentSession = sessionItems.firstOrNull { it.isSelected }
        chrome.copy(
            sessionTitle = currentSession?.title.orEmpty(),
            sessions = sessionItems,
            messages = messages,
            canArchiveCurrentSession = currentSession?.isMain == false,
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
                mutableCurrentSessionId.value = mainSession.id
                refreshSkillCommands(mainSession.id)
                syncRetryAvailability(mainSession.id)
            }
            sessionRepository.observeSessions().collect { sessions ->
                val previousSessionId = mutableCurrentSessionId.value
                when {
                    sessions.isEmpty() -> Unit
                    mutableCurrentSessionId.value.isBlank() -> {
                        mutableCurrentSessionId.value = sessions.firstOrNull { it.isMain }?.id ?: sessions.first().id
                    }

                    sessions.none { it.id == mutableCurrentSessionId.value } -> {
                        mutableCurrentSessionId.value = sessions.first().id
                    }
                }
                if (mutableCurrentSessionId.value != previousSessionId) {
                    refreshSkillCommands(mutableCurrentSessionId.value)
                    syncRetryAvailability(mutableCurrentSessionId.value)
                }
            }
        }
        refreshSkillCommands()
    }

    fun onDraftChanged(value: String) {
        draft.value = value
        errorMessage.value = null
        noticeMessage.value = null
    }

    fun sendCurrentDraft() {
        val sessionId = currentSessionId.value
        val draftValue = draft.value.trim()
        if (sessionId.isBlank() || draftValue.isBlank() || isRunning.value) return

        startTurn(
            sessionId = sessionId,
            userMessage = draftValue,
            persistUserMessage = true,
            clearDraft = true,
            isRetry = false,
        )
    }

    fun cancelActiveTurn() {
        if (!isRunning.value || activeTurnJob == null || isCancelling.value) {
            return
        }
        cancelRequested = true
        isCancelling.value = true
        activeTurnStage.value = "Cancelling..."
        activeTurnJob?.cancel()
    }

    fun retryLastFailedTurn() {
        val sessionId = currentSessionId.value
        val failedMessage = lastFailedUserMessageText
        if (
            sessionId.isBlank() ||
            failedMessage.isNullOrBlank() ||
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
        if (isRunning.value) return
        mutableCurrentSessionId.value = sessionId
        errorMessage.value = null
        noticeMessage.value = null
        refreshSkillCommands(sessionId)
        syncRetryAvailability(sessionId)
    }

    fun createNewSession(title: String) {
        if (isRunning.value) return
        viewModelScope.launch {
            val normalizedTitle = title.trim().ifBlank { "Session ${state.value.sessions.size + 1}" }
            val created = sessionRepository.createSession(normalizedTitle)
            mutableCurrentSessionId.value = created.id
            errorMessage.value = null
            noticeMessage.value = null
            refreshSkillCommands(created.id)
            syncRetryAvailability(created.id)
        }
    }

    fun renameCurrentSession(title: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || isRunning.value) return
        viewModelScope.launch {
            sessionRepository.updateTitle(
                id = sessionId,
                title = title.trim().ifBlank { "Untitled session" },
            )
        }
    }

    fun archiveCurrentSession() {
        if (isRunning.value) return
        val current = state.value.sessions.firstOrNull { it.isSelected } ?: return
        if (current.isMain) {
            errorMessage.value = "The main session cannot be archived."
            return
        }
        viewModelScope.launch {
            sessionRepository.archiveSession(current.id)
            errorMessage.value = null
            noticeMessage.value = null
        }
    }

    private fun startTurn(
        sessionId: String,
        userMessage: String,
        persistUserMessage: Boolean,
        clearDraft: Boolean,
        isRetry: Boolean,
    ) {
        val normalizedUserMessage = userMessage.trim()
        if (normalizedUserMessage.isBlank() || isRunning.value) return

        activeTurnJob?.cancel()
        activeTurnJob = null
        cancelRequested = false
        if (clearDraft) {
            draft.value = ""
        }
        isRunning.value = true
        isCancelling.value = false
        errorMessage.value = null
        noticeMessage.value = null
        streamingAssistantText.value = ""
        activeTurnStage.value = "Waiting for response"
        canRetryLastFailedTurn.value = false

        activeTurnJob = viewModelScope.launch {
            if (isRetry) {
                eventLogRepository.log(
                    category = EventCategory.Provider,
                    level = EventLevel.Info,
                    message = "Retrying failed turn.",
                    details = "sessionId=$sessionId",
                )
            }
            try {
                agentRunner.runInteractiveTurnStream(
                    request = AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = normalizedUserMessage,
                        persistUserMessage = persistUserMessage,
                    ),
                ).collect { event ->
                    when (event) {
                        is AgentTurnEvent.AssistantTextDelta -> {
                            streamingAssistantText.value += event.text
                            if (activeTurnStage.value.isNullOrBlank() || activeTurnStage.value == "Waiting for response") {
                                activeTurnStage.value = "Generating response"
                            }
                        }

                        is AgentTurnEvent.ToolStarted -> {
                            activeTurnStage.value = "Running ${event.name}"
                        }

                        is AgentTurnEvent.ToolFinished -> {
                            activeTurnStage.value = if (event.success) {
                                "Finished ${event.name}"
                            } else {
                                "Tool ${event.name} failed"
                            }
                        }

                        is AgentTurnEvent.TurnCompleted -> {
                            streamingAssistantText.value = ""
                            activeTurnStage.value = null
                            errorMessage.value = null
                            noticeMessage.value = null
                            lastFailedUserMessageText = null
                            lastFailedSessionId = null
                            canRetryLastFailedTurn.value = false
                            refreshSkillCommands(sessionId)
                        }

                        is AgentTurnEvent.TurnFailed -> {
                            streamingAssistantText.value = ""
                            activeTurnStage.value = null
                            errorMessage.value = event.message
                            noticeMessage.value = null
                            lastFailedUserMessageText = normalizedUserMessage
                            lastFailedSessionId = sessionId
                            canRetryLastFailedTurn.value = event.retryable
                            logTurnFailure(message = event.message, kind = event.kind)
                        }

                        AgentTurnEvent.Cancelled -> {
                            streamingAssistantText.value = ""
                            activeTurnStage.value = null
                            errorMessage.value = null
                            noticeMessage.value = "Turn cancelled."
                            canRetryLastFailedTurn.value = false
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (cancelRequested) {
                    streamingAssistantText.value = ""
                    activeTurnStage.value = null
                    errorMessage.value = null
                    noticeMessage.value = "Turn cancelled."
                    canRetryLastFailedTurn.value = false
                } else {
                    throw error
                }
            } finally {
                val cancelledByUser = cancelRequested
                if (cancelledByUser) {
                    streamingAssistantText.value = ""
                    activeTurnStage.value = null
                    errorMessage.value = null
                    noticeMessage.value = "Turn cancelled."
                    canRetryLastFailedTurn.value = false
                }
                isRunning.value = false
                isCancelling.value = false
                cancelRequested = false
                activeTurnJob = null
            }
        }
    }

    private fun logTurnFailure(
        message: String,
        kind: AgentTurnFailureKind,
    ) {
        viewModelScope.launch {
            eventLogRepository.log(
                category = if (kind == AgentTurnFailureKind.Runtime) EventCategory.System else EventCategory.Provider,
                level = EventLevel.Error,
                message = message,
                details = "kind=${kind.name}",
            )
        }
    }

    private fun syncRetryAvailability(sessionId: String) {
        canRetryLastFailedTurn.value =
            lastFailedUserMessageText != null &&
            lastFailedSessionId == sessionId &&
            !isRunning.value
    }

    private fun refreshSkillCommands(
        sessionId: String? = mutableCurrentSessionId.value.takeIf { it.isNotBlank() },
    ) {
        viewModelScope.launch {
            val commands = skillManager.refreshSkills(sessionId = sessionId)
                .mapNotNull { skill ->
                    val frontmatter = skill.frontmatter ?: return@mapNotNull null
                    if (frontmatter.userInvocable) "/${frontmatter.name}" else null
                }
                .sorted()
            slashCommands.value = commands
        }
    }

    companion object {
        fun factory(dependencies: ChatDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(
                        sessionRepository = dependencies.sessionRepository,
                        messageRepository = dependencies.messageRepository,
                        eventLogRepository = dependencies.eventLogRepository,
                        agentRunner = dependencies.agentRunner,
                        skillManager = dependencies.skillManager,
                    ) as T
                }
            }
        }
    }
}

private data class BaseChatChrome(
    val draft: String,
    val isRunning: Boolean,
    val isCancelling: Boolean,
    val errorMessage: String?,
    val noticeMessage: String?,
)

private data class TurnChrome(
    val streamingAssistantText: String,
    val canRetryLastFailedTurn: Boolean,
    val activeTurnStage: String?,
)

private fun ChatMessage.toUi(): ChatMessageUi {
    return ChatMessageUi(
        id = id,
        role = when (role) {
            MessageRole.User -> "user"
            MessageRole.Assistant -> "assistant"
            MessageRole.ToolCall -> "tool_call"
            MessageRole.ToolResult -> "tool_result"
            MessageRole.System -> "system"
        },
        text = content,
    )
}
