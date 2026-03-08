package ai.androidclaw.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.androidclaw.app.AppContainer
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
)

data class ChatUiState(
    val sessionTitle: String = "Main session",
    val draft: String = "",
    val isRunning: Boolean = false,
    val slashCommands: List<String> = emptyList(),
    val messages: List<ChatMessageUi> = listOf(
        ChatMessageUi(
            id = "welcome",
            role = "assistant",
            text = "AndroidClaw bootstrap is running with a deterministic FakeProvider. Try a normal message or `/list_tasks`.",
        ),
    ),
)

class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    init {
        refreshSkillCommands()
    }

    fun onDraftChanged(value: String) {
        mutableState.update { it.copy(draft = value) }
    }

    fun sendCurrentDraft() {
        val draft = state.value.draft.trim()
        if (draft.isBlank() || state.value.isRunning) return

        val userMessage = ChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = draft,
        )
        mutableState.update {
            it.copy(
                draft = "",
                isRunning = true,
                messages = it.messages + userMessage,
            )
        }

        viewModelScope.launch {
            val result = container.agentRunner.runInteractiveTurn(
                request = AgentTurnRequest(
                    sessionId = "main",
                    userMessage = draft,
                ),
            )
            val assistantText = buildString {
                append(result.assistantMessage)
                if (result.selectedSkills.isNotEmpty()) {
                    append("\n\nActive skills: ")
                    append(result.selectedSkills.joinToString { it.displayName })
                }
            }
            mutableState.update {
                it.copy(
                    isRunning = false,
                    messages = it.messages + ChatMessageUi(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        text = assistantText,
                    ),
                )
            }
            refreshSkillCommands()
        }
    }

    private fun refreshSkillCommands() {
        viewModelScope.launch {
            val commands = container.skillManager.refreshBundledSkills()
                .mapNotNull { skill ->
                    val frontmatter = skill.frontmatter ?: return@mapNotNull null
                    if (frontmatter.userInvocable) "/${frontmatter.name}" else null
                }
                .sorted()
            mutableState.update { it.copy(slashCommands = commands) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(container) as T
                }
            }
        }
    }
}

