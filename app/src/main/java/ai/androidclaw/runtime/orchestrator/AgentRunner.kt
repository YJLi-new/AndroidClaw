package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.ModelSkillMetadata
import ai.androidclaw.runtime.providers.ModelStreamEvent
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolExecutionContext
import ai.androidclaw.runtime.tools.ToolInvocationOrigin
import ai.androidclaw.runtime.tools.ToolRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentTurnRequest(
    val sessionId: String,
    val userMessage: String,
    val taskRunId: String? = null,
    val persistUserMessage: Boolean = true,
)

enum class AgentTurnExitReason {
    Completed,
    DirectToolDispatch,
    ToolLoopExhausted,
}

data class AgentTurnResult(
    val assistantMessage: String,
    val assistantMessageId: String? = null,
    val selectedSkills: List<SkillSnapshot>,
    val directToolResult: ToolExecutionResult? = null,
    val providerRequestId: String? = null,
    val exitReason: AgentTurnExitReason = AgentTurnExitReason.Completed,
)

class AgentRunner(
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val messageRepository: MessageRepository,
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry,
    private val sessionLaneCoordinator: SessionLaneCoordinator,
    private val promptAssembler: PromptAssembler,
) {
    suspend fun runInteractiveTurn(request: AgentTurnRequest): AgentTurnResult {
        return runTurn(
            sessionId = request.sessionId,
            userMessage = request.userMessage,
            runMode = ModelRunMode.Interactive,
            taskRunId = request.taskRunId,
            persistUserMessage = request.persistUserMessage,
        )
    }

    fun runInteractiveTurnStream(request: AgentTurnRequest): Flow<AgentTurnEvent> = channelFlow {
        try {
            val result = runTurnStream(
                sessionId = request.sessionId,
                userMessage = request.userMessage,
                taskRunId = request.taskRunId,
                persistUserMessage = request.persistUserMessage,
                emitEvent = { event -> send(event) },
            )
            send(AgentTurnEvent.TurnCompleted(result))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            send(
                AgentTurnEvent.TurnFailed(
                    message = error.message ?: "Turn failed.",
                    retryable = error.isRetryable(),
                    kind = error.toFailureKind(),
                ),
            )
        }
    }

    suspend fun runScheduledTurn(
        sessionId: String,
        userMessage: String,
        taskRunId: String? = null,
    ): AgentTurnResult {
        return runTurn(
            sessionId = sessionId,
            userMessage = userMessage,
            runMode = ModelRunMode.Scheduled,
            taskRunId = taskRunId,
        )
    }

    private suspend fun runTurn(
        sessionId: String,
        userMessage: String,
        runMode: ModelRunMode,
        taskRunId: String?,
        persistUserMessage: Boolean = true,
    ): AgentTurnResult {
        return sessionLaneCoordinator.withLane(sessionId) {
            executeTurn(
                sessionId = sessionId,
                userMessage = userMessage,
                runMode = runMode,
                taskRunId = taskRunId,
                persistUserMessage = persistUserMessage,
            )
        }
    }

    private suspend fun runTurnStream(
        sessionId: String,
        userMessage: String,
        taskRunId: String?,
        persistUserMessage: Boolean,
        emitEvent: suspend (AgentTurnEvent) -> Unit,
    ): AgentTurnResult {
        return sessionLaneCoordinator.withLane(sessionId) {
            executeTurn(
                sessionId = sessionId,
                userMessage = userMessage,
                runMode = ModelRunMode.Interactive,
                taskRunId = taskRunId,
                persistUserMessage = persistUserMessage,
                emitEvent = emitEvent,
                useStreamingProvider = true,
            )
        }
    }

    private suspend fun executeTurn(
        sessionId: String,
        userMessage: String,
        runMode: ModelRunMode,
        taskRunId: String?,
        persistUserMessage: Boolean = true,
        emitEvent: suspend (AgentTurnEvent) -> Unit = {},
        useStreamingProvider: Boolean = false,
    ): AgentTurnResult {
        val normalizedUserMessage = userMessage.trim()
        val availableSkills = skillManager.refreshSkillInventory(sessionId = sessionId)
        val slashCommand = SlashCommand.parse(normalizedUserMessage)
        if (persistUserMessage) {
            messageRepository.addMessage(
                sessionId = sessionId,
                role = MessageRole.User,
                content = normalizedUserMessage,
                taskRunId = taskRunId,
            )
        }
        try {
            if (slashCommand != null) {
                val slashSkill = skillManager.findSlashSkill(slashCommand.name, availableSkills)
                if (slashSkill == null) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = "No enabled skill named /${slashCommand.name} is available.",
                        selectedSkills = emptyList(),
                        taskRunId = taskRunId,
                    )
                }
                if (slashSkill.eligibility.status != SkillEligibilityStatus.Eligible) {
                    val reasons = slashSkill.eligibility.reasons
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = " ")
                        ?: "This skill is not currently available."
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = "Skill /${slashCommand.name} is unavailable. $reasons",
                        selectedSkills = listOf(slashSkill),
                        taskRunId = taskRunId,
                    )
                }

                val frontmatter = slashSkill.frontmatter
                if (
                    frontmatter != null &&
                    frontmatter.commandDispatch == SkillCommandDispatch.Tool &&
                    frontmatter.commandTool != null
                ) {
                    val toolResult = executeDirectToolDispatch(
                        sessionId = sessionId,
                        slashCommand = slashCommand,
                        slashSkill = slashSkill,
                        toolName = frontmatter.commandTool,
                        runMode = runMode,
                        taskRunId = taskRunId,
                        onToolStarted = { emitEvent(AgentTurnEvent.ToolStarted(it)) },
                        onToolFinished = { name, result ->
                            emitEvent(
                                AgentTurnEvent.ToolFinished(
                                    name = name,
                                    success = result.success,
                                    summary = result.summary,
                                ),
                            )
                        },
                    )
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = toolResult.summary,
                        selectedSkills = listOf(slashSkill),
                        directToolResult = toolResult,
                        taskRunId = taskRunId,
                        exitReason = AgentTurnExitReason.DirectToolDispatch,
                    )
                }
            }

            val selectedSkills = if (slashCommand != null) {
                skillManager.findSlashSkill(slashCommand.name, availableSkills)
                    ?.takeIf { it.eligibility.status == SkillEligibilityStatus.Eligible }
                    ?.let(::listOf)
                    ?: emptyList()
            } else {
                skillManager.selectModelSkills(availableSkills)
            }
            val toolDescriptors = toolRegistry.descriptors()
            val providerSettings = settingsDataStore.settings.first()
            val provider = providerRegistry.require(providerSettings.providerType)
            val persistedMessages = messageRepository.getRecentMessages(
                sessionId = sessionId,
                limit = MESSAGE_CONTEXT_FETCH_LIMIT,
            ).asReversed()
            val promptAssembly = promptAssembler.assemble(
                persistedMessages = persistedMessages,
                selectedSkills = selectedSkills,
                toolDescriptors = toolDescriptors,
                runMode = runMode,
            )
            var messageHistory = promptAssembly.messageHistory
            var providerRequestId: String? = null

            repeat(MAX_TOOL_ROUNDS) { round ->
                val request = buildModelRequest(
                    sessionId = sessionId,
                    messageHistory = messageHistory,
                    systemPrompt = promptAssembly.systemPrompt,
                    selectedSkills = selectedSkills,
                    toolDescriptors = toolDescriptors,
                    runMode = runMode,
                )
                val response = if (useStreamingProvider) {
                    withContext(Dispatchers.IO) {
                        collectStreamedResponse(
                            provider = provider,
                            request = request,
                            onTextDelta = { text ->
                                if (text.isNotEmpty()) {
                                    emitEvent(AgentTurnEvent.AssistantTextDelta(text))
                                }
                            },
                        )
                    }
                } else {
                    withContext(Dispatchers.IO) { provider.generate(request) }
                }
                providerRequestId = response.providerRequestId
                if (response.finishReason != TOOL_USE_FINISH_REASON) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = response.text,
                        selectedSkills = selectedSkills,
                        providerRequestId = response.providerRequestId,
                        taskRunId = taskRunId,
                    )
                }

                if (response.toolCalls.isEmpty()) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = "Provider requested tool use without specifying a tool call.",
                        selectedSkills = selectedSkills,
                        providerRequestId = response.providerRequestId,
                        taskRunId = taskRunId,
                    )
                }

                val toolResultMessages = executeProviderToolCalls(
                    sessionId = sessionId,
                    toolCalls = response.toolCalls,
                    runMode = runMode,
                    requestId = response.providerRequestId,
                    taskRunId = taskRunId,
                    onToolStarted = { emitEvent(AgentTurnEvent.ToolStarted(it)) },
                    onToolFinished = { name, result ->
                        emitEvent(
                            AgentTurnEvent.ToolFinished(
                                name = name,
                                success = result.success,
                                summary = result.summary,
                            ),
                        )
                    },
                )
                messageHistory = messageHistory +
                    ModelMessage(
                        role = ModelMessageRole.Assistant,
                        content = response.text,
                        toolCalls = response.toolCalls,
                    ) +
                    toolResultMessages

                if (round == MAX_TOOL_ROUNDS - 1) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = "Tool-call limit reached before the turn could complete.",
                        selectedSkills = selectedSkills,
                        providerRequestId = providerRequestId,
                        taskRunId = taskRunId,
                        exitReason = AgentTurnExitReason.ToolLoopExhausted,
                    )
                }
            }

            error("Unreachable: tool-call loop should return before exhausting repeat.")
        } catch (error: CancellationException) {
            if (useStreamingProvider) {
                handleTurnCancellation(
                    sessionId = sessionId,
                    runMode = runMode,
                    taskRunId = taskRunId,
                )
            }
            throw error
        } catch (error: Exception) {
            handleTurnFailure(
                sessionId = sessionId,
                runMode = runMode,
                taskRunId = taskRunId,
                error = error,
            )
            throw error
        }
    }

    private fun buildModelRequest(
        sessionId: String,
        messageHistory: List<ModelMessage>,
        systemPrompt: String,
        selectedSkills: List<SkillSnapshot>,
        toolDescriptors: List<ai.androidclaw.runtime.tools.ToolDescriptor>,
        runMode: ModelRunMode,
    ): ModelRequest {
        return ModelRequest(
            sessionId = sessionId,
            requestId = UUID.randomUUID().toString(),
            messageHistory = messageHistory,
            systemPrompt = systemPrompt,
            enabledSkills = selectedSkills.map { skill ->
                ModelSkillMetadata(
                    id = skill.id,
                    name = skill.displayName,
                    description = skill.frontmatter?.description.orEmpty(),
                    instructions = skill.instructionsMd,
                )
            },
            toolDescriptors = toolDescriptors,
            runMode = runMode,
        )
    }

    private suspend fun collectStreamedResponse(
        provider: ModelProvider,
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ai.androidclaw.runtime.providers.ModelResponse {
        val streamedText = StringBuilder()
        var completedResponse: ai.androidclaw.runtime.providers.ModelResponse? = null
        provider.streamGenerate(request).collect { event ->
            when (event) {
                is ModelStreamEvent.TextDelta -> {
                    if (event.text.isNotEmpty()) {
                        streamedText.append(event.text)
                        onTextDelta(event.text)
                    }
                }

                is ModelStreamEvent.ToolCallDelta -> Unit
                is ModelStreamEvent.Completed -> completedResponse = event.response
            }
        }

        val response = completedResponse ?: throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider stream ended without a final response.",
        )
        if (streamedText.isEmpty() && response.text.isNotBlank() && response.finishReason != TOOL_USE_FINISH_REASON) {
            onTextDelta(response.text)
        }
        if (response.text.isNotBlank() || response.toolCalls.isNotEmpty() || streamedText.isEmpty()) {
            return response
        }
        return response.copy(text = streamedText.toString())
    }

    private suspend fun executeDirectToolDispatch(
        sessionId: String,
        slashCommand: SlashCommand,
        slashSkill: SkillSnapshot,
        toolName: String,
        runMode: ModelRunMode,
        taskRunId: String?,
        onToolStarted: suspend (String) -> Unit = {},
        onToolFinished: suspend (String, ToolExecutionResult) -> Unit = { _, _ -> },
    ): ToolExecutionResult {
        val toolCallId = UUID.randomUUID().toString()
        val toolArguments = buildJsonObject {
            put("command", slashCommand.arguments)
            put("commandName", slashCommand.name)
            put("skillName", slashSkill.displayName)
        }
        messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.ToolCall,
            content = "Tool request: $toolName $toolArguments",
            toolCallId = toolCallId,
            taskRunId = taskRunId,
        )
        onToolStarted(toolName)
        val toolResult = toolRegistry.execute(
            context = ToolExecutionContext(
                sessionId = sessionId,
                taskRunId = taskRunId,
                origin = ToolInvocationOrigin.SlashCommand,
                runMode = runMode,
                requestedName = toolName,
                canonicalName = toolName,
                requestId = toolCallId,
                activeSkillId = slashSkill.id,
            ),
            arguments = toolArguments,
        )
        messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.ToolResult,
            content = "Tool result: ${toolResult.summary}",
            toolCallId = toolCallId,
            taskRunId = taskRunId,
        )
        onToolFinished(toolName, toolResult)
        return toolResult
    }

    private suspend fun executeProviderToolCalls(
        sessionId: String,
        toolCalls: List<ProviderToolCall>,
        runMode: ModelRunMode,
        requestId: String?,
        taskRunId: String?,
        onToolStarted: suspend (String) -> Unit = {},
        onToolFinished: suspend (String, ToolExecutionResult) -> Unit = { _, _ -> },
    ): List<ModelMessage> {
        return buildList {
            toolCalls.forEach { toolCall ->
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.ToolCall,
                    content = "Tool request: ${toolCall.name} ${toolCall.argumentsJson}",
                    toolCallId = toolCall.id,
                    taskRunId = taskRunId,
                )
                onToolStarted(toolCall.name)
                val toolResult = toolRegistry.execute(
                    context = ToolExecutionContext(
                        sessionId = sessionId,
                        taskRunId = taskRunId,
                        origin = if (runMode == ModelRunMode.Scheduled) {
                            ToolInvocationOrigin.ScheduledModel
                        } else {
                            ToolInvocationOrigin.Model
                        },
                        runMode = runMode,
                        requestedName = toolCall.name,
                        canonicalName = toolCall.name,
                        requestId = requestId ?: toolCall.id,
                    ),
                    arguments = toolCall.argumentsJson,
                )
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.ToolResult,
                    content = "Tool result: ${toolResult.summary}",
                    toolCallId = toolCall.id,
                    taskRunId = taskRunId,
                )
                onToolFinished(toolCall.name, toolResult)
                add(
                    ModelMessage(
                        role = ModelMessageRole.Tool,
                        content = toolResult.summary,
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                    ),
                )
            }
        }
    }

    private suspend fun persistAssistantResponse(
        sessionId: String,
        assistantText: String,
        selectedSkills: List<SkillSnapshot>,
        directToolResult: ToolExecutionResult? = null,
        providerRequestId: String? = null,
        taskRunId: String?,
        exitReason: AgentTurnExitReason = AgentTurnExitReason.Completed,
    ): AgentTurnResult {
        val persistedText = assistantText.withActiveSkills(selectedSkills)
        val assistantMessage = messageRepository.addMessage(
            sessionId = sessionId,
            role = MessageRole.Assistant,
            content = persistedText,
            providerMeta = providerRequestId,
            taskRunId = taskRunId,
        )
        return AgentTurnResult(
            assistantMessage = persistedText,
            assistantMessageId = assistantMessage.id,
            selectedSkills = selectedSkills,
            directToolResult = directToolResult,
            providerRequestId = providerRequestId,
            exitReason = exitReason,
        )
    }

    private suspend fun handleTurnFailure(
        sessionId: String,
        runMode: ModelRunMode,
        taskRunId: String?,
        error: Exception,
    ) {
        if (runMode == ModelRunMode.Interactive) {
            runCatching {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.System,
                    content = "Turn failed: ${error.message ?: "Turn failed."}",
                    taskRunId = taskRunId,
                )
            }
        }
    }

    private suspend fun handleTurnCancellation(
        sessionId: String,
        runMode: ModelRunMode,
        taskRunId: String?,
    ) {
        if (runMode == ModelRunMode.Interactive) {
            withContext(NonCancellable) {
                runCatching {
                    messageRepository.addMessage(
                        sessionId = sessionId,
                        role = MessageRole.System,
                        content = "Turn cancelled.",
                        taskRunId = taskRunId,
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6
        private const val MESSAGE_CONTEXT_FETCH_LIMIT = 256
        private const val TOOL_USE_FINISH_REASON = "tool_use"
    }
}

private fun String.withActiveSkills(selectedSkills: List<SkillSnapshot>): String {
    if (selectedSkills.isEmpty()) {
        return this
    }
    return buildString {
        append(this@withActiveSkills)
        append("\n\nActive skills: ")
        append(selectedSkills.joinToString { it.displayName })
    }
}

private fun Throwable.isRetryable(): Boolean {
    return this is ModelProviderException && (
        kind == ModelProviderFailureKind.Network ||
            kind == ModelProviderFailureKind.Timeout
        )
}

private fun Throwable.toFailureKind(): AgentTurnFailureKind {
    return when (this) {
        is ModelProviderException -> when (kind) {
            ModelProviderFailureKind.Configuration -> AgentTurnFailureKind.Configuration
            ModelProviderFailureKind.Authentication -> AgentTurnFailureKind.Authentication
            ModelProviderFailureKind.Network -> AgentTurnFailureKind.Network
            ModelProviderFailureKind.Timeout -> AgentTurnFailureKind.Timeout
            ModelProviderFailureKind.Response -> AgentTurnFailureKind.Response
        }

        else -> AgentTurnFailureKind.Runtime
    }
}
