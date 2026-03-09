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
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.skills.SkillManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val errorMessage: String? = null,
    val slashCommands: List<String> = emptyList(),
    val sessions: List<ChatSessionUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val canArchiveCurrentSession: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val eventLogRepository: EventLogRepository,
    private val agentRunner: AgentRunner,
    private val skillManager: SkillManager,
) : ViewModel() {
    private val draft = MutableStateFlow("")
    private val isRunning = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val slashCommands = MutableStateFlow<List<String>>(emptyList())
    private val mutableCurrentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = mutableCurrentSessionId.asStateFlow()

    private val sessionsFlow = sessionRepository.observeSessions()

    private val messagesFlow: Flow<List<ChatMessageUi>> = mutableCurrentSessionId.flatMapLatest { sessionId ->
        if (sessionId.isBlank()) {
            flowOf(emptyList())
        } else {
            messageRepository.observeMessages(sessionId).map { messages ->
                messages.map(ChatMessage::toUi)
            }
        }
    }

    private val chromeFlow = combine(
        draft,
        isRunning,
        errorMessage,
        slashCommands,
        mutableCurrentSessionId,
    ) { draftValue, isRunningValue, errorMessageValue, slashCommandsValue, currentSessionIdValue ->
        ChatUiState(
            currentSessionId = currentSessionIdValue,
            sessionTitle = "",
            draft = draftValue,
            isRunning = isRunningValue,
            errorMessage = errorMessageValue,
            slashCommands = slashCommandsValue,
            sessions = emptyList(),
            messages = emptyList(),
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
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState(),
    )

    init {
        viewModelScope.launch {
            val mainSession = sessionRepository.getOrCreateMainSession()
            if (mutableCurrentSessionId.value.isBlank()) {
                mutableCurrentSessionId.value = mainSession.id
                refreshSkillCommands(mainSession.id)
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
                }
            }
        }
        refreshSkillCommands()
    }

    fun onDraftChanged(value: String) {
        draft.value = value
        errorMessage.value = null
    }

    fun sendCurrentDraft() {
        val sessionId = currentSessionId.value
        val draftValue = draft.value.trim()
        if (sessionId.isBlank() || draftValue.isBlank() || isRunning.value) return

        draft.value = ""
        isRunning.value = true
        errorMessage.value = null

        viewModelScope.launch {
            try {
                agentRunner.runInteractiveTurn(
                    request = AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = draftValue,
                    ),
                )
                refreshSkillCommands()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "Turn failed."
                errorMessage.value = message
                viewModelScope.launch {
                    eventLogRepository.log(
                        category = if (error is ModelProviderException) EventCategory.Provider else EventCategory.System,
                        level = EventLevel.Error,
                        message = message,
                        details = if (error is ModelProviderException) {
                            buildString {
                                append("kind=")
                                append(error.kind.name)
                                error.details?.takeIf { it.isNotBlank() }?.let {
                                    append("; details=")
                                    append(it)
                                }
                            }
                        } else {
                            error.stackTraceToString().take(500)
                        },
                    )
                }
            } finally {
                isRunning.value = false
            }
        }
    }

    fun switchSession(sessionId: String) {
        mutableCurrentSessionId.value = sessionId
        errorMessage.value = null
        refreshSkillCommands(sessionId)
    }

    fun createNewSession(title: String) {
        viewModelScope.launch {
            val normalizedTitle = title.trim().ifBlank { "Session ${state.value.sessions.size + 1}" }
            val created = sessionRepository.createSession(normalizedTitle)
            mutableCurrentSessionId.value = created.id
            errorMessage.value = null
            refreshSkillCommands(created.id)
        }
    }

    fun renameCurrentSession(title: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            sessionRepository.updateTitle(
                id = sessionId,
                title = title.trim().ifBlank { "Untitled session" },
            )
        }
    }

    fun archiveCurrentSession() {
        val current = state.value.sessions.firstOrNull { it.isSelected } ?: return
        if (current.isMain) {
            errorMessage.value = "The main session cannot be archived."
            return
        }
        viewModelScope.launch {
            sessionRepository.archiveSession(current.id)
            errorMessage.value = null
        }
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
