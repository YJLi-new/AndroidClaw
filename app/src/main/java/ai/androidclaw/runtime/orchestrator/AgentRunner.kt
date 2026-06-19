package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.ChatMessage
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.MESSAGE_CONTENT_MAX_CHARS
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.runtime.memory.MemoryCoordinator
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.ModelSkillMetadata
import ai.androidclaw.runtime.providers.ModelStreamEvent
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.ProviderMessageMeta
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.providers.ProviderToolCall
import ai.androidclaw.runtime.providers.offlineFailure
import ai.androidclaw.runtime.providers.toPayload
import ai.androidclaw.runtime.providers.toStorageString
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.tools.ToolExecutionContext
import ai.androidclaw.runtime.tools.ToolExecutionResult
import ai.androidclaw.runtime.tools.ToolInvocationOrigin
import ai.androidclaw.runtime.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

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
    val providerMeta: String? = null,
    val exitReason: AgentTurnExitReason = AgentTurnExitReason.Completed,
)

internal const val AGENT_STREAMING_PREVIEW_MAX_CHARS = 20_000
internal const val AGENT_STREAMING_PREVIEW_TRUNCATED_NOTICE =
    "\n\n[Live response preview truncated by AndroidClaw. The saved assistant message may contain more text.]"

class AgentRunner(
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val messageRepository: MessageRepository,
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry,
    private val sessionLaneCoordinator: SessionLaneCoordinator,
    private val promptAssembler: PromptAssembler,
    private val sessionSummaryCoordinator: SessionSummaryCoordinator? = null,
    private val memoryCoordinator: MemoryCoordinator? = null,
    private val loadSessionSummary: suspend (String) -> String? = { null },
    private val loadSessionCompactionBoundary: suspend (String) -> String? = { null },
    private val networkStatusProvider: NetworkStatusProvider? = null,
) {
    suspend fun runInteractiveTurn(request: AgentTurnRequest): AgentTurnResult =
        runTurn(
            sessionId = request.sessionId,
            userMessage = request.userMessage,
            runMode = ModelRunMode.Interactive,
            taskRunId = request.taskRunId,
            persistUserMessage = request.persistUserMessage,
        )

    fun runInteractiveTurnStream(request: AgentTurnRequest): Flow<AgentTurnEvent> =
        channelFlow {
            try {
                val result =
                    runTurnStream(
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
    ): AgentTurnResult =
        runTurn(
            sessionId = sessionId,
            userMessage = userMessage,
            runMode = ModelRunMode.Scheduled,
            taskRunId = taskRunId,
        )

    private suspend fun runTurn(
        sessionId: String,
        userMessage: String,
        runMode: ModelRunMode,
        taskRunId: String?,
        persistUserMessage: Boolean = true,
    ): AgentTurnResult =
        sessionLaneCoordinator.withLane(sessionId) {
            executeTurn(
                sessionId = sessionId,
                userMessage = userMessage,
                runMode = runMode,
                taskRunId = taskRunId,
                persistUserMessage = persistUserMessage,
            )
        }

    private suspend fun runTurnStream(
        sessionId: String,
        userMessage: String,
        taskRunId: String?,
        persistUserMessage: Boolean,
        emitEvent: suspend (AgentTurnEvent) -> Unit,
    ): AgentTurnResult =
        sessionLaneCoordinator.withLane(sessionId) {
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
        val compactBoundaryMessageId =
            if (slashCommand?.name == COMPACT_COMMAND_NAME) {
                messageRepository
                    .getRecentMessages(
                        sessionId = sessionId,
                        limit = 1,
                    ).firstOrNull()
                    ?.id
            } else {
                null
            }
        val persistedUserMessage =
            if (persistUserMessage) {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.User,
                    content = normalizedUserMessage,
                    taskRunId = taskRunId,
                )
            } else {
                null
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
                    val reasons =
                        slashSkill.eligibility.reasons
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
                    val compactSummary =
                        buildCompactSummaryIfNeeded(
                            sessionId = sessionId,
                            slashCommand = slashCommand,
                            toolName = frontmatter.commandTool,
                            compactBoundaryMessageId = compactBoundaryMessageId,
                        )
                    val toolResult =
                        executeDirectToolDispatch(
                            sessionId = sessionId,
                            slashCommand = slashCommand,
                            slashSkill = slashSkill,
                            toolName = frontmatter.commandTool,
                            runMode = runMode,
                            taskRunId = taskRunId,
                            extraArguments =
                                compactToolArguments(
                                    compactBoundaryMessageId = compactBoundaryMessageId,
                                    compactSummary = compactSummary,
                                ),
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
                        triggerSummaryRefresh = frontmatter.commandTool != COMPACT_TOOL_NAME,
                    )
                }
            }

            val selectedSkills =
                if (slashCommand != null) {
                    skillManager
                        .findSlashSkill(slashCommand.name, availableSkills)
                        ?.takeIf { it.eligibility.status == SkillEligibilityStatus.Eligible }
                        ?.let(::listOf)
                        ?: emptyList()
                } else {
                    skillManager.selectModelSkills(availableSkills)
                }
            val toolOrigin = runMode.toModelToolInvocationOrigin()
            val toolDescriptors =
                toolRegistry.descriptorsFor(
                    origin = toolOrigin,
                    runMode = runMode,
                )
            val providerSettings = settingsDataStore.settings.first()
            if (
                providerSettings.providerType.requiresRemoteSettings &&
                networkStatusProvider?.currentStatus()?.isConnected == false
            ) {
                throw offlineFailure()
            }
            val provider = providerRegistry.require(providerSettings.providerType)
            val persistedMessages =
                messageRepository
                    .getRecentMessages(
                        sessionId = sessionId,
                        limit = MESSAGE_CONTEXT_FETCH_LIMIT,
                    ).asReversed()
            val sessionSummary = loadSessionSummary(sessionId)
            val compactionBoundary = loadSessionCompactionBoundary(sessionId)
            val crossSessionMemories =
                memoryCoordinator
                    ?.loadRelevantMemoryTexts(normalizedUserMessage)
                    .orEmpty()
            val promptAssembly =
                promptAssembler.assemble(
                    persistedMessages = persistedMessages.afterBoundary(compactionBoundary).withoutCompactControlMessages(),
                    selectedSkills = selectedSkills,
                    toolDescriptors = toolDescriptors,
                    runMode = runMode,
                    sessionSummary = sessionSummary,
                    forceSessionSummary = !sessionSummary.isNullOrBlank() && compactionBoundary != null,
                    crossSessionMemories = crossSessionMemories,
                )
            var messageHistory = promptAssembly.messageHistory
            var providerRequestId: String? = null

            repeat(MAX_TOOL_ROUNDS) { round ->
                val request =
                    buildModelRequest(
                        sessionId = sessionId,
                        messageHistory = messageHistory,
                        systemPrompt = promptAssembly.systemPrompt,
                        selectedSkills = selectedSkills,
                        toolDescriptors = toolDescriptors,
                        runMode = runMode,
                    )
                val response =
                    if (useStreamingProvider) {
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
                val boundedResponseText = response.text.toBoundedAgentAssistantText()
                providerRequestId = response.providerRequestId
                if (response.finishReason != TOOL_USE_FINISH_REASON) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = boundedResponseText,
                        selectedSkills = selectedSkills,
                        providerId = provider.id,
                        providerRequestId = response.providerRequestId,
                        providerModelId = response.modelId,
                        providerUsage = response.usage,
                        taskRunId = taskRunId,
                        sourceUserMessage = normalizedUserMessage,
                        sourceMessageIds = listOfNotNull(persistedUserMessage?.id),
                    )
                }

                if (response.toolCalls.isEmpty()) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = "Provider requested tool use without specifying a tool call.",
                        selectedSkills = selectedSkills,
                        providerId = provider.id,
                        providerRequestId = response.providerRequestId,
                        providerModelId = response.modelId,
                        providerUsage = response.usage,
                        taskRunId = taskRunId,
                    )
                }
                val validatedToolCalls = response.toolCalls.validateProviderToolCallsForAgent()

                val toolResultMessages =
                    executeProviderToolCalls(
                        sessionId = sessionId,
                        toolCalls = validatedToolCalls,
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
                        content = boundedResponseText,
                        toolCalls = validatedToolCalls,
                    ) +
                    toolResultMessages

                if (round == MAX_TOOL_ROUNDS - 1) {
                    return persistAssistantResponse(
                        sessionId = sessionId,
                        assistantText = "Tool-call limit reached before the turn could complete.",
                        selectedSkills = selectedSkills,
                        providerId = provider.id,
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
    ): ModelRequest =
        ModelRequest(
            sessionId = sessionId,
            requestId = UUID.randomUUID().toString(),
            messageHistory = messageHistory,
            systemPrompt = systemPrompt,
            enabledSkills = selectedSkills.toBoundedModelSkillMetadata(),
            toolDescriptors = toolDescriptors.toBoundedModelToolDescriptors(),
            runMode = runMode,
        )

    private suspend fun collectStreamedResponse(
        provider: ModelProvider,
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ai.androidclaw.runtime.providers.ModelResponse {
        val streamedText = StringBuilder()
        var emittedPreviewTextChars = 0
        var emittedPreviewTruncationNotice = false

        suspend fun emitBoundedTextDelta(text: String) {
            if (text.isEmpty() || emittedPreviewTruncationNotice) {
                return
            }
            val previewTextBudget =
                (AGENT_STREAMING_PREVIEW_MAX_CHARS - AGENT_STREAMING_PREVIEW_TRUNCATED_NOTICE.length)
                    .coerceAtLeast(0)
            val remainingTextBudget = previewTextBudget - emittedPreviewTextChars
            if (remainingTextBudget > 0) {
                val prefix = text.take(remainingTextBudget)
                if (prefix.isNotEmpty()) {
                    onTextDelta(prefix)
                    emittedPreviewTextChars += prefix.length
                }
            }
            if (text.length > remainingTextBudget) {
                onTextDelta(AGENT_STREAMING_PREVIEW_TRUNCATED_NOTICE)
                emittedPreviewTruncationNotice = true
            }
        }
        var completedResponse: ai.androidclaw.runtime.providers.ModelResponse? = null
        provider.streamGenerate(request).collect { event ->
            when (event) {
                is ModelStreamEvent.TextDelta -> {
                    if (event.text.isNotEmpty()) {
                        streamedText.appendBoundedAgentText(event.text)
                        emitBoundedTextDelta(event.text)
                    }
                }

                is ModelStreamEvent.ToolCallDelta -> Unit
                is ModelStreamEvent.Completed -> completedResponse = event.response
            }
        }

        val response =
            completedResponse ?: throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider stream ended without a final response.",
            )
        if (streamedText.isEmpty() && response.text.isNotBlank() && response.finishReason != TOOL_USE_FINISH_REASON) {
            emitBoundedTextDelta(response.text)
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
        extraArguments: JsonObject = buildJsonObject {},
        onToolStarted: suspend (String) -> Unit = {},
        onToolFinished: suspend (String, ToolExecutionResult) -> Unit = { _, _ -> },
    ): ToolExecutionResult {
        val toolCallId = UUID.randomUUID().toString()
        val toolArguments =
            buildJsonObject {
                put("command", slashCommand.arguments)
                put("commandName", slashCommand.name)
                put("skillName", slashSkill.displayName)
                extraArguments.forEach { (key, value) ->
                    put(key, value)
                }
            }
        val isCompactDirectDispatch = toolName == COMPACT_TOOL_NAME && slashCommand.name == COMPACT_COMMAND_NAME
        val redactedToolArguments = toolRegistry.redactArguments(toolName, toolArguments).arguments
        val toolCallMessage =
            messageRepository.addMessage(
                sessionId = sessionId,
                role = MessageRole.ToolCall,
                content =
                    if (isCompactDirectDispatch) {
                        "Tool request: $toolName {summary stored in session summary}"
                    } else {
                        "Tool request: $toolName $redactedToolArguments"
                    },
                toolCallId = toolCallId,
                taskRunId = taskRunId,
            )
        val executionArguments =
            if (isCompactDirectDispatch && toolArguments.containsKey("compactedUntilMessageId")) {
                toolArguments.withCompactedUntilMessageId(toolCallMessage.id)
            } else {
                toolArguments
            }
        onToolStarted(toolName)
        val toolResult =
            toolRegistry.execute(
                context =
                    ToolExecutionContext(
                        sessionId = sessionId,
                        taskRunId = taskRunId,
                        origin = ToolInvocationOrigin.SlashCommand,
                        runMode = runMode,
                        requestedName = toolName,
                        canonicalName = toolName,
                        requestId = toolCallId,
                        activeSkillId = slashSkill.id,
                    ),
                arguments = executionArguments,
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

    private suspend fun buildCompactSummaryIfNeeded(
        sessionId: String,
        slashCommand: SlashCommand,
        toolName: String,
        compactBoundaryMessageId: String?,
    ): String? {
        if (toolName != COMPACT_TOOL_NAME || slashCommand.name != COMPACT_COMMAND_NAME || slashCommand.arguments.isNotBlank()) {
            return null
        }
        if (compactBoundaryMessageId.isNullOrBlank()) {
            return null
        }
        val allMessages = messageRepository.getMessages(sessionId)
        val existingSummary = loadSessionSummary(sessionId)
        val sourceMessages =
            allMessages
                .afterBoundary(loadSessionCompactionBoundary(sessionId))
                .withoutCompactControlMessages()
                .throughBoundary(compactBoundaryMessageId)
        if (sourceMessages.isEmpty()) {
            return null
        }
        val fallbackSummary =
            buildLocalCompactSummary(
                existingSummary = existingSummary,
                sourceMessages = sourceMessages,
            )
        val providerSettings = settingsDataStore.settings.first()
        if (providerSettings.providerType == ProviderType.Fake) {
            return fallbackSummary
        }
        if (
            providerSettings.providerType.requiresRemoteSettings &&
            networkStatusProvider?.currentStatus()?.isConnected == false
        ) {
            return fallbackSummary
        }
        val generatedSummary =
            runCatching {
                withContext(Dispatchers.IO) {
                    providerRegistry.require(providerSettings.providerType).generate(
                        ModelRequest(
                            sessionId = sessionId,
                            requestId = "compact-$sessionId-$compactBoundaryMessageId",
                            messageHistory =
                                listOf(
                                    ModelMessage(
                                        role = ModelMessageRole.User,
                                        content =
                                            buildCompactSummaryPrompt(
                                                existingSummary = existingSummary,
                                                sourceMessages = sourceMessages,
                                            ),
                                    ),
                                ),
                            systemPrompt = COMPACT_SYSTEM_PROMPT,
                            enabledSkills = emptyList(),
                            toolDescriptors = emptyList(),
                            runMode = ModelRunMode.Scheduled,
                        ),
                    )
                }
            }.getOrNull()
                ?.text
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        return generatedSummary ?: fallbackSummary
    }

    private fun compactToolArguments(
        compactBoundaryMessageId: String?,
        compactSummary: String?,
    ): JsonObject =
        buildJsonObject {
            compactBoundaryMessageId?.takeIf { it.isNotBlank() }?.let { boundary ->
                put("compactedUntilMessageId", boundary)
            }
            compactSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                put("summary", summary)
            }
        }

    private suspend fun executeProviderToolCalls(
        sessionId: String,
        toolCalls: List<ProviderToolCall>,
        runMode: ModelRunMode,
        requestId: String?,
        taskRunId: String?,
        onToolStarted: suspend (String) -> Unit = {},
        onToolFinished: suspend (String, ToolExecutionResult) -> Unit = { _, _ -> },
    ): List<ModelMessage> =
        buildList {
            toolCalls.forEach { toolCall ->
                val redactedToolArguments =
                    toolRegistry
                        .redactArguments(
                            toolName = toolCall.name,
                            arguments = toolCall.argumentsJson,
                        ).arguments
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = MessageRole.ToolCall,
                    content = "Tool request: ${toolCall.name} $redactedToolArguments",
                    toolCallId = toolCall.id,
                    taskRunId = taskRunId,
                )
                onToolStarted(toolCall.name)
                val toolResult =
                    toolRegistry.execute(
                        context =
                            ToolExecutionContext(
                                sessionId = sessionId,
                                taskRunId = taskRunId,
                                origin =
                                    if (runMode == ModelRunMode.Scheduled) {
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

    private suspend fun persistAssistantResponse(
        sessionId: String,
        assistantText: String,
        selectedSkills: List<SkillSnapshot>,
        directToolResult: ToolExecutionResult? = null,
        providerId: String? = null,
        providerRequestId: String? = null,
        providerModelId: String? = null,
        providerUsage: ai.androidclaw.runtime.providers.ProviderUsage? = null,
        taskRunId: String?,
        exitReason: AgentTurnExitReason = AgentTurnExitReason.Completed,
        triggerSummaryRefresh: Boolean = true,
        sourceUserMessage: String? = null,
        sourceMessageIds: List<String> = emptyList(),
    ): AgentTurnResult {
        val boundedAssistantText = assistantText.toBoundedAgentAssistantText()
        val persistedText =
            boundedAssistantText
                .withActiveSkills(selectedSkills)
                .toBoundedAgentAssistantText()
        val providerMeta =
            providerId?.let { resolvedProviderId ->
                ProviderMessageMeta(
                    providerId = resolvedProviderId,
                    requestId = providerRequestId,
                    modelId = providerModelId,
                    usage = providerUsage?.toPayload(),
                ).toStorageString()
            }
        val assistantMessage =
            messageRepository.addMessage(
                sessionId = sessionId,
                role = MessageRole.Assistant,
                content = persistedText,
                providerMeta = providerMeta,
                taskRunId = taskRunId,
            )
        if (triggerSummaryRefresh) {
            sessionSummaryCoordinator?.onTurnCompleted(sessionId)
        }
        if (exitReason == AgentTurnExitReason.Completed && !sourceUserMessage.isNullOrBlank()) {
            runCatching {
                memoryCoordinator?.captureTurn(
                    sessionId = sessionId,
                    userMessage = sourceUserMessage,
                    assistantMessage = boundedAssistantText,
                    sourceMessageIds = (sourceMessageIds + assistantMessage.id).distinct(),
                )
            }
        }
        return AgentTurnResult(
            assistantMessage = persistedText,
            assistantMessageId = assistantMessage.id,
            selectedSkills = selectedSkills,
            directToolResult = directToolResult,
            providerRequestId = providerRequestId,
            providerMeta = providerMeta,
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
        private const val COMPACT_COMMAND_NAME = "compact"
        private const val COMPACT_TOOL_NAME = "sessions.compact"
        private val COMPACT_SYSTEM_PROMPT =
            """
            You compact AndroidClaw chat sessions.
            Return only a concise plain-text summary.
            Preserve stable facts, user preferences, decisions, current goals, unresolved tasks, and useful technical context.
            Do not include hidden reasoning or chain-of-thought.
            """.trimIndent()
    }
}

private fun List<ChatMessage>.afterBoundary(boundaryMessageId: String?): List<ChatMessage> {
    if (boundaryMessageId.isNullOrBlank()) {
        return this
    }
    val boundaryIndex = indexOfFirst { it.id == boundaryMessageId }
    if (boundaryIndex == -1) {
        return this
    }
    return drop(boundaryIndex + 1)
}

private fun List<ChatMessage>.withoutCompactControlMessages(): List<ChatMessage> = filterNot(ChatMessage::isCompactControlMessage)

private fun ChatMessage.isCompactControlMessage(): Boolean {
    val trimmedContent = content.trim()
    return when (role) {
        MessageRole.User -> trimmedContent.startsWith("/compact")
        MessageRole.ToolCall -> trimmedContent.startsWith("Tool request: sessions.compact")
        MessageRole.ToolResult -> trimmedContent.startsWith("Tool result: Compacted this session")
        MessageRole.Assistant ->
            trimmedContent.startsWith("Compacted this session") &&
                trimmedContent.contains("Active skills: compact")

        MessageRole.System -> false
    }
}

private fun JsonObject.withCompactedUntilMessageId(messageId: String): JsonObject =
    buildJsonObject {
        this@withCompactedUntilMessageId.forEach { (key, value) ->
            if (key == "compactedUntilMessageId") {
                put(key, messageId)
            } else {
                put(key, value)
            }
        }
    }

private fun List<ChatMessage>.throughBoundary(boundaryMessageId: String): List<ChatMessage> {
    val boundaryIndex = indexOfFirst { it.id == boundaryMessageId }
    if (boundaryIndex == -1) {
        return this
    }
    return take(boundaryIndex + 1)
}

private fun buildCompactSummaryPrompt(
    existingSummary: String?,
    sourceMessages: List<ChatMessage>,
): String =
    buildString {
        if (!existingSummary.isNullOrBlank()) {
            appendLine("Existing compacted summary:")
            appendLine(existingSummary.trim())
            appendLine()
            appendLine("Update it with the transcript below.")
        } else {
            appendLine("Create a first compacted summary from the transcript below.")
        }
        appendLine()
        appendLine("Transcript to compact:")
        sourceMessages
            .takeLast(COMPACT_SOURCE_MESSAGE_LIMIT)
            .forEach { message ->
                appendLine("${message.compactRoleLabel()}: ${message.content.trim().take(1_200)}")
            }
    }.trim()

private fun buildLocalCompactSummary(
    existingSummary: String?,
    sourceMessages: List<ChatMessage>,
): String =
    buildString {
        if (!existingSummary.isNullOrBlank()) {
            appendLine("Previous summary:")
            appendLine(existingSummary.trim())
            appendLine()
        }
        appendLine("Recent transcript:")
        sourceMessages
            .takeLast(12)
            .forEach { message ->
                val text =
                    message.content
                        .trim()
                        .replace(COMPACT_WHITESPACE_REGEX, " ")
                        .take(240)
                if (text.isNotBlank()) {
                    appendLine("- ${message.compactRoleLabel()}: $text")
                }
            }
    }.trim().take(COMPACT_SUMMARY_MAX_CHARS)

private fun ChatMessage.compactRoleLabel(): String =
    when (role) {
        MessageRole.User -> "User"
        MessageRole.Assistant -> "Assistant"
        MessageRole.System -> "System"
        MessageRole.ToolCall -> "Tool request"
        MessageRole.ToolResult -> "Tool result"
    }

private const val COMPACT_SUMMARY_MAX_CHARS = 4_000
private const val COMPACT_SOURCE_MESSAGE_LIMIT = 48
private val COMPACT_WHITESPACE_REGEX = Regex("\\s+")

internal fun List<SkillSnapshot>.toBoundedModelSkillMetadata(): List<ModelSkillMetadata> =
    take(MAX_PROMPT_SKILLS).map { skill ->
        ModelSkillMetadata(
            id = skill.id.toAgentSkillText(MAX_PROMPT_SKILL_NAME_CHARS),
            name = skill.displayName.toAgentSkillText(MAX_PROMPT_SKILL_NAME_CHARS),
            description =
                skill.frontmatter
                    ?.description
                    .orEmpty()
                    .toAgentSkillText(MAX_PROMPT_SKILL_DESCRIPTION_CHARS),
            instructions = skill.instructionsMd.toAgentSkillText(MAX_PROMPT_SKILL_INSTRUCTIONS_CHARS),
        )
    }

internal fun String.withActiveSkills(selectedSkills: List<SkillSnapshot>): String {
    if (selectedSkills.isEmpty()) {
        return this
    }
    val displayedSkillNames =
        selectedSkills
            .take(MAX_PROMPT_SKILLS)
            .map { skill -> skill.displayName.trim().toAgentSkillText(MAX_PROMPT_SKILL_NAME_CHARS) }
            .filter(String::isNotBlank)
    if (displayedSkillNames.isEmpty()) {
        return this
    }
    return buildString {
        append(this@withActiveSkills)
        append("\n\nActive skills: ")
        append(displayedSkillNames.joinToString())
        if (selectedSkills.size > MAX_PROMPT_SKILLS) {
            append(", +").append(selectedSkills.size - MAX_PROMPT_SKILLS).append(" more")
        }
    }
}

private fun String.toAgentSkillText(maxChars: Int): String = take(maxChars)

private fun String.toBoundedAgentAssistantText(): String = take(MESSAGE_CONTENT_MAX_CHARS)

internal const val AGENT_PROVIDER_TOOL_CALL_MAX_COUNT = 16
internal const val AGENT_PROVIDER_TOOL_CALL_ID_MAX_CHARS = 256
internal const val AGENT_PROVIDER_TOOL_CALL_NAME_MAX_CHARS = 256
internal const val AGENT_PROVIDER_TOOL_ARGUMENT_JSON_MAX_CHARS = 39_000

internal fun List<ProviderToolCall>.validateProviderToolCallsForAgent(): List<ProviderToolCall> {
    if (size > AGENT_PROVIDER_TOOL_CALL_MAX_COUNT) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider returned too many tool calls.",
            details = "toolCalls=$size max=$AGENT_PROVIDER_TOOL_CALL_MAX_COUNT",
        )
    }
    return map { toolCall -> toolCall.validateForAgent() }
}

private fun ProviderToolCall.validateForAgent(): ProviderToolCall {
    val normalizedId = id.trim()
    val normalizedName = name.trim()
    val serializedArguments = argumentsJson.toString()
    if (normalizedId.isBlank()) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider returned a tool call without an id.",
        )
    }
    if (normalizedId.length > AGENT_PROVIDER_TOOL_CALL_ID_MAX_CHARS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider returned an oversized tool call id.",
            details = normalizedId.take(AGENT_PROVIDER_TOOL_CALL_ID_MAX_CHARS),
        )
    }
    if (normalizedName.isBlank()) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider returned a tool call without a name.",
        )
    }
    if (normalizedName.length > AGENT_PROVIDER_TOOL_CALL_NAME_MAX_CHARS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider returned an oversized tool call name.",
            details = normalizedName.take(AGENT_PROVIDER_TOOL_CALL_NAME_MAX_CHARS),
        )
    }
    if (serializedArguments.length > AGENT_PROVIDER_TOOL_ARGUMENT_JSON_MAX_CHARS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider returned oversized tool arguments.",
            details = serializedArguments.take(500),
        )
    }
    return copy(
        id = normalizedId,
        name = normalizedName,
    )
}

private fun StringBuilder.appendBoundedAgentText(text: String) {
    val remainingChars = MESSAGE_CONTENT_MAX_CHARS - length
    if (remainingChars > 0) {
        append(text.take(remainingChars))
    }
}

private fun ModelRunMode.toModelToolInvocationOrigin(): ToolInvocationOrigin =
    when (this) {
        ModelRunMode.Interactive -> ToolInvocationOrigin.Model
        ModelRunMode.Scheduled -> ToolInvocationOrigin.ScheduledModel
    }

private fun Throwable.isRetryable(): Boolean =
    this is ModelProviderException &&
        (
            kind == ModelProviderFailureKind.Offline ||
                kind == ModelProviderFailureKind.Network ||
                kind == ModelProviderFailureKind.Timeout ||
                kind == ModelProviderFailureKind.StreamInterrupted
        )

private fun Throwable.toFailureKind(): AgentTurnFailureKind =
    when (this) {
        is ModelProviderException ->
            when (kind) {
                ModelProviderFailureKind.Configuration -> AgentTurnFailureKind.Configuration
                ModelProviderFailureKind.InvalidEndpoint -> AgentTurnFailureKind.InvalidEndpoint
                ModelProviderFailureKind.Offline -> AgentTurnFailureKind.Offline
                ModelProviderFailureKind.Authentication -> AgentTurnFailureKind.Authentication
                ModelProviderFailureKind.Network -> AgentTurnFailureKind.Network
                ModelProviderFailureKind.Timeout -> AgentTurnFailureKind.Timeout
                ModelProviderFailureKind.Server -> AgentTurnFailureKind.Server
                ModelProviderFailureKind.StreamInterrupted -> AgentTurnFailureKind.StreamInterrupted
                ModelProviderFailureKind.Response -> AgentTurnFailureKind.Response
            }

        else -> AgentTurnFailureKind.Runtime
    }
