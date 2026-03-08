package ai.androidclaw.feature.chat

import ai.androidclaw.app.ChatDependencies
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import ai.androidclaw.runtime.skills.SkillManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
)

data class ChatUiState(
    val currentSessionId: String = "",
    val sessionTitle: String = "",
    val draft: String = "",
    val isRunning: Boolean = false,
    val slashCommands: List<String> = emptyList(),
    val sessions: List<ChatSessionUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRunner: AgentRunner,
    private val skillManager: SkillManager,
) : ViewModel() {
    private val draft = MutableStateFlow("")
    private val isRunning = MutableStateFlow(false)
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
        slashCommands,
        mutableCurrentSessionId,
    ) { draftValue, isRunningValue, slashCommandsValue, currentSessionIdValue ->
        ChatUiState(
            currentSessionId = currentSessionIdValue,
            sessionTitle = "",
            draft = draftValue,
            isRunning = isRunningValue,
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
            )
        }
        chrome.copy(
            sessionTitle = sessionItems.firstOrNull { it.isSelected }?.title.orEmpty(),
            sessions = sessionItems,
            messages = messages,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState(),
    )

    init {
        viewModelScope.launch {
            sessionRepository.observeSessions().collect { sessions ->
                when {
                    sessions.isEmpty() -> Unit
                    mutableCurrentSessionId.value.isBlank() -> {
                        mutableCurrentSessionId.value = sessions.firstOrNull { it.isMain }?.id ?: sessions.first().id
                    }

                    sessions.none { it.id == mutableCurrentSessionId.value } -> {
                        mutableCurrentSessionId.value = sessions.first().id
                    }
                }
            }
        }
        viewModelScope.launch {
            val mainSession = sessionRepository.getOrCreateMainSession()
            if (mutableCurrentSessionId.value.isBlank()) {
                mutableCurrentSessionId.value = mainSession.id
            }
        }
        refreshSkillCommands()
    }

    fun onDraftChanged(value: String) {
        draft.value = value
    }

    fun sendCurrentDraft() {
        val sessionId = currentSessionId.value
        val draftValue = draft.value.trim()
        if (sessionId.isBlank() || draftValue.isBlank() || isRunning.value) return

        draft.value = ""
        isRunning.value = true

        viewModelScope.launch {
            try {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.User,
                    content = draftValue,
                )
                val result = agentRunner.runInteractiveTurn(
                    request = AgentTurnRequest(
                        sessionId = sessionId,
                        userMessage = draftValue,
                    ),
                )
                val assistantText = buildString {
                    append(result.assistantMessage)
                    if (result.selectedSkills.isNotEmpty()) {
                        append("\n\nActive skills: ")
                        append(result.selectedSkills.joinToString { it.displayName })
                    }
                }
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.Assistant,
                    content = assistantText,
                )
                refreshSkillCommands()
            } finally {
                isRunning.value = false
            }
        }
    }

    fun switchSession(sessionId: String) {
        mutableCurrentSessionId.value = sessionId
    }

    fun createNewSession(title: String) {
        viewModelScope.launch {
            val normalizedTitle = title.trim().ifBlank { "Session ${state.value.sessions.size + 1}" }
            val created = sessionRepository.createSession(normalizedTitle)
            mutableCurrentSessionId.value = created.id
        }
    }

    private fun refreshSkillCommands() {
        viewModelScope.launch {
            val commands = skillManager.refreshBundledSkills()
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
